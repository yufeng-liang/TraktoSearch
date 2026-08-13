"""从已导入 App 的 Chiikawa 合成图生成同一基准画布上的透明分层资源。

脚本只读取 App 本地的合成 PNG，不会修改 website 工程。身体层保留完整的归一化底图，
其余图层只提取对应区域中真实存在的轮廓、颜色和表情像素。这样既不会因为矩形挖空产生
接缝，也能让 Compose 对头部、耳朵、脸部和手臂分别施加位移、缩放和旋转。
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
from PIL import Image


RGBA = tuple[int, int, int, int]
Box = tuple[float, float, float, float]
CANVAS_SIZE = 1920


@dataclass(frozen=True)
class CharacterLayerConfig:
    source_names: tuple[tuple[str, str], ...]
    body_color: tuple[int, int, int]
    head_fraction: float
    face_box: Box
    ear_boxes: tuple[Box, ...]
    arm_boxes: tuple[Box, ...]
    remove_right_edge_line: bool = False


CONFIGS = {
    "chiikawa": CharacterLayerConfig(
        source_names=(
            ("standby", "ai_sprite_chiikawa_standby.png"),
            ("peek", "ai_sprite_chiikawa_peek.png"),
            ("react", "ai_sprite_chiikawa_react.png"),
        ),
        body_color=(255, 252, 244),
        head_fraction=0.68,
        face_box=(0.08, 0.28, 0.92, 0.70),
        ear_boxes=((0.25, 0.00, 0.42, 0.24), (0.58, 0.00, 0.75, 0.24)),
        arm_boxes=((0.00, 0.54, 0.27, 0.86), (0.73, 0.54, 1.00, 0.86)),
        remove_right_edge_line=True,
    ),
    "hachiware": CharacterLayerConfig(
        source_names=(
            ("standby", "ai_sprite_hachiware_standby.png"),
            ("peek", "ai_sprite_hachiware_peek.png"),
            ("react", "ai_sprite_hachiware_react.png"),
        ),
        body_color=(255, 255, 255),
        head_fraction=0.66,
        face_box=(0.08, 0.32, 0.92, 0.74),
        ear_boxes=((0.18, 0.00, 0.82, 0.39),),
        arm_boxes=((0.00, 0.54, 0.28, 0.86), (0.72, 0.54, 1.00, 0.86)),
        remove_right_edge_line=True,
    ),
    "usagi": CharacterLayerConfig(
        source_names=(
            ("standby", "ai_sprite_usagi_standby.png"),
            ("peek", "ai_sprite_usagi_peek.png"),
            ("react", "ai_sprite_usagi_react.png"),
        ),
        body_color=(255, 246, 207),
        head_fraction=0.62,
        face_box=(0.08, 0.34, 0.92, 0.76),
        ear_boxes=((0.34, 0.00, 0.49, 0.38), (0.51, 0.00, 0.66, 0.38)),
        arm_boxes=((0.00, 0.54, 0.28, 0.86), (0.72, 0.54, 1.00, 0.86)),
    ),
}


def normalized_box(box: Box, alpha_bbox: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    left, top, right, bottom = alpha_bbox
    width = right - left
    height = bottom - top
    return (
        round(left + box[0] * width),
        round(top + box[1] * height),
        round(left + box[2] * width),
        round(top + box[3] * height),
    )


def is_feature(pixel: RGBA, body_color: tuple[int, int, int]) -> bool:
    red, green, blue, alpha = pixel
    if alpha == 0:
        return False
    dark = max(red, green, blue) < 125
    pink = red > 145 and red > green * 1.12 and blue > green * 0.75 and green < 220
    differs_from_body = max(
        abs(red - body_color[0]),
        abs(green - body_color[1]),
        abs(blue - body_color[2]),
    ) > 34
    return dark or pink or differs_from_body


def infer_body_color(
    source: Image.Image,
    fallback: tuple[int, int, int],
    prefer_fallback: bool = False,
) -> tuple[int, int, int]:
    """从当前姿态的主体像素推断底色，避免不同导出图之间出现白色接缝。"""
    if prefer_fallback:
        return fallback
    candidates = [
        (red, green, blue)
        for red, green, blue, alpha in source.getdata()
        if alpha >= 200 and min(red, green, blue) > 180 and max(red, green, blue) - min(red, green, blue) < 55
    ]
    if not candidates:
        return fallback
    return Counter(candidates).most_common(1)[0][0]


def visible_region_mask(
    source: Image.Image,
    boxes: list[tuple[int, int, int, int]],
) -> bytearray:
    """Create an alpha-clipped elliptical region mask without rectangular layer edges."""
    pixels = source.load()
    mask = bytearray(source.width * source.height)
    for y in range(source.height):
        for x in range(source.width):
            inside_region = False
            for left, top, right, bottom in boxes:
                center_x = (left + right) / 2
                center_y = (top + bottom) / 2
                radius_x = max(1.0, (right - left) / 2)
                radius_y = max(1.0, (bottom - top) / 2)
                distance = ((x - center_x) / radius_x) ** 2 + ((y - center_y) / radius_y) ** 2
                if distance <= 1.0:
                    inside_region = True
                    break
            if pixels[x, y][3] >= 16 and inside_region:
                mask[y * source.width + x] = 1
    return mask


def subtract_mask(mask: bytearray, excluded: bytearray) -> bytearray:
    return bytearray(value and not excluded[index] for index, value in enumerate(mask))


def extract_region(
    source: Image.Image,
    mask: bytearray,
    body_color: tuple[int, int, int],
    features_only: bool = False,
) -> Image.Image:
    """Extract a transparent layer from the source alpha silhouette."""
    result = Image.new("RGBA", source.size, (0, 0, 0, 0))
    source_pixels = source.load()
    result_pixels = result.load()
    for y in range(source.height):
        for x in range(source.width):
            pixel = source_pixels[x, y]
            if mask[y * source.width + x] and (
                not features_only or is_feature(pixel, body_color)
            ):
                result_pixels[x, y] = pixel
    return result


def infer_silhouette_mask(source: Image.Image) -> bytearray:
    """从透明线稿推导实体轮廓，避免 peek 图只剩描边而没有身体填色。"""
    alpha = np.asarray(source.getchannel("A"), dtype=np.uint8)
    line_mask = np.where(alpha >= 16, 255, 0).astype(np.uint8)
    occupied_ratio = float(cv2.countNonZero(line_mask)) / float(line_mask.size)

    # 原有 standby/react 资源已经包含实体填色，直接沿用其 alpha 轮廓。
    if occupied_ratio >= 0.12:
        return bytearray(line_mask.ravel() > 0)

    # peek 参考图是透明线稿，且外轮廓存在几十像素的断笔。选择能闭合
    # 主轮廓的最小闭运算核，避免用过大的核吞掉耳朵、表情等独立细节。
    center = (source.width // 2, source.height // 2)
    minimum_area = max(4_000, line_mask.size * 0.0035)
    main_contour = None
    for kernel_size in (15, 25, 35, 45, 55, 65, 81, 101):
        kernel = cv2.getStructuringElement(
            cv2.MORPH_ELLIPSE,
            (kernel_size, kernel_size),
        )
        closed = cv2.morphologyEx(line_mask, cv2.MORPH_CLOSE, kernel)
        contours, _ = cv2.findContours(
            closed,
            cv2.RETR_EXTERNAL,
            cv2.CHAIN_APPROX_SIMPLE,
        )
        candidates = [
            contour
            for contour in contours
            if cv2.contourArea(contour) >= minimum_area
        ]
        containing_center = [
            contour
            for contour in candidates
            if cv2.pointPolygonTest(contour, center, False) >= 0
        ]
        if containing_center:
            main_contour = max(containing_center, key=cv2.contourArea)
            break

    filled = np.zeros_like(line_mask)
    if main_contour is not None:
        cv2.drawContours(filled, [main_contour], -1, 255, thickness=cv2.FILLED)

    # 资源边界异常时仍保留所有可用轮廓，避免生成空资源；正常 peek 会在
    # 上面的中心包含检查中提前返回，只有损坏素材才会走这个兜底。
    if cv2.countNonZero(filled) == 0:
        closed = cv2.morphologyEx(
            line_mask,
            cv2.MORPH_CLOSE,
            cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (101, 101)),
        )
        contours, _ = cv2.findContours(
            closed,
            cv2.RETR_EXTERNAL,
            cv2.CHAIN_APPROX_SIMPLE,
        )
        for contour in contours:
            if cv2.contourArea(contour) >= minimum_area:
                cv2.drawContours(filled, [contour], -1, 255, thickness=cv2.FILLED)

    # 如果资源的边界断得过多，至少保留原 alpha，避免生成空资源。
    if cv2.countNonZero(filled) == 0:
        filled = line_mask
    return bytearray(filled.ravel() > 0)


def fill_silhouette(
    source: Image.Image,
    silhouette_mask: bytearray,
    body_color: tuple[int, int, int],
) -> Image.Image:
    """用主体色补齐透明线稿，并保留原始描边、表情和手臂像素。"""
    result = source.copy()
    pixels = result.load()
    for y in range(result.height):
        for x in range(result.width):
            index = y * result.width + x
            if silhouette_mask[index] and pixels[x, y][3] < 16:
                pixels[x, y] = (*body_color, 255)
    return result


def replace_pixels_with_body(
    image: Image.Image,
    mask: bytearray,
    body_color: tuple[int, int, int],
) -> None:
    """从身体底层移除将要独立运动的像素，保持各图层之间没有重复描边。"""
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            index = y * image.width + x
            if mask[index] and pixels[x, y][3] >= 16:
                pixels[x, y] = (*body_color, 255)


def remove_right_edge_line(source: Image.Image) -> Image.Image:
    """Remove the opaque door edge present in the two side-peek reference assets.

    The imported references do not use one stable edge color: one door is gray and
    the other is black.  A real character can also touch the right edge, so require
    a contiguous run of columns with a stable color and a mostly constant vertical
    profile before treating it as a door.  This keeps the cleaner reusable if a
    legacy side-peek source is supplied again without trimming a character outline.
    """
    pixels = source.load()
    minimum_x = round(source.width * 0.55)
    required_opaque_rows = source.height * 0.84
    candidates: list[int] = []
    for x in range(source.width - 1, minimum_x, -1):
        opaque_rows = sum(1 for y in range(source.height) if pixels[x, y][3] >= 180)
        if opaque_rows >= required_opaque_rows:
            candidates.append(x)
        elif candidates:
            break

    if len(candidates) < 3:
        return source

    edge_columns = sorted(candidates)
    line_left = edge_columns[0]
    line_right = edge_columns[-1]
    if line_right < source.width - 8:
        return source

    sample_x = line_right
    opaque_pixels = [
        pixels[sample_x, y]
        for y in range(source.height)
        if pixels[sample_x, y][3] >= 180
    ]
    if not opaque_pixels:
        return source
    common_color, common_count = Counter(opaque_pixels).most_common(1)[0]
    if common_count / len(opaque_pixels) < 0.72:
        return source

    # A door edge has the same visible width over most rows; a character outline
    # changes width with the silhouette and therefore fails this consistency check.
    row_widths = []
    for y in range(source.height):
        row_width = sum(
            1
            for x in range(line_left, line_right + 1)
            if pixels[x, y][3] >= 180
        )
        row_widths.append(row_width)
    if sum(width >= max(1, len(edge_columns) - 1) for width in row_widths) < source.height * 0.72:
        return source

    cleaned = source.copy()
    cleaned_pixels = cleaned.load()
    for y in range(cleaned.height):
        for x in range(max(0, line_left - 2), cleaned.width):
            cleaned_pixels[x, y] = (0, 0, 0, 0)
    return cleaned


def normalize_to_canvas(image: Image.Image) -> Image.Image:
    """按 Compose 的 Fit 规则放入统一画布，不拉伸角色比例。"""
    scale = min(CANVAS_SIZE / image.width, CANVAS_SIZE / image.height)
    target_size = (round(image.width * scale), round(image.height * scale))
    resized = image.resize(target_size, Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS_SIZE, CANVAS_SIZE), (0, 0, 0, 0))
    canvas.alpha_composite(
        resized,
        ((CANVAS_SIZE - target_size[0]) // 2, (CANVAS_SIZE - target_size[1]) // 2),
    )
    return canvas


LAYER_NAMES = ("body", "head", "ears", "face", "arms")


def compose_layers(output_dir: Path, character: str, state: str) -> Image.Image:
    """按 Compose 的绘制顺序合成一套分层，供本地视觉回归检查。"""
    composite = Image.new("RGBA", (CANVAS_SIZE, CANVAS_SIZE), (0, 0, 0, 0))
    for layer_name in LAYER_NAMES:
        layer_path = output_dir / f"ai_layer_{character}_{state}_{layer_name}.png"
        with Image.open(layer_path) as layer:
            composite = Image.alpha_composite(composite, layer.convert("RGBA"))
    return composite


def generate_character(character: str, source_dir: Path, output_dir: Path, preview_dir: Path | None) -> None:
    config = CONFIGS[character]
    for state, source_name in config.source_names:
        generate_state_layers(character, state, source_dir / source_name, output_dir, config)

        if preview_dir is not None:
            preview_dir.mkdir(parents=True, exist_ok=True)
            compose_layers(output_dir, character, state).save(
                preview_dir / f"ai_layer_{character}_{state}_preview.png",
                optimize=True,
            )


def generate_state_layers(
    character: str,
    state: str,
    source_path: Path,
    output_dir: Path,
    config: CharacterLayerConfig,
) -> None:
    source = Image.open(source_path).convert("RGBA")
    if config.remove_right_edge_line and state == "peek":
        source = remove_right_edge_line(source)
    source_alpha_ratio = sum(
        alpha >= 16
        for alpha in source.getchannel("A").getdata()
    ) / (source.width * source.height)
    body_color = infer_body_color(
        source,
        config.body_color,
        prefer_fallback=source_alpha_ratio < 0.18,
    )
    alpha_bbox = source.getchannel("A").getbbox()
    if alpha_bbox is None:
        raise ValueError(f"角色资源没有透明主体: {source_path.name}")

    silhouette_mask = infer_silhouette_mask(source)
    filled_source = fill_silhouette(source, silhouette_mask, body_color)

    head_bottom = round(alpha_bbox[1] + config.head_fraction * (alpha_bbox[3] - alpha_bbox[1]))
    head_box = (alpha_bbox[0], alpha_bbox[1], alpha_bbox[2], head_bottom)
    face_box = normalized_box(config.face_box, alpha_bbox)
    ear_boxes = [normalized_box(box, alpha_bbox) for box in config.ear_boxes]
    arm_boxes = [normalized_box(box, alpha_bbox) for box in config.arm_boxes]

    # 身体层保留角色底形，只清除头部/手臂区域的轮廓和表情特征；不挖矩形透明洞。
    # 头部与手臂移动后，底层仍有同色填充，不会出现方形接缝或突然露黑。
    head_mask = visible_region_mask(filled_source, [head_box])
    ears_mask = visible_region_mask(filled_source, ear_boxes)
    face_mask = visible_region_mask(filled_source, [face_box])
    arms_mask = visible_region_mask(filled_source, arm_boxes)
    head_mask = subtract_mask(head_mask, ears_mask)
    head_mask = subtract_mask(head_mask, face_mask)
    head_mask = subtract_mask(head_mask, arms_mask)
    ears_mask = subtract_mask(ears_mask, face_mask)
    arms_mask = subtract_mask(arms_mask, face_mask)

    body = filled_source.copy()
    for mask in (head_mask, ears_mask, face_mask, arms_mask):
        replace_pixels_with_body(body, mask, body_color)

    # 头部层保留真实轮廓、填充和头部特征，脸部/耳朵/手臂分别交给独立图层。
    head = extract_region(filled_source, head_mask, body_color, features_only=True)
    ears = extract_region(filled_source, ears_mask, body_color, features_only=True)
    face = extract_region(filled_source, face_mask, body_color, features_only=True)
    arms = extract_region(filled_source, arms_mask, body_color, features_only=True)

    output_dir.mkdir(parents=True, exist_ok=True)
    layers = dict(zip(LAYER_NAMES, (body, head, ears, face, arms)))
    for layer_name, image in layers.items():
        normalize_to_canvas(image).save(
            output_dir / f"ai_layer_{character}_{state}_{layer_name}.png",
            optimize=True,
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--preview-dir", type=Path)
    args = parser.parse_args()
    for character in CONFIGS:
        generate_character(character, args.source_dir, args.output_dir, args.preview_dir)


if __name__ == "__main__":
    main()

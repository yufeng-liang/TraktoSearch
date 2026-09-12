"""打字机底图预处理：透明像素补色 → 抹掉底图上那半个错的花字 → 按官方字标复刻 TTPD 花字。

产物：app/src/main/res/drawable-nodpi/era_ttpd_typewriter.webp（+ build 里的对照裁片）

**输入是本地素材，不入仓**（都在 .gitignore 的 docs/ 下，见 docs/ttpd-refs/README.md）：

  docs/ttpd-refs/typewriter-ai-base.png       AI 生成并抠好的打字机底座（1536×1024 RGBA）
  docs/ttpd-refs/_official-mark-logotype.png  官方字标最清楚的一张：奶白面 + 深色连体 TTPD
  docs/ttpd-refs/_official-mark-globe.png     整机实拍（用来核对字标在机身上是什么材质）
  docs/ttpd-refs/ttpd-snowglobe-reddit.jpg    官方雪球：机身前脸上的银花字

所以这个脚本在干净 clone 上跑不起来 —— 它记的是「这张底图是怎么来的」，不是可移植的构建步骤。

## 字形为什么从字标里抠，而不是从机器实拍里抠

第一版从机器实拍（`_official-mark-ornament.png`）里提「高度场」，把亮的当成笔画 ——
**读反了**。实拍里亮的是奶白面板、暗的才是笔画（对照 `_official-mark-logotype.png`
与官方雪球：字是刻在浅色面板上的深色连体字）。从糊掉的小图里提反而把字腔的极性搞颠倒，
被点名「还是没画对」。

现在：形状从字标那张 155×74 的清晰图里抠（**像素级对齐**，连笔、字腔、首字母横画末端的
下勾都在），材质仍按机身来 —— 机身是黑漆，字是银的（对照官方雪球）。

## 透明像素必须补色

底图 alpha 边有一圈软边，透明像素里残留的 RGB 是灰黑。缩放或压缩时这圈灰会被吸到不透明的
一侧，纸白边就描出一圈灰黑（见教训 swiftie-backview-drawing-rules）。补成最近的不透明像素
的颜色即可，**alpha 原样留着**。
"""
from __future__ import annotations

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage

ROOT = "F:/trae-project/.worktrees/swiftie-ttpd"
SRC = f"{ROOT}/docs/ttpd-refs/typewriter-ai-base.png"
MARK_REF = f"{ROOT}/docs/ttpd-refs/_official-mark-logotype.png"
OUT = f"{ROOT}/app/src/main/res/drawable-nodpi/era_ttpd_typewriter.webp"

# 字标那张里花字的范围（含余量），量自 471×235 的原图
MARK_REF_BOX = (160, 126, 332, 216)

# 底图上待替换的花字区（AI 画的那半个 serif 字），四周留够抹除的边
PATCH_BOX = (686, 478, 848, 568)

# 新花字宽度（px）。AI 原字 109×45；字标里字形的宽高比是 2.095，
# 所以同宽下新字更矮一点（45 → 50 高），面积与原先相当
MARK_W = 108
MARK_CENTER = (766.0, 521.0)

SILVER = np.array([206, 202, 194], np.float32)

# 底缘那一带的判定阈值：参考图上脚之间的牵线比脚浅得多，按 0.45 的收边阈值取会整段切掉。
# 这一档低阈值**只用在底缘那一带**（见 bottom_band），别的地方仍走收边阈值
FAINT_INK = (0.10, 0.28)

# 底缘那一带的高度与上沿羽化（字标那张的像素）
FOOT_BAND = 11
FOOT_FEATHER = 4.0


def fill_transparent_rgb(img: Image.Image) -> Image.Image:
    """透明像素的 RGB 换成为最近的不透明像素的颜色（alpha 不动）。"""
    a = np.array(img)
    opaque = a[:, :, 3] > 0
    if opaque.all():
        return img
    _, indices = ndimage.distance_transform_edt(~opaque, return_indices=True)
    filled = a.copy()
    nearest = a[indices[0], indices[1]]
    filled[~opaque, :3] = nearest[~opaque, :3]
    return Image.fromarray(filled)


def bottom_band(solid: np.ndarray, shape: tuple[int, int]) -> np.ndarray:
    """底缘那一带的软遮盖：从整幅字的最低点往上 [FOOT_BAND] 像素，上沿羽化。

    只取这一带而不是整幅走低阈值：低阈值会把所有笔画都加胖一圈，
    而需要「认回来」的只有脚的外撇与脚之间的牵线。
    """
    h, _ = shape
    bottom = np.zeros(solid.shape[1], dtype=np.int32)
    for x in range(solid.shape[1]):
        col = np.nonzero(solid[:, x])[0]
        bottom[x] = col.max() if col.size else -1
    base = int(bottom[bottom >= 0].max())
    y = np.arange(h, dtype=np.float32)[:, None]
    return np.clip((y - (base - FOOT_BAND)) / FOOT_FEATHER, 0.0, 1.0).repeat(solid.shape[1], axis=1)


def mark_cover() -> np.ndarray:
    """从官方字标里取出字形的**软遮盖**（1=笔画，0=面板），裁到字形 bbox。

    走软遮盖而不是先二值化：字标那张是 JPEG，硬阈值会在笔画边上留一圈锯齿。
    用面板与笔画的亮度差做归一化，边缘就自带抗锯齿。

    两道整形，都是**对照参考图**来的：

    - **底缘那条横**：官方字标里两条 T 的脚与 P 的脚是**连成一条**的（实拍照片上
      脚之间只是墨色变淡，没有真的断开），阈值一卡就断成三段，字看上去是散的。
      按底缘包络补一条横把它们连起来。这一条是需求方点出来的。
    - **收边**：0.45..0.65 的过渡带，笔画边上不留灰晕。
    """
    g = np.array(Image.open(MARK_REF).convert("L").crop(MARK_REF_BOX)).astype(np.float32)
    g = np.array(Image.fromarray(g.astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8))).astype(np.float32)
    panel = np.percentile(g, 92)        # 面板（浅）
    ink = np.percentile(g, 6)           # 笔画（深）
    raw = np.clip((panel - g) / max(panel - ink, 1e-3), 0.0, 1.0)
    cover = np.clip((raw - 0.45) / 0.20, 0.0, 1.0)
    cover = cover * cover * (3.0 - 2.0 * cover)
    faint_cover = np.clip((raw - FAINT_INK[0]) / (FAINT_INK[1] - FAINT_INK[0]), 0.0, 1.0)
    faint_cover = faint_cover * faint_cover * (3.0 - 2.0 * faint_cover)

    solid = raw > 0.5
    solid[~ndimage.binary_opening(solid, np.ones((2, 2)))] = False
    ys, xs = np.nonzero(solid)
    y0, y1, x0, x1 = ys.min(), ys.max(), xs.min(), xs.max()

    # 每列的底缘（写到 bbox 坐标里）
    h, w = solid.shape
    bottom = np.zeros(w, dtype=np.int32)
    for x in range(w):
        col = np.nonzero(solid[:, x])[0]
        bottom[x] = col.max() if col.size else -1
    span = np.nonzero(bottom >= 0)[0]
    left, right = span.min(), span.max()
    ybot_max = int(bottom[span].max())

    # 底缘那条横：参考图上脚与脚之间是**一条细线**牵着的（墨色比脚淡，阈值一卡就断），
    # 而且这条线**粗细不匀** —— 脚底下是衬线的宽度，脚与脚之间只有一丝。
    # 所以不能铺一条等高的横（那会把底糊成一块），而是按**低阈值**的墨色逐列取
    # 「底段顶」：脚底下取到衬线的上沿、脚之间只取到那一丝的上沿，粗细自己就对了
    # 底缘那一带：参考图上脚是**外撇**的（笔画到底部往外张开成衬线脚），脚与脚之间再由
    # 一条淡墨色的细线牵着 —— 粗细本来就不匀，脚底下厚、脚之间一丝。
    # 0.45 那一档收边把外撇和细线一起切掉了，于是底缘成了「棍子踩在台子上」的直角台阶。
    # 所以底缘这一带**另走一道低阈值**，把外撇与牵线原样认回来：不铺等高横，粗细照参考图
    cover = np.maximum(cover, faint_cover * bottom_band(solid, raw.shape))
    # 整体再抹一道很轻的模糊：把阈值留下的 1px 台阶化成斜坡，缩到目标尺寸就看不见锯齿了
    cover = np.array(Image.fromarray((cover * 255).astype(np.uint8))
                     .filter(ImageFilter.GaussianBlur(1.0))).astype(np.float32) / 255.0

    ys, xs = np.nonzero(cover > 0.5)
    return np.ascontiguousarray(cover[ys.min():ys.max() + 1, xs.min():xs.max() + 1])


def patch_mark(img: Image.Image) -> None:
    """把 AI 画的那个花字抹成机身前脸的平滑渐变：逐行取左右两侧的中位色，横向线性插值。"""
    a = np.array(img).astype(np.float32)
    x0, y0, x1, y1 = PATCH_BOX
    pad = 26
    for y in range(y0, y1):
        left = a[y, x0 - pad:x0 - 4].mean(axis=0)
        right = a[y, x1 + 4:x1 + pad].mean(axis=0)
        t = np.linspace(0.0, 1.0, x1 - x0, dtype=np.float32)[:, None]
        a[y, x0:x1, :3] = left[:3] * (1.0 - t) + right[:3] * t
    out = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    # 抹完那块比周围平，轻微模糊化开接缝，再把边界一圈羽化回去
    out.paste(out.crop((x0 - 4, y0 - 4, x1 + 4, y1 + 4)).filter(ImageFilter.GaussianBlur(3.5)), (x0 - 4, y0 - 4))
    img.paste(out, (0, 0))


def stamp_mark(img: Image.Image, cover: np.ndarray) -> None:
    """把字形压到前脸上：灯在左上，笔画上缘一条高光、右下压一道暗边，读作铸出来的银字。"""
    pw = MARK_W
    ph = int(round(pw * cover.shape[0] / cover.shape[1]))
    # 缩放走 float 通道（fromarray 见到 float32 就是 "F"）；
    # 108/155 是缩下去，软遮盖自带抗锯齿，不用再加模糊
    cov = np.array(Image.fromarray(cover.astype(np.float32)).resize((pw, ph), Image.BICUBIC), np.float32)

    def shift(dx: float, dy: float) -> np.ndarray:
        return ndimage.shift(cov, (-dy, -dx), order=1, mode="nearest")

    hi = np.clip(cov - shift(1.1, 1.1), 0.0, 1.0)      # 笔画的上/左缘
    lo = np.clip(shift(-1.1, -1.1) - cov, 0.0, 1.0)     # 笔画的下/右缘
    yy = np.linspace(0.0, 1.0, ph, dtype=np.float32)[:, None]
    bright = np.clip(0.80 + 0.40 * hi - 0.34 * lo + 0.06 * (1.0 - yy), 0.0, 1.35)
    rgb = np.clip(SILVER[None, None, :] * bright[:, :, None], 0, 255)

    cx, cy = MARK_CENTER
    x, y = int(round(cx - pw / 2.0)), int(round(cy - ph / 2.0))
    arr = np.array(img).astype(np.float32)
    sub = arr[y:y + ph, x:x + pw, :3]
    m = cov[:, :, None]
    arr[y:y + ph, x:x + pw, :3] = sub * (1.0 - m) + rgb * m
    img.paste(Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)), (0, 0))


def main() -> None:
    cover = mark_cover()
    print("字形软遮盖 %dx%d（宽高比 %.3f）" % (cover.shape[1], cover.shape[0], cover.shape[1] / cover.shape[0]))

    img = fill_transparent_rgb(Image.open(SRC).convert("RGBA"))
    patch_mark(img)
    stamp_mark(img, cover)
    # 有损 q=92 而不是无损：APK 里省下 800KB，代价在图上看不出来。
    # 透明像素已经补过色，有损压缩不会在轮廓上咬出灰边（这是补色的意义所在）
    img.save(OUT, format="WEBP", quality=92, method=6)
    print("写出", OUT)

    crop = img.crop((620, 440, 920, 600)).resize((1200, 640), Image.LANCZOS)
    ImageDraw.Draw(crop).rectangle(
        [((PATCH_BOX[0] - 620) * 4, (PATCH_BOX[1] - 440) * 4), ((PATCH_BOX[2] - 620) * 4, (PATCH_BOX[3] - 440) * 4)],
        outline=(255, 80, 80), width=1)
    crop.save(f"{ROOT}/build/mark/face_after.png")


if __name__ == "__main__":
    main()

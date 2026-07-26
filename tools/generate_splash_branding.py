from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont


S = 4
W, H = 1200, 374
TICKET_SCALE = 1.03
PAPER = "#F7EFE2"
PAPER_DEEP = "#E8D3BA"
CLAY = "#996345"
CLAY_DARK = "#76503B"
INK = "#503D32"
MUTED = "#876D5A"


def font(path: str, size: int, index: int = 0, variation: str | None = None):
    try:
        loaded = ImageFont.truetype(path, size, index=index)
        if variation:
            loaded.set_variation_by_name(variation)
        return loaded
    except OSError:
        return ImageFont.load_default()


MONO = "C:/Windows/Fonts/consola.ttf"
SERIF = "C:/Windows/Fonts/georgiab.ttf"
CHINESE = "C:/Windows/Fonts/NotoSerifSC-VF.ttf"


def draw_spaced_text(draw, position, text, text_font, fill, spacing):
    x, y = position
    for character in text:
        draw.text((x, y), character, font=text_font, fill=fill)
        x += draw.textlength(character, font=text_font) + spacing


def draw_centered_spaced_text(draw, center_x, y, text, text_font, fill, spacing):
    width = sum(draw.textlength(character, font=text_font) for character in text)
    width += spacing * max(0, len(text) - 1)
    draw_spaced_text(draw, (center_x - width / 2, y), text, text_font, fill, spacing)


def rounded_rect_points(box, radius, samples=12):
    left, top, right, bottom = box
    points = []
    corners = ((right - radius, top + radius, -90, 0),
               (right - radius, bottom - radius, 0, 90),
               (left + radius, bottom - radius, 90, 180),
               (left + radius, top + radius, 180, 270))
    for center_x, center_y, start, end in corners:
        for index in range(samples + 1):
            angle = start + (end - start) * index / samples
            from math import cos, radians, sin
            points.append((center_x + radius * cos(radians(angle)),
                           center_y + radius * sin(radians(angle))))
    return points


def dashed_path(draw, points, dash=20, gap=12, width=3, fill=CLAY):
    distance = 0.0
    remaining = dash
    drawing = True
    for start, end in zip(points, points[1:] + points[:1]):
        x1, y1 = start
        x2, y2 = end
        segment = ((x2 - x1) ** 2 + (y2 - y1) ** 2) ** 0.5
        while segment > 0:
            length = min(remaining, segment)
            ratio = length / segment
            next_point = (x1 + (x2 - x1) * ratio, y1 + (y2 - y1) * ratio)
            if drawing:
                draw.line((x1, y1, next_point[0], next_point[1]), fill=fill, width=width)
            x1, y1 = next_point
            segment -= length
            remaining -= length
            if remaining <= 0:
                drawing = not drawing
                remaining = dash if drawing else gap


def dashed_rounded_rectangle(draw, box, radius, dash=20, gap=12, width=3, fill=CLAY):
    dashed_path(draw, rounded_rect_points(box, radius), dash, gap, width, fill)


def dashed_line(draw, start, end, dash=20, gap=12, width=3, fill=CLAY):
    x1, y1 = start
    x2, y2 = end
    if y1 == y2:
        x = x1
        while x < x2:
            draw.line((x, y1, min(x + dash, x2), y2), fill=fill, width=width)
            x += dash + gap
    else:
        y = y1
        while y < y2:
            draw.line((x1, y, x2, min(y + dash, y2)), fill=fill, width=width)
            y += dash + gap


def draw_film_strip(draw, x, y, size, fill=CLAY):
    width = round(size * 0.92)
    height = round(size * 0.62)
    stroke = max(2, round(size * 0.06))
    draw.rounded_rectangle((x, y + size * 0.18, x + width, y + size * 0.18 + height),
                           radius=round(size * 0.08), outline=fill, width=stroke)
    for hole_x in (x + size * 0.12, x + size * 0.42, x + size * 0.72):
        hole_width = size * 0.12
        hole_height = size * 0.11
        for hole_y in (y + size * 0.02, y + size * 0.82):
            draw.rounded_rectangle((hole_x, hole_y, hole_x + hole_width, hole_y + hole_height),
                                   radius=2, outline=fill, width=max(2, stroke - 1))
    draw.rectangle((x + size * 0.24, y + size * 0.31, x + size * 0.68, y + size * 0.68),
                   outline=fill, width=max(2, stroke - 1))


def draw_mini_clapper(draw, x, y, size, fill=CLAY):
    stroke = max(2, round(size * 0.06))
    body_left = x + size * 0.12
    body_top = y + size * 0.32
    body_right = x + size * 0.92
    body_bottom = y + size * 0.88
    draw.rounded_rectangle((body_left, body_top, body_right, body_bottom),
                           radius=round(size * 0.08), outline=fill, width=stroke)
    bar = [(x + size * 0.12, y + size * 0.28),
           (x + size * 0.88, y + size * 0.12),
           (x + size * 0.94, y + size * 0.28),
           (x + size * 0.18, y + size * 0.44),
           (x + size * 0.12, y + size * 0.28)]
    draw.line(bar, fill=fill, width=stroke, joint="curve")
    for offset in (0.34, 0.58):
        draw.line((x + size * offset, y + size * 0.23,
                   x + size * (offset + 0.08), y + size * 0.31),
                  fill=fill, width=stroke)
    draw.line((body_left + size * 0.12, y + size * 0.62,
               body_right - size * 0.12, y + size * 0.62), fill=fill, width=max(2, stroke - 1))


def draw_popcorn(draw, x, y, size):
    draw.polygon(
        [(x + 6, y + size * 0.35), (x + size - 6, y + size * 0.35),
         (x + size * 0.78, y + size), (x + size * 0.22, y + size)],
        outline=CLAY,
    )
    for offset in (0.28, 0.5, 0.72):
        draw.line((x + size * offset, y + size * 0.38, x + size * offset - 3, y + size * 0.94), fill=CLAY, width=3)
    for cx, cy, r in ((0.22, 0.28, 0.14), (0.5, 0.17, 0.17), (0.77, 0.28, 0.14)):
        draw.ellipse((x + size * (cx - r), y + size * (cy - r), x + size * (cx + r), y + size * (cy + r)), fill=PAPER)
        draw.arc((x + size * (cx - r), y + size * (cy - r), x + size * (cx + r), y + size * (cy + r)), 180, 360, fill=CLAY, width=3)


def main():
    image = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    ticket = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    td = ImageDraw.Draw(ticket)
    left, top, right, bottom = 36, 58, W - 36, H - 16
    # 网页的 0 5px rgba(80, 61, 50, .08) 阴影。
    td.rounded_rectangle((left, top + 16, right, bottom + 16), radius=16,
                         fill=(80, 61, 50, 20))
    td.rounded_rectangle((left, top, right, bottom), radius=16, fill=PAPER_DEEP,
                         outline=(80, 61, 50, 82), width=4)
    # 两侧检票孔。
    for cx in (left, right):
        td.ellipse((cx - 22, (top + bottom) // 2 - 22, cx + 22, (top + bottom) // 2 + 22),
                   fill=PAPER, outline=(80, 61, 50, 64), width=3)

    divider_x = 940
    td.rectangle((58, 87, 911, 329), outline=(153, 99, 69, 72), width=2)
    dashed_line(td, (divider_x, 87), (divider_x, 329), dash=22, gap=16,
                width=3, fill=(80, 61, 50, 90))
    td.rectangle((966, 84, 1138, 332), outline=(80, 61, 50, 66), width=2)

    draw_spaced_text(td, (94, 94), "PERSONAL CINEMA ACCESS", font(MONO, 39),
                     CLAY_DARK, 7.8)
    draw_spaced_text(td, (94, 142), "从观影清单到网盘资源", font(CHINESE, 66, variation="Bold"),
                     INK, 1.95)
    draw_spaced_text(td, (94, 235), "TRAKT · WATCHLIST · SCREENING PASS", font(MONO, 33),
                     MUTED, 2.7)
    draw_popcorn(td, 674, 278, 48)
    draw_film_strip(td, 750, 276, 52)
    draw_mini_clapper(td, 838, 274, 54)
    draw_centered_spaced_text(td, 1052, 90, "07", font(SERIF, 112), CLAY_DARK, 0)
    draw_centered_spaced_text(td, 1052, 286, "SCREEN", font(MONO, 27), CLAY_DARK, 3.3)

    # CSS 的 overflow:hidden 会把打孔裁成票根边缘的缺口，不能保留贯穿的直线边框。
    ticket_mask = Image.new("L", (W, H), 0)
    mask_draw = ImageDraw.Draw(ticket_mask)
    mask_draw.rounded_rectangle((left, top, right, bottom), radius=16, fill=255)
    ticket.putalpha(ImageChops.multiply(ticket.getchannel("A"), ticket_mask))

    rotated = ticket.rotate(1.4, resample=Image.Resampling.BICUBIC, center=(W / 2, H / 2))
    scaled_width = round(W * TICKET_SCALE)
    scaled_height = round(H * TICKET_SCALE)
    scaled_ticket = rotated.resize((scaled_width, scaled_height), resample=Image.Resampling.BICUBIC)
    crop_left = (scaled_width - W) // 2
    crop_top = (scaled_height - H) // 2
    image.alpha_composite(scaled_ticket.crop((crop_left, crop_top, crop_left + W, crop_top + H)))

    output = Path("app/src/main/res/drawable-xxxhdpi/splash_branding_image.png")
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, format="PNG", optimize=True)


if __name__ == "__main__":
    main()

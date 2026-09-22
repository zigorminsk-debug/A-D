#!/usr/bin/env python3
"""Четыре иконки «Дневник АД» и лист выбора. Фон до краёв — под маску Android."""

from PIL import Image, ImageDraw, ImageFilter, ImageFont
import math
from pathlib import Path

OUT = Path("/home/user/A-D/bp-diary-app/design/icon-choices")
OUT.mkdir(parents=True, exist_ok=True)

S = 1024
NAVY_TOP = (42, 104, 158)
NAVY_BOT = (22, 58, 96)
NAVY = (31, 78, 121)
SCREEN = (18, 46, 78)
CORAL = (236, 90, 78)
CORAL_DEEP = (214, 72, 64)
WHITE = (255, 255, 255)
INK = (31, 78, 121)
BTN = (186, 206, 220)
CUFF = (232, 239, 245)
CUFF_STRIPE = (46, 116, 181)
SKIN = (242, 196, 164)
SKIN_EDGE = (214, 156, 124)
PAGE = (226, 234, 242)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONTB = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def gradient(size, top, bot):
    col = Image.new("RGB", (1, size))
    px = col.load()
    for y in range(size):
        t = y / (size - 1)
        px[0, y] = tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3))
    return col.resize((size, size), Image.Resampling.NEAREST).convert("RGBA")


def base():
    img = gradient(S, NAVY_TOP, NAVY_BOT)
    # мягкий блик сверху, чтобы квадрат не был плоским
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    g.ellipse((40, -180, 980, 620), fill=(255, 255, 255, 28))
    glow = glow.filter(ImageFilter.GaussianBlur(40))
    img.alpha_composite(glow)
    return img


def shadow_under(canvas, mask, offset=(0, 16), blur=22, alpha=80):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    black = Image.new("RGBA", canvas.size, (0, 0, 0, alpha))
    layer.paste(black, offset, mask)
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(layer)


def heart_poly(cx, cy, scale, n=360):
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        x = 16 * math.sin(t) ** 3
        y = 13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t)
        pts.append((cx + x * scale, cy - y * scale))
    return pts


def ecg_pts(x, y, scale, amp=1.0):
    rel = [
        (0, 0), (18, 0), (24, -8), (30, 0),
        (42, 0), (48, 6), (55, -36), (62, 16), (68, 0),
        (82, 0), (90, -11), (98, 0), (118, 0),
    ]
    return [(x + px * scale, y + py * scale * amp) for px, py in rel]


def draw_ecg(draw, x, y, scale, color, width, amp=1.0):
    pts = ecg_pts(x, y, scale, amp)
    draw.line(pts, fill=color, width=width, joint="curve")
    r = width / 2
    for px, py in (pts[0], pts[-1]):
        draw.ellipse((px - r, py - r, px + r, py + r), fill=color)


def rr(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def icon_tonometer():
    img = base()
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    body = (250, 312, 650, 736)
    rr(md, body, 68, 255)
    cuff = (678, 408, 808, 556)
    rr(md, cuff, 44, 255)
    shadow_under(img, mask, (0, 18), 26, 90)
    d = ImageDraw.Draw(img)
    rr(d, body, 68, WHITE)
    screen = (290, 352, 610, 592)
    rr(d, screen, 36, SCREEN)
    draw_ecg(d, 318, 488, 2.35, CORAL, 18, 1.05)
    for cx in (390, 510):
        d.ellipse((cx - 22, 640, cx + 22, 684), fill=BTN)
    d.line((650, 482, 690, 482), fill=WHITE, width=16)
    d.ellipse((642, 474, 658, 490), fill=WHITE)
    rr(d, cuff, 44, CUFF)
    rr(d, (730, 408, 764, 556), 8, CUFF_STRIPE)
    return img


def icon_heart():
    img = base()
    poly = heart_poly(512, 548, 16.2)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).polygon(poly, fill=255)
    shadow_under(img, mask, (0, 18), 28, 90)
    d = ImageDraw.Draw(img)
    d.polygon(poly, fill=WHITE)
    draw_ecg(d, 250, 528, 4.45, CORAL, 26, 1.15)
    return img


def icon_diary():
    img = base()
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    book = (332, 236, 692, 800)
    rr(md, book, 36, 255)
    shadow_under(img, mask, (0, 18), 26, 90)
    d = ImageDraw.Draw(img)
    # страницы справа
    for i, dx in enumerate((18, 34, 50)):
        rr(d, (692 - 8 + dx, 268 + i * 6, 692 + dx, 768 - i * 6), 10, PAGE)
    rr(d, book, 36, WHITE)
    # корешок
    d.line((392, 270, 392, 766), fill=(214, 224, 232), width=8)
    # закладка
    d.polygon([(448, 236), (512, 236), (512, 360), (480, 328), (448, 360)], fill=CORAL)
    # пульс на обложке — это дневник давления, не просто книжка
    draw_ecg(d, 430, 560, 1.85, CORAL, 16, 1.0)
    d.line((448, 620, 620, 620), fill=(214, 224, 232), width=8)
    d.line((448, 656, 580, 656), fill=(214, 224, 232), width=8)
    return img


def paste_rotated(base, color, w, h, radius, angle, center):
    tile = Image.new("RGBA", (w, h), color)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=radius, fill=255)
    tile.putalpha(mask)
    tile = tile.rotate(angle, expand=True, resample=Image.Resampling.BICUBIC)
    base.alpha_composite(tile, (int(center[0] - tile.width / 2), int(center[1] - tile.height / 2)))


def icon_cuff():
    img = base()
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    paste_rotated(layer, SKIN_EDGE, 520, 168, 84, -32, (512, 500))
    paste_rotated(layer, SKIN, 500, 136, 68, -32, (512, 496))
    paste_rotated(layer, WHITE, 168, 250, 36, 58, (500, 470))
    paste_rotated(layer, CUFF_STRIPE, 42, 250, 8, 58, (500, 470))
    d = ImageDraw.Draw(layer)
    d.line((575, 560, 640, 640), fill=WHITE, width=16)
    d.ellipse((612, 612, 748, 748), fill=WHITE)
    d.ellipse((636, 636, 724, 724), fill=SCREEN)
    d.line((680, 680, 712, 652), fill=CORAL, width=12)
    d.ellipse((670, 670, 690, 690), fill=CORAL)
    alpha = layer.split()[-1]
    shadow_under(img, alpha, (0, 16), 24, 80)
    img.alpha_composite(layer)
    return img


def squircle_mask(size, power=4.6, inset=2):
    mask = Image.new("L", (size, size), 0)
    px = mask.load()
    cx = cy = (size - 1) / 2
    r = size / 2 - inset
    for y in range(size):
        ny = abs(y - cy) / r
        ny_p = ny ** power
        for x in range(size):
            nx = abs(x - cx) / r
            if nx ** power + ny_p <= 1:
                # мягкий край
                v = 1 - (nx ** power + ny_p)
                a = 255 if v > 0.02 else int(255 * v / 0.02)
                px[x, y] = a
    return mask.filter(ImageFilter.GaussianBlur(0.6))


def masked(icon, size):
    im = icon.resize((size, size), Image.Resampling.LANCZOS)
    m = squircle_mask(size)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(im, (0, 0), m)
    return out


def save_icon(name, icon):
    icon.save(OUT / f"{name}-full.png")
    masked(icon, 512).save(OUT / f"{name}.png")


def choice_sheet(icons):
    W, H = 1680, 860
    sheet = Image.new("RGB", (W, H), (244, 247, 251))
    d = ImageDraw.Draw(sheet)
    title = ImageFont.truetype(FONTB, 42)
    name_f = ImageFont.truetype(FONTB, 28)
    sub_f = ImageFont.truetype(FONT, 20)
    d.text((W / 2, 48), "Иконки для «Дневник АД»", font=title, fill=NAVY, anchor="ma")
    labels = [
        ("1. Тонометр", "прибор и пульс"),
        ("2. Сердце", "пульс на сердце"),
        ("3. Дневник", "книжка измерений"),
        ("4. Манжета", "измерение на руке"),
    ]
    gap = W / 4
    for i, (icon, (name, sub)) in enumerate(zip(icons, labels)):
        cx = gap * i + gap / 2
        big = masked(icon, 300)
        # тень под иконкой
        sh = Image.new("RGBA", (300, 300), (0, 0, 0, 0))
        sm = squircle_mask(300)
        black = Image.new("RGBA", (300, 300), (20, 40, 70, 50))
        sh.paste(black, (0, 0), sm)
        sh = sh.filter(ImageFilter.GaussianBlur(12))
        sheet.paste(sh, (int(cx - 150), 168), sh)
        sheet.paste(big, (int(cx - 150), 150), big)
        small = masked(icon, 72)
        sheet.paste(small, (int(cx - 36), 480), small)
        d.text((cx, 590), name, font=name_f, fill=(26, 26, 26), anchor="ma")
        d.text((cx, 630), sub, font=sub_f, fill=(102, 112, 122), anchor="ma")
        d.text((cx, 700), "как на экране", font=sub_f, fill=(150, 160, 170), anchor="ma")
    sheet.save(OUT / "choice-sheet.png", quality=95)
    sheet.save("/tmp/choice-sheet.png", quality=95)


def main():
    icons = [
        ("01-tonometer", icon_tonometer()),
        ("02-heart", icon_heart()),
        ("03-diary", icon_diary()),
        ("04-cuff", icon_cuff()),
    ]
    for name, im in icons:
        save_icon(name, im)
        print("saved", name)
    choice_sheet([im for _, im in icons])
    print("sheet ok")


if __name__ == "__main__":
    main()

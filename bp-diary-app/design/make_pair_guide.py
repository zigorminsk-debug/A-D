#!/usr/bin/env python3
"""Картинки пошаговой инструкции: два замера и среднее в дневнике."""

from PIL import Image, ImageDraw, ImageFont
from pathlib import Path

OUT = Path("/home/user/A-D/bp-diary-app/design/pair-guide")
RES = Path("/home/user/A-D/bp-diary-app/app/src/main/res/drawable-nodpi")
OUT.mkdir(parents=True, exist_ok=True)
RES.mkdir(parents=True, exist_ok=True)

W, H = 1200, 720
NAVY = (31, 78, 121)
NAVY_SOFT = (232, 240, 248)
INK = (26, 32, 40)
GRAY = (102, 112, 122)
CORAL = (214, 72, 64)
GREEN = (46, 125, 50)
AMBER = (249, 168, 37)
WHITE = (255, 255, 255)
BG = (238, 244, 250)
SKIN = (242, 196, 164)
SKIN_EDGE = (214, 156, 124)
SCREEN = (18, 46, 78)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONTB = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def fnt(size, bold=False):
    return ImageFont.truetype(FONTB if bold else FONT, size)


def canvas():
    img = Image.new("RGB", (W, H), BG)
    return img, ImageDraw.Draw(img)


def rr(d, box, radius, fill=None, outline=None, width=1):
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def text_c(d, xy, text, font, fill):
    d.text(xy, text, font=font, fill=fill, anchor="mm")


def phone(d, box):
    rr(d, box, 40, NAVY)
    screen = (box[0] + 16, box[1] + 18, box[2] - 16, box[3] - 18)
    rr(d, screen, 28, (244, 247, 251))
    return screen


def btn(d, box, label, fill, color, font, radius=16):
    rr(d, box, radius, fill)
    text_c(d, ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2), label, font, color)


def field(d, box, hint, value):
    rr(d, box, 14, WHITE, NAVY, 3)
    d.text((box[0] + 16, box[1] + 8), hint, font=fnt(16), fill=GRAY)
    if value:
        d.text((box[0] + 16, box[1] + 32), value, font=fnt(28, True), fill=INK)


def save(img, name):
    preview = img.resize((900, 540), Image.Resampling.LANCZOS)
    preview.save(OUT / f"{name}.png", optimize=True)
    preview.save(RES / f"{name}.webp", format="WEBP", quality=82, method=6)


def step_prepare():
    img, d = canvas()
    title = fnt(34, True)
    d.text((40, 28), "Перед замером — 5 минут покоя", font=title, fill=NAVY)

    panels = [
        (40, 100, 400, 640, "Спина и ноги"),
        (420, 100, 780, 640, "Манжета"),
        (800, 100, 1160, 640, "Тишина"),
    ]
    for box, cap in ((p[:4], p[4]) for p in panels):
        rr(d, box, 28, WHITE)
        text_c(d, ((box[0] + box[2]) / 2, box[3] - 36), cap, fnt(26, True), NAVY)

    # 1. человек на стуле, вид сбоку
    cx = 220
    # спинка
    rr(d, (78, 180, 118, 430), 10, NAVY_SOFT)
    # сиденье
    rr(d, (108, 400, 250, 436), 10, NAVY_SOFT)
    # голова
    d.ellipse((168, 168, 248, 248), fill=SKIN, outline=SKIN_EDGE, width=3)
    # туловище
    rr(d, (176, 246, 250, 410), 28, (46, 116, 181))
    # ноги вниз, не скрещены
    d.line((196, 408, 176, 540), fill=NAVY, width=16)
    d.line((230, 408, 250, 540), fill=NAVY, width=16)
    d.ellipse((156, 528, 196, 552), fill=NAVY)
    d.ellipse((232, 528, 272, 552), fill=NAVY)
    # стол и рука
    rr(d, (248, 318, 360, 348), 8, (214, 224, 232))
    d.line((236, 300, 340, 300), fill=SKIN, width=22)
    rr(d, (268, 278, 318, 322), 8, WHITE, NAVY, 4)
    d.text((70, 200), "спина\nк спинке", font=fnt(16), fill=GRAY)
    d.text((120, 500), "ноги не скрещены", font=fnt(16), fill=GRAY)

    # 2. манжета на уровне сердца
    d.line((500, 250, 700, 250), fill=SKIN, width=70)
    rr(d, (560, 190, 660, 310), 18, WHITE, NAVY, 5)
    rr(d, (596, 190, 628, 310), 6, (46, 116, 181))
    d.line((470, 250, 740, 250), fill=CORAL, width=3)
    d.text((600, 360), "на уровне сердца", font=fnt(20), fill=CORAL, anchor="mm")
    d.text((600, 400), "на 2–3 см выше локтя", font=fnt(18), fill=GRAY, anchor="mm")

    # 3. тишина и 5 минут
    text_c(d, (980, 250), "5", fnt(92, True), NAVY)
    text_c(d, (980, 330), "минут", fnt(28, True), NAVY)
    rr(d, (860, 390, 1100, 470), 20, (255, 236, 234))
    text_c(d, (980, 430), "не разговаривать", fnt(22, True), CORAL)
    return img


def step_plus():
    img, d = canvas()
    d.text((40, 28), "Вкладка «Дневник»", font=fnt(34, True), fill=NAVY)
    screen = phone(d, (360, 90, 840, 680))
    # карточка сводки
    rr(d, (screen[0] + 24, screen[1] + 24, screen[2] - 24, screen[1] + 150), 16, WHITE)
    d.text((screen[0] + 44, screen[1] + 44), "Сводка", font=fnt(22, True), fill=NAVY)
    d.text((screen[0] + 44, screen[1] + 84), "Нажмите «+», чтобы начать два замера", font=fnt(18), fill=GRAY)
    # кнопка +
    fab = (screen[2] - 110, screen[3] - 120, screen[2] - 36, screen[3] - 46)
    d.ellipse(fab, fill=NAVY)
    text_c(d, ((fab[0] + fab[2]) / 2, (fab[1] + fab[3]) / 2 - 2), "+", fnt(48, True), WHITE)
    # стрелка-подпись
    d.line((900, 250, 760, 500), fill=CORAL, width=8)
    rr(d, (860, 160, 1140, 250), 18, CORAL)
    text_c(d, (1000, 205), "нажмите +", fnt(28, True), WHITE)
    return img


def step_first():
    img, d = canvas()
    d.text((36, 22), "Замер 1 из 2", font=fnt(32, True), fill=NAVY)
    screen = phone(d, (70, 80, 760, 690))
    card = (screen[0] + 18, screen[1] + 18, screen[2] - 18, screen[3] - 18)
    rr(d, card, 18, WHITE)
    d.text((card[0] + 24, card[1] + 16), "Замер 1 из 2", font=fnt(26, True), fill=NAVY)
    d.text((card[0] + 24, card[1] + 56), "Введите первый замер. Потом минута покоя и сигнал.", font=fnt(16), fill=GRAY)
    field(d, (card[0] + 24, card[1] + 100, card[2] - 24, card[1] + 172), "Систолическое (верхнее)", "128")
    field(d, (card[0] + 24, card[1] + 186, card[2] - 24, card[1] + 258), "Диастолическое (нижнее)", "78")
    field(d, (card[0] + 24, card[1] + 272, card[2] - 24, card[1] + 344), "Пульс", "72")
    btn(d, (card[0] + 24, card[3] - 150, card[2] - 24, card[3] - 96), "Дальше", NAVY, WHITE, fnt(24, True))
    btn(d, (card[0] + 24, card[3] - 84, card[0] + 250, card[3] - 28), "Отмена", NAVY_SOFT, NAVY, fnt(18))
    btn(d, (card[0] + 266, card[3] - 84, card[2] - 24, card[3] - 28), "Один замер", (255, 236, 234), CORAL, fnt(18, True))

    rr(d, (800, 120, 1160, 280), 22, (255, 236, 234))
    d.text((828, 146), "Не нажимайте", font=fnt(26, True), fill=CORAL)
    d.text((828, 190), "«Один замер».", font=fnt(26, True), fill=CORAL)
    d.text((828, 232), "Тогда среднее не запишется.", font=fnt(20), fill=INK)
    rr(d, (800, 320, 1160, 560), 22, WHITE)
    d.text((828, 348), "Тонометр показал", font=fnt(20), fill=GRAY)
    d.text((828, 390), "128 / 78", font=fnt(48, True), fill=NAVY)
    d.text((828, 470), "пульс 72", font=fnt(24), fill=GRAY)
    d.text((828, 514), "Эти числа — в поля слева.", font=fnt(18), fill=INK)
    return img


def step_wait():
    img, d = canvas()
    d.text((36, 22), "Минута между замерами", font=fnt(32, True), fill=NAVY)
    screen = phone(d, (180, 80, 780, 680))
    card = (screen[0] + 22, screen[1] + 28, screen[2] - 22, screen[3] - 28)
    rr(d, card, 18, WHITE)
    d.text((card[0] + 28, card[1] + 24), "Минута между замерами", font=fnt(26, True), fill=NAVY)
    text_c(d, ((card[0] + card[2]) / 2, card[1] + 180), "1:00", fnt(92, True), NAVY)
    d.text((card[0] + 36, card[1] + 270), "Сядьте спокойно и не разговаривайте.", font=fnt(20), fill=INK)
    d.text((card[0] + 36, card[1] + 306), "Когда прозвучит сигнал — измерьте ещё раз.", font=fnt(20), fill=INK)
    btn(d, (card[0] + 36, card[3] - 88, card[2] - 36, card[3] - 28), "Пропустить ожидание", NAVY_SOFT, NAVY, fnt(22, True))

    rr(d, (820, 180, 1160, 420), 24, WHITE)
    text_c(d, (990, 250), "сигнал", fnt(32, True), CORAL)
    d.text((860, 310), "Звук и вибрация:", font=fnt(22), fill=INK)
    d.text((860, 348), "пора мерить второй раз.", font=fnt(22), fill=INK)
    return img


def step_second():
    img, d = canvas()
    d.text((36, 22), "Замер 2 из 2", font=fnt(32, True), fill=NAVY)
    screen = phone(d, (70, 80, 820, 690))
    card = (screen[0] + 18, screen[1] + 16, screen[2] - 18, screen[3] - 16)
    rr(d, card, 18, WHITE)
    d.text((card[0] + 22, card[1] + 12), "Замер 2 из 2", font=fnt(26, True), fill=NAVY)
    d.text((card[0] + 22, card[1] + 50), "Введите второй замер. В дневник сохранится среднее.", font=fnt(16), fill=GRAY)
    rr(d, (card[0] + 22, card[1] + 84, card[2] - 22, card[1] + 138), 12, (232, 245, 233))
    d.text((card[0] + 36, card[1] + 98), "Первый 128/78. Среднее: 125/76", font=fnt(20, True), fill=GREEN)
    field(d, (card[0] + 22, card[1] + 154, card[2] - 22, card[1] + 222), "Систолическое (верхнее)", "122")
    field(d, (card[0] + 22, card[1] + 234, card[2] - 22, card[1] + 302), "Диастолическое (нижнее)", "74")
    field(d, (card[0] + 22, card[1] + 314, card[2] - 22, card[1] + 382), "Пульс", "68")
    btn(d, (card[0] + 22, card[3] - 78, card[2] - 22, card[3] - 22), "Сохранить среднее", GREEN, WHITE, fnt(24, True))

    rr(d, (850, 160, 1160, 520), 22, WHITE)
    d.text((874, 186), "Как считается", font=fnt(22, True), fill=NAVY)
    d.text((874, 240), "128 и 122", font=fnt(22), fill=INK)
    d.text((874, 274), "среднее 125", font=fnt(26, True), fill=GREEN)
    d.text((874, 330), "78 и 74", font=fnt(22), fill=INK)
    d.text((874, 364), "среднее 76", font=fnt(26, True), fill=GREEN)
    d.text((874, 430), "Пульс тоже усредняется,", font=fnt(18), fill=GRAY)
    d.text((874, 458), "если указан в обоих замерах.", font=fnt(18), fill=GRAY)
    return img


def step_saved():
    img, d = canvas()
    d.text((36, 22), "В дневнике — среднее", font=fnt(32, True), fill=NAVY)
    # карточка записи, как в списке
    card = (80, 110, 760, 430)
    rr(d, card, 22, WHITE)
    d.rectangle((80, 110, 96, 430), fill=(85, 139, 47))
    d.text((124, 136), "сегодня, 08:12", font=fnt(20), fill=GRAY)
    d.text((520, 136), "Норма", font=fnt(20, True), fill=(85, 139, 47))
    d.text((124, 180), "125 / 76", font=fnt(64, True), fill=INK)
    d.text((124, 270), "Пульс: 70 уд/мин", font=fnt(24), fill=GRAY)
    d.text((124, 330), "1: 128/78, 2: 122/74", font=fnt(26), fill=NAVY)
    d.text((124, 376), "Это среднее. Оба замера — в заметке.", font=fnt(20), fill=GRAY)

    rr(d, (80, 470, 760, 580), 20, (232, 245, 233))
    text_c(d, (420, 525), "Сохранено среднее 125/76", fnt(28, True), GREEN)

    rr(d, (820, 160, 1140, 520), 22, WHITE)
    d.text((848, 190), "Что увидит врач", font=fnt(22, True), fill=NAVY)
    d.text((848, 250), "В списке и в PDF —", font=fnt(20), fill=INK)
    d.text((848, 286), "125/76, не каждый", font=fnt(20), fill=INK)
    d.text((848, 322), "замер отдельно.", font=fnt(20), fill=INK)
    d.text((848, 390), "Заметка хранит", font=fnt(20), fill=GRAY)
    d.text((848, 426), "оба исходных числа.", font=fnt(20), fill=GRAY)
    return img


def main():
    steps = [
        ("guide_pair_1", step_prepare),
        ("guide_pair_2", step_plus),
        ("guide_pair_3", step_first),
        ("guide_pair_4", step_wait),
        ("guide_pair_5", step_second),
        ("guide_pair_6", step_saved),
    ]
    for name, fn in steps:
        save(fn(), name)
        print(name)


if __name__ == "__main__":
    main()

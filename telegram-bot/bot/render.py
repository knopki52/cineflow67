"""Картинки для бота: схема зала (как на сайте) и карточка «Твой киногод»."""
import io
import os

from PIL import Image, ImageDraw, ImageFont

FONT_PATHS = ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "C:/Windows/Fonts/arial.ttf",
              "/System/Library/Fonts/Supplemental/Arial.ttf"]
BOLD_PATHS = ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "C:/Windows/Fonts/arialbd.ttf",
              "/System/Library/Fonts/Supplemental/Arial Bold.ttf"]


def font(size: int, bold: bool = False):
    for p in (BOLD_PATHS if bold else FONT_PATHS):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


BG = (26, 11, 22)
SURFACE = (38, 16, 31)
TEXT = (243, 239, 250)
MUTED = (168, 149, 166)
BRASS = (227, 182, 92)
SEAT = (75, 33, 66)
SEAT_LINE = (110, 58, 98)
HELD = (110, 99, 128)
GROUP = (79, 184, 176)
SOLD = (52, 26, 47)
COLORS = {"FREE": SEAT, "MINE": BRASS, "HELD": HELD, "GROUP": GROUP, "SOLD": SOLD}


def hall_image(seatmap: dict, focus_row: int | None = None) -> bytes:
    s = seatmap["session"]
    rows, spr = s["rows"], s["seatsPerRow"]
    S, G, M = 40, 8, 70
    w = M * 2 + spr * S + (spr - 1) * G
    top = 150
    h = top + rows * S + (rows - 1) * G + 130
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)

    # экран с лучом
    d.text((M, 22), f"{s['movieTitle']}", font=font(26, True), fill=TEXT)
    d.text((M, 58), f"{s['cinemaName']} · {s['hallName']} · {s['startTime'][11:16]}", font=font(18), fill=MUTED)
    for i in range(30):
        alpha = int(60 * (1 - i / 30))
        d.arc((M - 40, 92 + i, w - M + 40, 92 + 120 + i), 200, 340, fill=(alpha + 30, alpha + 20, alpha + 35), width=2)
    d.arc((M - 40, 92, w - M + 40, 212), 200, 340, fill=TEXT, width=5)
    lbl = "ЭКРАН"
    tw = d.textlength(lbl, font=font(14))
    d.text(((w - tw) / 2, 112), lbl, font=font(14), fill=MUTED)

    cells = {(c["row"], c["seat"]): c for c in seatmap["seats"]}
    num_font, row_font = font(13, True), font(15)
    for r in range(1, rows + 1):
        y = top + (r - 1) * (S + G)
        if focus_row == r:
            d.rounded_rectangle((M - 12, y - 5, w - M + 12, y + S + 5), radius=12, outline=BRASS, width=3)
        for side_x in (M - 42, w - M + 18):
            d.text((side_x + 6, y + 10), str(r), font=row_font, fill=BRASS if focus_row == r else MUTED)
        for c in range(1, spr + 1):
            x = M + (c - 1) * (S + G)
            cell = cells.get((r, c), {"state": "FREE"})
            st = cell["state"]
            fill = COLORS.get(st, SEAT)
            outline = BRASS if st == "MINE" else SEAT_LINE if st == "FREE" else fill
            d.rounded_rectangle((x, y, x + S, y + S - 4), radius=8, fill=fill, outline=outline, width=2)
            d.rounded_rectangle((x + 5, y + S - 6, x + S - 5, y + S), radius=3, fill=fill)
            if st == "SOLD":
                d.line((x + 13, y + 11, x + S - 13, y + S - 15), fill=MUTED, width=2)
                d.line((x + S - 13, y + 11, x + 13, y + S - 15), fill=MUTED, width=2)
            elif st in ("FREE", "MINE") and (focus_row == r or focus_row is None):
                t = str(c)
                tw = d.textlength(t, font=num_font)
                d.text((x + (S - tw) / 2, y + 10), t, font=num_font, fill=(42, 21, 5) if st == "MINE" else MUTED)

    # легенда и статистика
    ly = h - 100
    items = [("Свободно", SEAT), ("Ваше", BRASS), ("Выбирают", HELD), ("Поход", GROUP), ("Продано", SOLD)]
    x = M
    for name, col in items:
        d.rounded_rectangle((x, ly, x + 20, ly + 20), radius=5, fill=col, outline=SEAT_LINE if col == SEAT else col)
        d.text((x + 28, ly + 1), name, font=font(16), fill=MUTED)
        x += 40 + d.textlength(name, font=font(16))
    d.text((M, ly + 40), f"Продано {seatmap['sold']} из {seatmap['total']} · {seatmap['percent']}%   "
                          f"цены {seatmap['priceMin']}–{seatmap['priceMax']} ₽", font=font(18, True), fill=TEXT)
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def wrapped_card(name: str, w: dict) -> bytes:
    W, H = 1080, 1350
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    for y in range(H):  # градиент бархата
        k = y / H
        d.line((0, y, W, y), fill=(int(90 - 64 * k), int(31 - 20 * k), int(68 - 46 * k)))
    d.text((80, 80), f"CineFlow · {w['year']}", font=font(40, True), fill=BRASS)
    d.text((80, 140), f"Киногод: {name}", font=font(34), fill=TEXT)
    d.text((70, 210), str(w["films"]), font=font(260, True), fill=TEXT)
    d.text((86, 500), "фильмов за год · " + f"{w.get('hours', 0)} ч в кино", font=font(38), fill=TEXT)
    d.text((80, 600), w.get("kinotype", ""), font=font(52, True), fill=BRASS)
    d.text((80, 680), w.get("kinotypeText", ""), font=font(28), fill=MUTED)
    lines = [f"Любимый жанр: {w.get('favoriteGenre')}",
             f"Любимое место: ряд {w.get('favoriteRow')}, {w.get('favoriteZone')}",
             f"Ночных сеансов: {w.get('nightSessions')}",
             f"Попкорна: {w.get('popcorn')}",
             f"Чаще всего: {w.get('topCinema')}"]
    for i, t in enumerate(lines):
        d.text((80, 820 + i * 70), t, font=font(36), fill=TEXT)
    d.text((80, H - 90), "cineflow · кино в реальном времени", font=font(26), fill=MUTED)
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


def decode_qr(data: bytes) -> str | None:
    """Распознаёт QR-код билета на фото (режим контролёра)."""
    import cv2
    import numpy as np
    arr = np.frombuffer(data, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        return None
    text, _, _ = cv2.QRCodeDetector().detectAndDecode(img)
    if not text:
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        text, _, _ = cv2.QRCodeDetector().detectAndDecode(cv2.resize(gray, None, fx=2, fy=2))
    return text or None

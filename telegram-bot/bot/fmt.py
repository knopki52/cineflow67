"""Форматирование дат, денег и склонений по-русски."""
from datetime import datetime
from html import escape

MONTHS = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа",
          "сентября", "октября", "ноября", "декабря"]
WEEKDAYS = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]


def dt(iso: str) -> datetime:
    return datetime.fromisoformat(iso.replace("Z", ""))


def when(iso: str) -> str:
    d = dt(iso)
    return f"{d.day} {MONTHS[d.month - 1]}, {d:%H:%M}"


def hm(iso: str) -> str:
    return f"{dt(iso):%H:%M}"


def money(n) -> str:
    return f"{int(round(n or 0)):,}".replace(",", " ") + " ₽"


def plural(n: int, one: str, few: str, many: str) -> str:
    m10, m100 = n % 10, n % 100
    if m10 == 1 and m100 != 11:
        return one
    if 2 <= m10 <= 4 and not 10 <= m100 < 20:
        return few
    return many


def e(s) -> str:
    return escape(str(s if s is not None else ""))


LEVELS = {"BASE": "Базовый", "SILVER": "🥈 Silver", "GOLD": "🥇 Gold"}

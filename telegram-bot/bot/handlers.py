"""Все сценарии бота: афиша → сеанс → выбор мест на картинке зала → оплата → билеты с QR.
Плюс бонусы, ассистент, кинотеатры на карте, «Киногод», совместный поход, контролёр и бар."""
import asyncio
from datetime import date, datetime, timedelta

from aiogram import Bot, F, Router
from aiogram.filters import Command, CommandObject, CommandStart
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import (BufferedInputFile, CallbackQuery, InputMediaPhoto, KeyboardButton, Message,
                           ReplyKeyboardMarkup)
from aiogram.utils.keyboard import InlineKeyboardBuilder

from .api import ApiError, api
from .config import SITE_URL
from .fmt import LEVELS, WEEKDAYS, e, hm, money, plural, when
from .render import decode_qr, hall_image, wrapped_card

router = Router()

# ---------------- память бота (на процесс) ----------------
USERS: dict[int, dict] = {}       # chat_id -> профиль из user-service
CITY: dict[int, dict] = {}        # chat_id -> {"id", "name"}
FOCUS: dict[int, int] = {}        # chat_id -> выбранный ряд на схеме
CART: dict[int, dict] = {}        # chat_id -> черновик заказа


class Promo(StatesGroup):
    code = State()


class ReviewText(StatesGroup):
    text = State()


B_AFISHA, B_TICKETS, B_ASSIST, B_BONUS = "🎬 Афиша", "🎟 Мои билеты", "🤖 Ассистент", "⭐ Бонусы"
B_MAP, B_YEAR, B_CITY = "🗺 Кинотеатры", "🎁 Мой киногод", "🏙 Город"
B_CONTROL, B_BAR = "🛂 Контроль", "🍿 Заказы бара"


def menu(user: dict | None) -> ReplyKeyboardMarkup:
    rows = [[KeyboardButton(text=B_AFISHA), KeyboardButton(text=B_TICKETS)],
            [KeyboardButton(text=B_ASSIST), KeyboardButton(text=B_BONUS)],
            [KeyboardButton(text=B_MAP), KeyboardButton(text=B_YEAR)],
            [KeyboardButton(text=B_CITY)]]
    role = (user or {}).get("role")
    if role in ("CONTROLLER", "ADMIN"):
        rows[-1].append(KeyboardButton(text=B_CONTROL))
    if role in ("BAR", "ADMIN"):
        rows[-1].append(KeyboardButton(text=B_BAR))
    return ReplyKeyboardMarkup(keyboard=rows, resize_keyboard=True, input_field_placeholder="Например: комедия в пятницу вдвоём")


async def me(chat_id: int, name: str = "", username: str | None = None, refresh: bool = False) -> dict:
    if refresh or chat_id not in USERS:
        USERS[chat_id] = await api.post("/api/users/telegram/auth", {"chatId": chat_id, "name": name, "username": username})
    return USERS[chat_id]


def city(chat_id: int) -> dict:
    return CITY.get(chat_id, {"id": 1, "name": "Москва"})


async def fail(target, err: Exception):
    text = str(err) if isinstance(err, ApiError) else "Что-то пошло не так, попробуйте ещё раз"
    if isinstance(target, CallbackQuery):
        await target.answer(text[:190], show_alert=True)
    else:
        await target.answer(text)


# ================= старт и привязка =================
@router.message(CommandStart())
async def start(message: Message, command: CommandObject, state: FSMContext):
    await state.clear()
    u = message.from_user
    payload = command.args or ""
    try:
        if payload.startswith("link_"):
            USERS[message.chat.id] = await api.post("/api/users/telegram/link",
                                                    {"code": payload[5:], "chatId": message.chat.id, "username": u.username})
            user = USERS[message.chat.id]
            await message.answer(f"✅ Telegram привязан к аккаунту <b>{e(user['name'])}</b>.\n"
                                 f"Теперь сюда придут билеты, напоминания и места из листа ожидания.", reply_markup=menu(user))
            return
        user = await me(message.chat.id, u.full_name, u.username)
    except ApiError as err:
        await message.answer(str(err))
        return
    await message.answer(
        f"🎞 <b>CineFlow</b> — сеть кинотеатров прямо в Telegram.\n\n"
        f"Привет, {e(user['name'])}! Здесь можно выбрать места на схеме зала, оплатить, получить билет с QR "
        f"и напоминание за час до сеанса.\n\n"
        f"Просто напишите, что хотите посмотреть: <i>«ужастик сегодня после 21, вдвоём»</i>.\n"
        f"На счёте: <b>{user['points']}</b> баллов.", reply_markup=menu(user))
    if payload.startswith("grp_"):
        await show_group(message, payload[4:])
    elif payload.startswith("s_") and payload[2:].isdigit():
        await send_hall(message, int(payload[2:]))


# ================= город =================
@router.message(F.text == B_CITY)
async def choose_city(message: Message):
    cities = await api.get("/api/cities")
    kb = InlineKeyboardBuilder()
    for c in cities:
        kb.button(text=("✓ " if c["id"] == city(message.chat.id)["id"] else "") + c["name"], callback_data=f"cy:{c['id']}")
    kb.adjust(1)
    await message.answer("Выберите город:", reply_markup=kb.as_markup())


@router.callback_query(F.data.startswith("cy:"))
async def set_city(cb: CallbackQuery):
    cid = int(cb.data[3:])
    c = next(x for x in await api.get("/api/cities") if x["id"] == cid)
    CITY[cb.message.chat.id] = {"id": c["id"], "name": c["name"]}
    await cb.message.edit_text(f"🏙 Город: <b>{e(c['name'])}</b>. Открывайте «Афишу».")
    await cb.answer()


# ================= афиша =================
@router.message(F.text == B_AFISHA)
@router.message(Command("afisha"))
async def afisha(message: Message):
    c = city(message.chat.id)
    try:
        movies = await api.get("/api/movies", cityId=c["id"])
    except ApiError as err:
        return await fail(message, err)
    kb = InlineKeyboardBuilder()
    for m in movies:
        kb.button(text=f"{m['title']} · ★{m['rating']:.1f}" if m["rating"] else m["title"], callback_data=f"mv:{m['id']}")
    kb.adjust(1)
    await message.answer(f"🎬 <b>Сейчас в кино · {e(c['name'])}</b>\nВыберите фильм:", reply_markup=kb.as_markup())


@router.callback_query(F.data.startswith("mv:"))
async def movie(cb: CallbackQuery):
    mid = int(cb.data[3:])
    try:
        m = await api.get(f"/api/movies/{mid}")
    except ApiError as err:
        return await fail(cb, err)
    kb = InlineKeyboardBuilder()
    today = date.today()
    for i in range(7):
        d = today + timedelta(days=i)
        label = "Сегодня" if i == 0 else "Завтра" if i == 1 else f"{WEEKDAYS[d.weekday()]} {d.day}"
        kb.button(text=label, callback_data=f"md:{mid}:{i}")
    trailer = (f"https://youtu.be/{m['trailerYoutubeId']}" if m.get("trailerYoutubeId")
               else f"https://www.youtube.com/results?search_query={m['title'].replace(' ', '+')}+трейлер")
    kb.button(text="▶️ Трейлер", url=trailer)
    kb.adjust(4, 3, 1)
    rating = f"★ {m['rating']:.1f} ({m['reviewsCount']} {plural(m['reviewsCount'], 'отзыв', 'отзыва', 'отзывов')})" if m["rating"] else "Пока без оценок"
    await cb.message.answer(
        f"🎬 <b>{e(m['title'])}</b>\n{e(m['genre'])} · {m['year']} · {m['durationMin']} мин · {e(m['ageRating'])}\n{rating}\n\n"
        f"{e(m['description'])}\n\n<i>Режиссёр: {e(m['director'])}</i>\n\nВыберите день:", reply_markup=kb.as_markup())
    await cb.answer()


@router.callback_query(F.data.startswith("md:"))
async def movie_day(cb: CallbackQuery):
    _, mid, off = cb.data.split(":")
    d = date.today() + timedelta(days=int(off))
    try:
        sessions = await api.get(f"/api/movies/{mid}/sessions", cityId=city(cb.message.chat.id)["id"], date=d.isoformat())
    except ApiError as err:
        return await fail(cb, err)
    if not sessions:
        return await cb.answer("В этот день сеансов нет — выберите другой", show_alert=True)
    kb = InlineKeyboardBuilder()
    for s in sessions[:40]:
        kb.button(text=f"{hm(s['startTime'])} · {s['cinemaName'].replace('CineFlow ', '')} · {s['hallType']}",
                  callback_data=f"ss:{s['id']}")
    kb.adjust(2)
    await cb.message.answer(f"🕐 <b>{e(sessions[0]['movieTitle'])}</b>, {d.day}.{d.month:02d}. Выберите сеанс:",
                            reply_markup=kb.as_markup())
    await cb.answer()


# ================= схема зала =================
def hall_kb(seatmap: dict, focus: int | None):
    s = seatmap["session"]
    sid = s["id"]
    kb = InlineKeyboardBuilder()
    cells = {(c["row"], c["seat"]): c for c in seatmap["seats"]}
    mine = [c for c in seatmap["seats"] if c["state"] == "MINE"]
    widths = []
    if focus:
        n = 0
        for c in range(1, s["seatsPerRow"] + 1):
            cell = cells[(focus, c)]
            st = cell["state"]
            text = {"FREE": f"{c}", "MINE": f"✅{c}", "SOLD": "✖", "HELD": "·", "GROUP": "·"}[st]
            kb.button(text=text, callback_data=f"st:{sid}:{focus}:{c}" if st in ("FREE", "MINE") else "noop")
            n += 1
        per = 7
        widths += [per] * (n // per) + ([n % per] if n % per else [])
        kb.button(text="⬅️ К рядам", callback_data=f"rw:{sid}:0")
        widths.append(1)
    else:
        for r in range(1, s["rows"] + 1):
            free = sum(1 for c in range(1, s["seatsPerRow"] + 1) if cells[(r, c)]["state"] in ("FREE", "MINE"))
            kb.button(text=f"Ряд {r}" + ("" if free else " ✖"), callback_data=f"rw:{sid}:{r}" if free else "noop")
        widths += [4] * (s["rows"] // 4) + ([s["rows"] % 4] if s["rows"] % 4 else [])
    if mine:
        total = sum(c["price"] for c in mine)
        kb.button(text=f"💳 Далее: {len(mine)} {plural(len(mine), 'место', 'места', 'мест')} · {money(total)}", callback_data=f"go:{sid}")
        widths.append(1)
        if len(mine) >= 2:
            kb.button(text="👥 Позвать друзей", callback_data=f"gr:{sid}")
            widths.append(1)
    elif seatmap["full"]:
        kb.button(text="⏳ Встать в лист ожидания", callback_data=f"wl:{sid}")
        widths.append(1)
    kb.button(text="🔄 Обновить", callback_data=f"rw:{sid}:{focus or 0}")
    widths.append(1)
    kb.adjust(*widths)
    return kb.as_markup()


def hall_caption(seatmap: dict, focus: int | None) -> str:
    s = seatmap["session"]
    mine = [c for c in seatmap["seats"] if c["state"] == "MINE"]
    lines = [f"🎬 <b>{e(s['movieTitle'])}</b>", f"📍 {e(s['cinemaName'])}, {e(s['hallName'])} ({e(s['hallType'])})",
             f"🕐 {when(s['startTime'])}",
             f"🎟 Продано {seatmap['sold']} из {seatmap['total']} · {seatmap['percent']}%"]
    if seatmap.get("factors"):
        lines.append("💡 " + ", ".join(seatmap["factors"]))
    if mine:
        lines.append("\n✅ Ваши места: " + ", ".join(f"ряд {c['row']} м.{c['seat']}" for c in mine)
                     + " — держим 5 минут")
    elif seatmap["full"]:
        lines.append("\n😔 Зал полностью продан. Встаньте в лист ожидания — освободившееся место достанется первому.")
    lines.append("\n" + (f"Ряд {focus}: нажмите на номер места." if focus else "Выберите ряд — центр зала дороже, первые ряды дешевле."))
    return "\n".join(lines)


async def load_map(chat_id: int, sid: int) -> dict:
    user = await me(chat_id)
    return await api.get(f"/api/booking/sessions/{sid}/map", userId=user["id"])


async def send_hall(message: Message, sid: int):
    try:
        seatmap = await load_map(message.chat.id, sid)
    except ApiError as err:
        return await fail(message, err)
    FOCUS.pop(message.chat.id, None)
    await message.answer_photo(BufferedInputFile(hall_image(seatmap), "hall.png"),
                               caption=hall_caption(seatmap, None), reply_markup=hall_kb(seatmap, None))


async def redraw(cb: CallbackQuery, sid: int, focus: int | None):
    seatmap = await load_map(cb.message.chat.id, sid)
    media = InputMediaPhoto(media=BufferedInputFile(hall_image(seatmap, focus), "hall.png"), caption=hall_caption(seatmap, focus))
    try:
        await cb.message.edit_media(media=media, reply_markup=hall_kb(seatmap, focus))
    except Exception:
        await cb.message.answer_photo(media.media, caption=media.caption, reply_markup=hall_kb(seatmap, focus))


@router.callback_query(F.data == "noop")
async def noop(cb: CallbackQuery):
    await cb.answer("Это место недоступно")


@router.callback_query(F.data.startswith("ss:"))
async def open_session(cb: CallbackQuery):
    await cb.answer()
    await send_hall(cb.message, int(cb.data[3:]))


@router.callback_query(F.data.startswith("rw:"))
async def choose_row(cb: CallbackQuery):
    _, sid, row = cb.data.split(":")
    focus = int(row) or None
    FOCUS[cb.message.chat.id] = focus
    try:
        await redraw(cb, int(sid), focus)
        await cb.answer()
    except ApiError as err:
        await fail(cb, err)


@router.callback_query(F.data.startswith("st:"))
async def toggle_seat(cb: CallbackQuery):
    _, sid, row, seat = cb.data.split(":")
    sid, row, seat = int(sid), int(row), int(seat)
    user = await me(cb.message.chat.id)
    try:
        seatmap = await api.get(f"/api/booking/sessions/{sid}/map", userId=user["id"])
        cell = next(c for c in seatmap["seats"] if c["row"] == row and c["seat"] == seat)
        body = {"sessionId": sid, "userId": user["id"], "seats": [{"row": row, "seat": seat}]}
        if cell["state"] == "MINE":
            await api.post("/api/booking/release", body)
            await cb.answer(f"Ряд {row}, место {seat} отпущено")
        else:
            await api.post("/api/booking/hold", body)
            await cb.answer(f"✅ Ряд {row}, место {seat} · {money(cell['price'])} — держим 5 минут")
        await redraw(cb, sid, row)
    except ApiError as err:
        await fail(cb, err)
        await redraw(cb, sid, row)


@router.callback_query(F.data.startswith("wl:"))
async def waitlist(cb: CallbackQuery):
    sid = int(cb.data[3:])
    user = await me(cb.message.chat.id)
    try:
        p = await api.post("/api/waitlist", {"sessionId": sid, "userId": user["id"], "userName": user["name"]})
        await cb.message.answer(f"⏳ Вы в листе ожидания: <b>{p['position'] or 1}-й</b> в очереди.\n"
                                f"Как только место освободится, мы удержим его за вами на 10 минут и пришлём сюда кнопку «Забрать место».")
        await cb.answer()
    except ApiError as err:
        await fail(cb, err)


@router.callback_query(F.data.startswith("gr:"))
async def make_group(cb: CallbackQuery):
    sid = int(cb.data[3:])
    user = await me(cb.message.chat.id)
    try:
        seatmap = await api.get(f"/api/booking/sessions/{sid}/map", userId=user["id"])
        mine = [{"row": c["row"], "seat": c["seat"]} for c in seatmap["seats"] if c["state"] == "MINE"]
        g = await api.post("/api/groups", {"organizerId": user["id"], "organizerName": user["name"], "sessionId": sid, "seats": mine})
        bot_name = (await cb.bot.get_me()).username
        link = f"https://t.me/{bot_name}?start=grp_{g['id']}"
        await cb.message.answer(
            f"👥 <b>Совместный поход создан!</b>\n«{e(g['session']['movieTitle'])}», {when(g['session']['startTime'])}\n"
            f"Места держатся 10 минут. Перешлите друзьям ссылку — каждый выберет кресло и оплатит сам:\n\n{link}\n\n"
            f"Или на сайте: {SITE_URL}/#/group/{g['id']}")
        await cb.answer()
    except ApiError as err:
        await fail(cb, err)


async def show_group(message: Message, gid: str):
    try:
        g = await api.get(f"/api/groups/{gid}")
    except ApiError as err:
        return await fail(message, err)
    kb = InlineKeyboardBuilder()
    for s in g["seats"]:
        if s["status"] == "WAITING":
            kb.button(text=f"Ряд {s['row']}, место {s['seat']}", callback_data=f"gp:{g['id']}:{s['row']}:{s['seat']}")
    kb.adjust(2)
    paid = "\n".join(f"✅ ряд {s['row']} м.{s['seat']} — {e(s['paidBy'])}" for s in g["seats"] if s["status"] == "PAID")
    await message.answer(
        f"👥 <b>{e(g['organizerName'])} зовёт в кино!</b>\n🎬 «{e(g['session']['movieTitle'])}»\n"
        f"📍 {e(g['session']['cinemaName'])}, {e(g['session']['hallName'])} · {when(g['session']['startTime'])}\n"
        f"Оплатили {g['paid']} из {g['total']}\n{paid}\n\nВыберите своё место:", reply_markup=kb.as_markup())


@router.callback_query(F.data.startswith("gp:"))
async def group_pick(cb: CallbackQuery):
    _, gid, row, seat = cb.data.split(":")
    g = await api.get(f"/api/groups/{gid}")
    CART[cb.message.chat.id] = {"sid": g["session"]["id"], "seats": [{"row": int(row), "seat": int(seat)}],
                                "promo": None, "points": 0, "bar": {}, "group": gid}
    await cb.answer()
    await show_quote(cb.message, new=True)


# ================= оформление =================
@router.callback_query(F.data.startswith("go:"))
async def to_checkout(cb: CallbackQuery):
    sid = int(cb.data[3:])
    seatmap = await load_map(cb.message.chat.id, sid)
    mine = [{"row": c["row"], "seat": c["seat"]} for c in seatmap["seats"] if c["state"] == "MINE"]
    if not mine:
        return await cb.answer("Время удержания вышло — выберите места заново", show_alert=True)
    CART[cb.message.chat.id] = {"sid": sid, "seats": mine, "promo": None, "points": 0, "bar": {}, "group": None}
    await cb.answer()
    await show_quote(cb.message, new=True)


def cart_body(chat_id: int, user: dict) -> dict:
    c = CART[chat_id]
    return {"userId": user["id"], "sessionId": c["sid"], "seats": c["seats"], "promoCode": c["promo"],
            "usePoints": c["points"], "barItems": [{"itemId": int(k), "qty": v} for k, v in c["bar"].items() if v > 0],
            "groupId": c["group"]}


async def show_quote(message: Message, new: bool = False):
    chat_id = message.chat.id
    user = await me(chat_id)
    try:
        q = await api.post("/api/booking/quote", cart_body(chat_id, user))
    except ApiError as err:
        return await message.answer(str(err))
    s = q["session"]
    lines = [f"🧾 <b>Ваш заказ</b>", f"🎬 {e(s['movieTitle'])}", f"📍 {e(s['cinemaName'])}, {e(s['hallName'])} · {when(s['startTime'])}", ""]
    for it in q["tickets"]["items"]:
        f = f" <i>({e(', '.join(it['factors']))})</i>" if it["factors"] else ""
        lines.append(f"🎟 Ряд {it['row']}, место {it['seat']} — <b>{money(it['price'])}</b>{f}")
    if q["tickets"]["discount"]:
        lines.append(f"🏷 Промокод {e(q['tickets']['promoCode'])}: −{money(q['tickets']['discount'])}")
    if q.get("bar"):
        for l in q["bar"]["lines"]:
            lines.append(f"{l['emoji']} {e(l['name'])} ×{l['qty']} — {money(l['sum'])}")
    if q["pointsToUse"]:
        lines.append(f"⭐ Баллами: −{money(q['pointsToUse'])}")
    lines.append(f"\n💳 <b>К оплате: {money(q['total'])}</b>")
    lines.append(f"Начислим {q['earnPoints']} баллов · уровень {LEVELS.get(q['level'], q['level'])}")
    if q.get("holdExpiresAt"):
        lines.append(f"⏳ Места держатся до {datetime.fromtimestamp(q['holdExpiresAt'] / 1000):%H:%M}")
    for m in q["messages"]:
        lines.append(f"ℹ️ {e(m)}")
    kb = InlineKeyboardBuilder()
    kb.button(text="🏷 Промокод", callback_data="q:promo")
    if q["maxPoints"] > 0:
        kb.button(text=f"⭐ {'Не списывать' if q['pointsToUse'] else 'Списать ' + str(q['maxPoints'])} баллов", callback_data="q:points")
    kb.button(text="🍿 Бар к креслу", callback_data="q:bar")
    kb.button(text=f"💳 Оплатить {money(q['total'])}", callback_data="q:pay")
    kb.button(text="✖ Отменить", callback_data="q:cancel")
    kb.adjust(2, 1, 1, 1) if q["maxPoints"] > 0 else kb.adjust(2, 1, 1)
    CART[chat_id]["maxPoints"] = q["maxPoints"]
    text = "\n".join(lines)
    if new:
        await message.answer(text, reply_markup=kb.as_markup())
    else:
        try:
            await message.edit_text(text, reply_markup=kb.as_markup())
        except Exception:
            await message.answer(text, reply_markup=kb.as_markup())


@router.callback_query(F.data == "q:promo")
async def ask_promo(cb: CallbackQuery, state: FSMContext):
    await state.set_state(Promo.code)
    await cb.message.answer("🏷 Отправьте промокод (например, AUTUMN, STUDENT, TUESDAY30):")
    await cb.answer()


@router.message(Promo.code)
async def got_promo(message: Message, state: FSMContext):
    await state.clear()
    if message.chat.id not in CART:
        return await message.answer("Заказ не найден — выберите места заново")
    CART[message.chat.id]["promo"] = (message.text or "").strip().upper()
    await show_quote(message, new=True)


@router.callback_query(F.data == "q:points")
async def toggle_points(cb: CallbackQuery):
    c = CART.get(cb.message.chat.id)
    if not c:
        return await cb.answer("Заказ устарел", show_alert=True)
    c["points"] = 0 if c["points"] else c.get("maxPoints", 0)
    await cb.answer()
    await show_quote(cb.message)


@router.callback_query(F.data == "q:bar")
async def bar_menu(cb: CallbackQuery):
    c = CART.get(cb.message.chat.id)
    if not c:
        return await cb.answer("Заказ устарел", show_alert=True)
    try:
        items = await api.get("/api/bar/menu")
    except ApiError:
        return await cb.answer("🍿 Бар сейчас не принимает заказы — можно купить билеты без него", show_alert=True)
    kb = InlineKeyboardBuilder()
    for it in items:
        q = c["bar"].get(str(it["id"]), 0)
        kb.button(text=f"{it['emoji']} {it['name']} · {it['price']}₽" + (f" ×{q}" if q else ""), callback_data=f"bi:{it['id']}:1")
        kb.button(text="−", callback_data=f"bi:{it['id']}:-1")
    kb.button(text="✅ Готово", callback_data="q:back")
    kb.adjust(*([2] * len(items)), 1)
    await cb.message.edit_text("🍿 <b>Бар к вашему креслу</b>\nЗаказ принесут прямо к месту. Нажмите, чтобы добавить:", reply_markup=kb.as_markup())
    await cb.answer()


@router.callback_query(F.data.startswith("bi:"))
async def bar_item(cb: CallbackQuery):
    _, iid, d = cb.data.split(":")
    c = CART.get(cb.message.chat.id)
    if not c:
        return await cb.answer("Заказ устарел", show_alert=True)
    c["bar"][iid] = max(0, c["bar"].get(iid, 0) + int(d))
    await bar_menu(cb)


@router.callback_query(F.data == "q:back")
async def quote_back(cb: CallbackQuery):
    await cb.answer()
    await show_quote(cb.message)


@router.callback_query(F.data == "q:cancel")
async def cancel(cb: CallbackQuery):
    c = CART.pop(cb.message.chat.id, None)
    user = await me(cb.message.chat.id)
    if c and not c["group"]:
        try:
            await api.post("/api/booking/release", {"sessionId": c["sid"], "userId": user["id"], "seats": []})
        except ApiError:
            pass
    await cb.message.edit_text("Заказ отменён, места свободны.")
    await cb.answer()


@router.callback_query(F.data == "q:pay")
async def pay(cb: CallbackQuery):
    chat_id = cb.message.chat.id
    c = CART.get(chat_id)
    if not c:
        return await cb.answer("Заказ устарел", show_alert=True)
    user = await me(chat_id)
    await cb.answer()
    msg = await cb.message.edit_text("🎞 Гасим свет в зале…")
    await asyncio.sleep(0.7)
    await msg.edit_text("💳 Проводим оплату…")
    try:
        r = await api.post("/api/booking/checkout", cart_body(chat_id, user))
    except ApiError as err:
        kb = InlineKeyboardBuilder()
        kb.button(text="🔁 Повторить оплату", callback_data="q:back")
        return await msg.edit_text(f"⚠️ {e(err)}", reply_markup=kb.as_markup())
    CART.pop(chat_id, None)
    extra = "\n".join(f"ℹ️ {e(m)}" for m in r["messages"])
    await msg.edit_text(
        f"✅ <b>Оплачено {money(r['paidAmount'])}!</b>\n🎟 {len(r['tickets'])} {plural(len(r['tickets']), 'билет', 'билета', 'билетов')} "
        f"с QR-кодом уже летят к вам ✈️\n" + (f"🍿 Бар принесёт к креслу: {e(r['barItems'])}\n" if r.get("barItems") else "")
        + (f"⭐ Списано баллов: {r['pointsUsed']}\n" if r["pointsUsed"] else "") + extra)
    await me(chat_id, refresh=True)


# ================= мои билеты =================
@router.message(F.text == B_TICKETS)
@router.message(Command("tickets"))
async def my_tickets(message: Message):
    user = await me(message.chat.id)
    try:
        tickets = await api.get("/api/tickets", userId=user["id"])
    except ApiError as err:
        return await fail(message, err)
    active = [t for t in tickets if t["status"] == "PAID"]
    used = [t for t in tickets if t["status"] == "USED"][:5]
    if not active and not used:
        return await message.answer("Билетов пока нет. Откройте «🎬 Афишу» или напишите, что хотите посмотреть.")
    kb = InlineKeyboardBuilder()
    for t in active:
        kb.button(text=f"🎟 {hm(t['startTime'])} {t['startTime'][8:10]}.{t['startTime'][5:7]} · {t['movieTitle'][:22]} · р{t['row']} м{t['seat']}",
                  callback_data=f"tk:{t['id']}")
    for t in used:
        kb.button(text=f"⭐ Оценить «{t['movieTitle'][:24]}»", callback_data=f"rv:{t['movieId']}")
    kb.adjust(1)
    await message.answer(f"🎟 <b>Ваши билеты</b> — активных: {len(active)}", reply_markup=kb.as_markup())


async def send_ticket(bot: Bot, chat_id: int, t: dict, header: str = ""):
    qr = await api.get(f"/api/tickets/{t['id']}/qr.png", size=480)
    kb = InlineKeyboardBuilder()
    if t["status"] == "PAID" and t.get("refundPercent"):
        kb.button(text=f"↩️ Вернуть {t['refundPercent']}% · {money(t['refundEstimate'])}", callback_data=f"rf:{t['id']}")
    caption = (f"{header}🎬 <b>{e(t['movieTitle'])}</b>\n📍 {e(t['cinemaName'])}, {e(t['hallName'])}\n"
               f"🕐 {when(t['startTime'])}\n💺 <b>Ряд {t['row']}, место {t['seat']}</b> · {money(t['price'])}\n"
               f"<code>{t['id'][:8]}</code> — покажите QR контролёру")
    await bot.send_photo(chat_id, BufferedInputFile(qr, "ticket.png"), caption=caption,
                         reply_markup=kb.as_markup() if t["status"] == "PAID" and t.get("refundPercent") else None)


@router.callback_query(F.data.startswith("tk:"))
async def ticket(cb: CallbackQuery):
    try:
        t = await api.get(f"/api/tickets/{cb.data[3:]}")
        await send_ticket(cb.bot, cb.message.chat.id, t)
        await cb.answer()
    except ApiError as err:
        await fail(cb, err)


@router.callback_query(F.data.startswith("rf:"))
async def refund_ask(cb: CallbackQuery):
    t = await api.get(f"/api/tickets/{cb.data[3:]}")
    if not t.get("refundPercent"):
        return await cb.answer("До сеанса меньше 2 часов — вернуть билет нельзя", show_alert=True)
    kb = InlineKeyboardBuilder()
    kb.button(text=f"Да, вернуть {money(t['refundEstimate'])}", callback_data=f"ry:{t['id']}")
    await cb.message.answer(f"↩️ Вернуть билет на «{e(t['movieTitle'])}»? Вернём {t['refundPercent']}% — {money(t['refundEstimate'])}.",
                            reply_markup=kb.as_markup())
    await cb.answer()


@router.callback_query(F.data.startswith("ry:"))
async def refund(cb: CallbackQuery):
    user = await me(cb.message.chat.id)
    try:
        t = await api.post(f"/api/tickets/{cb.data[3:]}/refund", {"userId": user["id"]})
        await cb.message.edit_text(f"✅ Билет возвращён. {money(t['refundAmount'])} вернутся на карту.")
        await cb.answer()
    except ApiError as err:
        await fail(cb, err)


# ================= отзыв =================
@router.callback_query(F.data.startswith("rv:"))
async def review_stars(cb: CallbackQuery):
    mid = cb.data[3:]
    kb = InlineKeyboardBuilder()
    for n in range(1, 6):
        kb.button(text="★" * n, callback_data=f"rs:{mid}:{n}")
    kb.adjust(5)
    await cb.message.answer("Как вам фильм?", reply_markup=kb.as_markup())
    await cb.answer()


@router.callback_query(F.data.startswith("rs:"))
async def review_rate(cb: CallbackQuery, state: FSMContext):
    _, mid, n = cb.data.split(":")
    await state.set_state(ReviewText.text)
    await state.update_data(mid=mid, rating=int(n))
    await cb.message.edit_text(f"{'★' * int(n)} — спасибо! Напишите пару слов о фильме или отправьте «-», чтобы пропустить.")
    await cb.answer()


@router.message(ReviewText.text)
async def review_text(message: Message, state: FSMContext):
    data = await state.get_data()
    await state.clear()
    user = await me(message.chat.id)
    text = "" if (message.text or "").strip() == "-" else message.text
    try:
        await api.post(f"/api/movies/{data['mid']}/reviews", {"userId": user["id"], "userName": user["name"], "rating": data["rating"], "text": text})
        await message.answer("⭐ Отзыв опубликован на сайте. Спасибо!")
    except ApiError as err:
        await message.answer(str(err))


# ================= бонусы =================
@router.message(F.text == B_BONUS)
async def bonuses(message: Message):
    u = await me(message.chat.id, refresh=True)
    bar = "▰" * round(u["progress"] * 10) + "▱" * (10 - round(u["progress"] * 10))
    nxt = (f"\nДо {LEVELS[u['nextLevel']]}: {bar} осталось {money(u['toNextLevel'])}" if u.get("nextLevel")
           else "\nМаксимальный уровень — попкорн в каждом заказе бесплатно 🍿")
    await message.answer(f"⭐ <b>{u['points']} баллов</b>\nУровень: {LEVELS.get(u['level'])} · {u['percent']}% с каждой покупки"
                         f"{nxt}\n\n1 балл = 1 ₽, можно оплатить до 50% стоимости билетов.\n"
                         f"Silver — 7%, Gold — 10% и попкорн в подарок.")


# ================= кинотеатры =================
@router.message(F.text == B_MAP)
async def cinemas(message: Message):
    c = city(message.chat.id)
    try:
        list_ = await api.get("/api/cinemas", cityId=c["id"])
        now = datetime.now()
        sessions = await api.get("/api/sessions", cityId=c["id"], **{"from": now.strftime("%Y-%m-%dT%H:%M:00"),
                                                                    "to": (now + timedelta(hours=6)).strftime("%Y-%m-%dT%H:%M:00")})
    except ApiError as err:
        return await fail(message, err)
    for cin in list_:
        await message.answer_venue(latitude=cin["lat"], longitude=cin["lng"], title=cin["name"], address=cin["address"])
        nxt = [s for s in sessions if s["cinemaId"] == cin["id"]][:6]
        if nxt:
            kb = InlineKeyboardBuilder()
            for s in nxt:
                kb.button(text=f"{hm(s['startTime'])} {s['movieTitle'][:18]}", callback_data=f"ss:{s['id']}")
            kb.adjust(2)
            await message.answer(f"Ближайшие сеансы в {e(cin['name'])}:", reply_markup=kb.as_markup())


# ================= киногод =================
@router.message(F.text == B_YEAR)
async def wrapped(message: Message):
    user = await me(message.chat.id)
    try:
        w = await api.get(f"/api/wrapped/{user['id']}")
    except ApiError as err:
        return await fail(message, err)
    if not w.get("films"):
        return await message.answer("🎁 Киногод пока пуст — сходите в кино, и здесь появится ваша статистика.")
    slides = [
        f"🎁 <b>{e(user['name'])}, это ваш киногод {w['year']}</b>",
        f"🎬 Вы посмотрели <b>{w['films']}</b> {plural(w['films'], 'фильм', 'фильма', 'фильмов')} — это {w['hours']} ч в темноте зала",
        f"❤️ Любимый жанр — <b>{e(w['favoriteGenre'])}</b> ({w['favoriteGenreShare']}% сеансов)",
        f"💺 Ваше место — <b>ряд {w['favoriteRow']}, {e(w['favoriteZone'])}</b>",
        f"🌙 Ночных сеансов: <b>{w['nightSessions']}</b> · 🍿 попкорна: <b>{w['popcorn']}</b>",
        f"{w['kinotypeEmoji']} Ваш кинотип: <b>{e(w['kinotype'])}</b>\n<i>{e(w['kinotypeText'])}</i>",
    ]
    msg = await message.answer(slides[0])
    acc = slides[0]
    for s in slides[1:]:
        await asyncio.sleep(1.6)
        acc += "\n\n" + s
        await msg.edit_text(acc)
    await message.answer_photo(BufferedInputFile(wrapped_card(user["name"], w), "kinogod.png"),
                               caption="Карточка для сторис — поделитесь с друзьями 🎞")


# ================= контролёр =================
@router.message(F.text == B_CONTROL)
async def control_help(message: Message):
    await message.answer("🛂 <b>Режим контролёра</b>\nПришлите фото QR-кода билета — я проверю его. "
                         "Или отправьте код командой: <code>/scan 1a2b3c4d-…</code>")


async def check_ticket(message: Message, code: str):
    try:
        r = await api.post("/api/control/scan", {"code": code})
    except ApiError as err:
        return await fail(message, err)
    head = {"OK": "✅ <b>ПРОХОДИТЕ</b>", "ALREADY_USED": "⛔ <b>БИЛЕТ УЖЕ ИСПОЛЬЗОВАН</b>", "WRONG_SESSION": "⛔ <b>НЕ ТОТ СЕАНС</b>",
            "REFUNDED": "⛔ <b>БИЛЕТ ВОЗВРАЩЁН</b>", "NOT_FOUND": "⛔ <b>БИЛЕТ НЕ НАЙДЕН</b>", "EXPIRED": "⛔ <b>СЕАНС ЗАКОНЧИЛСЯ</b>"}
    t = r.get("ticket")
    extra = f"\n🎬 {e(t['movieTitle'])} · {when(t['startTime'])}\n👤 {e(t.get('buyerName') or '')}" if t else ""
    await message.answer(f"{head.get(r['result'], r['result'])}\n{e(r['message'])}{extra}")


@router.message(Command("scan"))
async def scan_cmd(message: Message, command: CommandObject):
    user = await me(message.chat.id)
    if user["role"] not in ("CONTROLLER", "ADMIN"):
        return await message.answer("Команда доступна контролёрам")
    if not command.args:
        return await message.answer("Укажите код: /scan код-билета")
    await check_ticket(message, command.args.strip())


@router.message(F.photo)
async def scan_photo(message: Message, bot: Bot):
    user = await me(message.chat.id)
    if user["role"] not in ("CONTROLLER", "ADMIN"):
        return await message.answer("Фото QR-кодов проверяют контролёры. Чтобы стать контролёром для демо, "
                                    "войдите на сайте как control@cineflow.ru и привяжите этот Telegram.")
    buf = await bot.download(message.photo[-1])
    code = decode_qr(buf.read())
    if not code:
        return await message.answer("Не вижу QR-код. Сфотографируйте ближе и ровнее.")
    await check_ticket(message, code)


# ================= бар =================
@router.message(F.text == B_BAR)
async def bar_orders(message: Message):
    try:
        orders = await api.get("/api/bar/orders")
    except ApiError as err:
        return await fail(message, err)
    if not orders:
        return await message.answer("🍿 Активных заказов нет")
    nxt = {"NEW": ("COOKING", "👨‍🍳 Готовим"), "COOKING": ("DELIVERING", "🏃 Несём"), "DELIVERING": ("DELIVERED", "✅ Доставлено")}
    for o in orders[:15]:
        kb = InlineKeyboardBuilder()
        st, label = nxt[o["status"]]
        kb.button(text=label, callback_data=f"bo:{o['id']}:{st}")
        await message.answer(f"#{o['id']} · <b>{e(o['hallName'])}, {e(o['seatLabel'])}</b>\n{e(o['itemsText'])}\n"
                             f"«{e(o['movieTitle'])}» в {hm(o['startTime'])} · статус {o['status']}", reply_markup=kb.as_markup())


@router.callback_query(F.data.startswith("bo:"))
async def bar_status(cb: CallbackQuery):
    _, oid, st = cb.data.split(":")
    try:
        o = await api.patch(f"/api/bar/orders/{oid}/status", {"status": st})
        await cb.message.edit_text(f"#{o['id']} · {e(o['hallName'])}, {e(o['seatLabel'])}\n{e(o['itemsText'])}\nСтатус: <b>{o['status']}</b>")
        await cb.answer("Зритель получил уведомление")
    except ApiError as err:
        await fail(cb, err)


# ================= лист ожидания: забрать место =================
@router.callback_query(F.data.startswith("wt:"))
async def waitlist_take(cb: CallbackQuery):
    _, sid, row, seat = cb.data.split(":")
    CART[cb.message.chat.id] = {"sid": int(sid), "seats": [{"row": int(row), "seat": int(seat)}],
                                "promo": None, "points": 0, "bar": {}, "group": None}
    await cb.answer()
    await show_quote(cb.message, new=True)


# ================= ассистент =================
@router.message(F.text == B_ASSIST)
async def assistant_help(message: Message):
    await message.answer("🤖 Напишите, как другу: жанр, день, время, сколько вас и бюджет.\n\nНапример:\n"
                         "• <i>хочу комедию в пятницу вечером, недорого, вдвоём</i>\n• <i>ужастик сегодня после 21</i>\n"
                         "• <i>фантастика завтра в IMAX на троих</i>")


@router.callback_query(F.data.startswith("as:"))
async def assistant_take(cb: CallbackQuery):
    _, sid, row, first, n = cb.data.split(":")
    user = await me(cb.message.chat.id)
    seats = [{"row": int(row), "seat": int(first) + i} for i in range(int(n))]
    try:
        await api.post("/api/booking/hold", {"sessionId": int(sid), "userId": user["id"], "seats": seats})
    except ApiError as err:
        return await fail(cb, err)
    CART[cb.message.chat.id] = {"sid": int(sid), "seats": seats, "promo": None, "points": 0, "bar": {}, "group": None}
    await cb.answer("Места удержаны на 5 минут")
    await show_quote(cb.message, new=True)


@router.message(F.text & ~F.text.startswith("/"))
async def assistant(message: Message, state: FSMContext):
    if await state.get_state():
        return
    user = await me(message.chat.id, message.from_user.full_name, message.from_user.username)
    await message.bot.send_chat_action(message.chat.id, "typing")
    try:
        r = await api.post("/api/assistant", {"text": message.text, "cityId": city(message.chat.id)["id"], "userId": user["id"]})
    except ApiError as err:
        return await fail(message, err)
    chips = " · ".join(r["understood"]["chips"])
    kb = InlineKeyboardBuilder()
    lines = [f"🤖 Понял: <i>{e(chips)}</i>", "", e(r["reply"])]
    for i, sg in enumerate(r["suggestions"]):
        s = sg["session"]
        lines.append(f"\n{i + 1}. <b>{e(s['movieTitle'])}</b> — {when(s['startTime'])}\n   {e(s['cinemaName'])}, {e(s['hallName'])} · {e(sg['why'])}")
        first = sg["seats"][0]
        kb.button(text=f"Беру №{i + 1} · {money(sg['total'])}",
                  callback_data=f"as:{s['id']}:{first['row']}:{first['seat']}:{len(sg['seats'])}")
    kb.adjust(1)
    await message.answer("\n".join(lines), reply_markup=kb.as_markup() if r["suggestions"] else menu(user))

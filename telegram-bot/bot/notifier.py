"""Слушает Kafka-топик «telegram» (его наполняет notification-service) и доставляет сообщения в чаты."""
import asyncio
import json
import logging

from aiogram import Bot
from aiogram.utils.keyboard import InlineKeyboardBuilder
from aiokafka import AIOKafkaConsumer

from .api import ApiError, api
from .config import KAFKA
from .fmt import LEVELS, e, money, plural, when
from .handlers import send_ticket

log = logging.getLogger("notifier")


async def deliver(bot: Bot, ev: dict):
    chat = ev["chatId"]
    t = ev.get("type")
    if t == "ORDER_PAID":
        n = len(ev.get("tickets", []))
        await bot.send_message(chat, f"🎉 <b>Билеты оплачены!</b>\n🎬 {e(ev['movieTitle'])}\n📍 {e(ev['cinemaName'])}, {e(ev['hallName'])}\n"
                                     f"🕐 {when(ev['startTime'])}\n💳 {money(ev['paidAmount'])} · {n} {plural(n, 'билет', 'билета', 'билетов')}"
                               + (f"\n🍿 К креслу принесут: {e(ev['barItems'])}" if ev.get("barItems") else ""))
        for tk in ev.get("tickets", []):
            try:
                full = await api.get(f"/api/tickets/{tk['id']}")
                await send_ticket(bot, chat, full)
            except ApiError as err:
                log.warning("Билет %s не отправлен: %s", tk["id"], err)
    elif t == "REMINDER":
        kb = InlineKeyboardBuilder()
        kb.button(text="🎟 Показать билет", callback_data=f"tk:{ev['ticketId']}")
        await bot.send_message(chat, f"⏰ <b>Через час — «{e(ev['movieTitle'])}»</b>\n📍 {e(ev['cinemaName'])}, {e(ev['hallName'])}\n"
                                     f"💺 Ряд {ev['row']}, место {ev['seat']} · начало в {ev['startTime'][11:16]}\nНе забудьте попкорн 🍿",
                               reply_markup=kb.as_markup())
    elif t == "WAITLIST_OFFER":
        kb = InlineKeyboardBuilder()
        kb.button(text="🎟 Забрать место", callback_data=f"wt:{ev['sessionId']}:{ev['row']}:{ev['seat']}")
        await bot.send_message(chat, f"🎉 <b>Освободилось место!</b>\n🎬 {e(ev['movieTitle'])} · {when(ev['startTime'])}\n"
                                     f"📍 {e(ev['cinemaName'])}, {e(ev['hallName'])}\n💺 Ряд {ev['row']}, место {ev['seat']}\n"
                                     f"Держим за вами {ev['holdMinutes']} минут.", reply_markup=kb.as_markup())
    elif t == "BAR_STATUS":
        text = {"COOKING": "👨‍🍳 Ваш заказ готовят", "DELIVERING": f"🏃 Заказ уже несут к креслу — {e(ev['seatLabel'])}",
                "DELIVERED": "🍿 Заказ доставлен. Приятного просмотра!"}.get(ev["status"])
        if text:
            await bot.send_message(chat, f"{text}\n<i>{e(ev['items'])}</i>")
    elif t == "LEVEL_UP":
        await bot.send_message(chat, f"🏆 <b>Новый уровень: {LEVELS.get(ev['level'], ev['level'])}!</b>\n"
                                     f"Теперь {ev['percent']}% от покупок возвращается баллами"
                               + (" и попкорн в каждом заказе бесплатно 🍿" if ev.get("freePopcorn") else "") + ".")
    elif t == "TICKETS_REFUNDED":
        await bot.send_message(chat, f"↩️ Возврат по билету на «{e(ev['movieTitle'])}» (ряд {ev['row']}, место {ev['seat']}): "
                                     f"<b>{money(ev['refundAmount'])}</b> ({ev['refundPercent']}%)."
                               + (f" Вернули {ev['pointsReturned']} баллов." if ev.get("pointsReturned") else ""))
    elif t == "TICKET_USED":
        kb = InlineKeyboardBuilder()
        kb.button(text="⭐ Оценить фильм после сеанса", callback_data=f"rv:{ev['movieId']}")
        await bot.send_message(chat, f"🍿 Приятного просмотра! {e(ev['hallName'])}, ряд {ev['row']}, место {ev['seat']}.",
                               reply_markup=kb.as_markup())


async def run(bot: Bot):
    while True:
        consumer = AIOKafkaConsumer("telegram", bootstrap_servers=KAFKA, group_id="telegram-bot",
                                    auto_offset_reset="latest", value_deserializer=lambda b: json.loads(b.decode("utf-8")))
        try:
            await consumer.start()
            log.info("Подключился к Kafka %s, слушаю топик telegram", KAFKA)
            async for msg in consumer:
                try:
                    await deliver(bot, msg.value)
                except Exception as err:  # одно сообщение не должно ронять бота
                    log.warning("Не доставлено %s: %s", msg.value.get("type"), err)
        except Exception as err:
            log.warning("Kafka недоступна (%s), повтор через 5 секунд", err)
            await asyncio.sleep(5)
        finally:
            try:
                await consumer.stop()
            except Exception:
                pass

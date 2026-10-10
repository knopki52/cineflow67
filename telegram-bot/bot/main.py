"""Точка входа Telegram-бота CineFlow."""
import asyncio
import logging
import sys

from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.fsm.storage.memory import MemoryStorage
from aiogram.types import BotCommand

from . import notifier
from .api import api
from .config import BOT_TOKEN
from .handlers import router


async def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [telegram-bot] %(name)s: %(message)s")
    if not BOT_TOKEN or ":" not in BOT_TOKEN or "ВАШ" in BOT_TOKEN:
        logging.error("BOT_TOKEN не задан. Создайте бота у @BotFather и пропишите токен в файле .env")
        await asyncio.sleep(3600)  # не перезапускаемся в цикле, остальной проект работает
        return
    bot = Bot(BOT_TOKEN, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
    dp = Dispatcher(storage=MemoryStorage())
    dp.include_router(router)
    await bot.set_my_commands([
        BotCommand(command="start", description="Главное меню"),
        BotCommand(command="afisha", description="Афиша"),
        BotCommand(command="tickets", description="Мои билеты"),
        BotCommand(command="scan", description="Проверить билет (контролёр)"),
    ])
    me = await bot.get_me()
    logging.info("Бот @%s запущен", me.username)
    task = asyncio.create_task(notifier.run(bot))
    try:
        await dp.start_polling(bot)
    finally:
        task.cancel()
        await api.close()
        await bot.session.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)

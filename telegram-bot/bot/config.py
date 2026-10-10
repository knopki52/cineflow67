"""Настройки бота из переменных окружения (файл .env в корне проекта)."""
import os

BOT_TOKEN = os.getenv("BOT_TOKEN", "")
# Gateway: бот ходит в микросервисы так же, как сайт — через Spring Cloud Gateway
API_URL = os.getenv("API_URL", "http://localhost:8080").rstrip("/")
KAFKA = os.getenv("KAFKA", "localhost:29092")
SITE_URL = os.getenv("SITE_URL", "http://localhost:8080")

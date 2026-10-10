"""HTTP-клиент к Gateway. Все вызовы бота идут в те же REST API, что и у сайта."""
import aiohttp

from .config import API_URL


class ApiError(Exception):
    def __init__(self, message: str, status: int = 0):
        super().__init__(message)
        self.status = status


class Api:
    def __init__(self):
        self._session: aiohttp.ClientSession | None = None

    async def session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20))
        return self._session

    async def request(self, method: str, path: str, json=None, params=None):
        s = await self.session()
        try:
            async with s.request(method, API_URL + path, json=json, params=params) as r:
                ctype = r.headers.get("Content-Type", "")
                body = await r.json() if "json" in ctype else await r.read()
                if r.status >= 400:
                    msg = None
                    if isinstance(body, dict):
                        if body.get("error") == "SERVICE_DOWN":
                            msg = "🎞 Плёнка оборвалась: сервис временно недоступен. Попробуйте через минуту."
                        else:
                            msg = body.get("message") or body.get("error")
                    raise ApiError(msg or f"Ошибка {r.status}", r.status)
                return body
        except aiohttp.ClientError:
            raise ApiError("🎞 Сервер CineFlow не отвечает. Попробуйте через минуту.")

    async def get(self, path, **params):
        return await self.request("GET", path, params={k: v for k, v in params.items() if v is not None})

    async def post(self, path, json=None):
        return await self.request("POST", path, json=json if json is not None else {})

    async def delete(self, path, **params):
        return await self.request("DELETE", path, params=params)

    async def patch(self, path, json=None):
        return await self.request("PATCH", path, json=json)

    async def close(self):
        if self._session:
            await self._session.close()


api = Api()

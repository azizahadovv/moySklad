"""
📨 tg-reader — Telegram akkaunt(lar)iga MTProto (Telethon) orqali kirib, FAQAT bitta botdan (SOURCE_BOT)
kelgan xabarlarni o'qiydi va kassa-nazorati ilovasiga HTTP orqali uzatadi. Boshqa chatlarga tegmaydi,
hech narsa yozmaydi (test tekshiruvidan tashqari — o'zining "Saqlangan xabarlar"iga), hech kimga javob
bermaydi.

Login — QR KOD orqali (bot orqali boshqariladi, xuddi Telegram Desktop/Web'dagi «Qurilma ulash» kabi):
DIQQAT (2026-09-15): telefon+kod-matn kiritish usuli ATAYLAB OLIB TASHLANDI — Telegramning o'zi bu
patternni (kodni akkauntdan xabar sifatida yuborish) fishing-qarshi himoya deb hisoblab, kirishni
bloklaydi (client PHONE_CODE_EXPIRED ko'radi, garchi kod to'g'ri/vaqtida kiritilgan bo'lsa ham — bu
TDLib yoki Telethondan qat'i nazar, Telegram SERVER darajasidagi cheklov). QR kodni telefon kamerasi
bilan skanerlash bu himoyani UMUMAN tetiklamaydi (kod matn sifatida hech qayerga yozilmaydi).

Buyruqlar:
  python reader.py login +998901234567   — akkauntga konsoldan bir marta kirish (kod, 2FA parol so'raladi;
                                             bu STDIN orqali, Telegram xabari EMAS — bloklanmaydi, server
                                             terminaliga to'g'ridan-to'g'ri kirish imkoni bo'lganda zaxira)
  python reader.py run                   — barcha sessiyalar bilan ishlash + HTTP boshqaruv serveri (docker compose standart)
  python reader.py list                  — sessiyalar ro'yxati

Muhit: TG_API_ID, TG_API_HASH (my.telegram.org), TG_SOURCE_BOT (masalan HUMOcardbot), APP_URL (http://app:8080),
TGREADER_SECRET (ilova bilan umumiy kalit — ikkala yo'nalishda ham tekshiriladi), SESSIONS_DIR (/sessions),
TG_BACKFILL (birinchi ulanishda nechta eski xabar, 200), CONTROL_PORT (8081).
"""
import asyncio
import base64
import glob
import io
import json
import logging
import os
import sys
import time
from datetime import datetime
from zoneinfo import ZoneInfo

import aiohttp
import qrcode
from aiohttp import web
from telethon import TelegramClient, events
from telethon.errors import SessionPasswordNeededError

API_ID = int(os.environ.get("TG_API_ID", "0") or 0)
API_HASH = os.environ.get("TG_API_HASH", "")
SOURCE_BOT = os.environ.get("TG_SOURCE_BOT", "HUMOcardbot").lstrip("@")
APP_URL = os.environ.get("APP_URL", "http://app:8080").rstrip("/")
SECRET = os.environ.get("TGREADER_SECRET", "")
SESSIONS_DIR = os.environ.get("SESSIONS_DIR", "/sessions")
BACKFILL = int(os.environ.get("TG_BACKFILL", "200") or 200)
CONTROL_PORT = int(os.environ.get("CONTROL_PORT", "8081") or 8081)
HEARTBEAT_SEC = 60
SCAN_SEC = 60

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("tg-reader")

# telefon -> asyncio.Task (serve() ishlab turgan akkauntlar)
running: dict[str, asyncio.Task] = {}
# str(userId) -> {"client","qr","task","step","gen","png","phone","name","error","started_at",...} — QR-login jarayoni
qr_state: dict[str, dict] = {}
# qo'lda to'xtatilgan (pauza) telefonlar — run() sikli qayta ishga tushirmaydi
paused: set[str] = set()

_http: aiohttp.ClientSession | None = None


def _session() -> aiohttp.ClientSession:
    global _http
    if _http is None or _http.closed:
        _http = aiohttp.ClientSession()
    return _http


# ---------------------------------------------------------------- ilova bilan aloqa (Python -> Java, xabar uzatish)
async def post(path: str, payload: dict, retries: int = 5) -> dict | None:
    """POST JSON → ilova (ASYNC — asosiy event loop'ni bloklamaydi, aks holda BARCHA akkauntlar,
    QR kutish va HTTP boshqaruv serveri shu chaqiruv tugagunicha qotib qoladi). Xato bo'lsa qayta uradi."""
    for attempt in range(retries):
        try:
            async with _session().post(APP_URL + path, json=payload, headers={"X-Reader-Secret": SECRET},
                                        timeout=aiohttp.ClientTimeout(total=30)) as r:
                body = await r.text()
                if r.status in (400, 401, 403):
                    log.warning("ilova %s → HTTP %s: %s", path, r.status, body[:200])
                    return None  # qayta urishdan foyda yo'q
                if r.status != 200:
                    log.warning("ilova %s → HTTP %s: %s", path, r.status, body[:200])
                else:
                    return json.loads(body) if body else {}
        except Exception as e:  # noqa: BLE001
            log.warning("ilova %s → %s (urinish %d)", path, e, attempt + 1)
        await asyncio.sleep(min(30, 3 * (attempt + 1)))
    return None


def session_path(phone: str) -> str:
    # "+" fayl nomida SAQLANADI (POSIX'da to'liq yaroqli belgi) — sessions()/run() ham xuddi shu "+"li
    # nomni qaytaradi, aks holda bitta akkaunt ikki xil kalit ("+998..." va "998...") bilan IKKI MARTA
    # ulanadi (bitta SQLite faylga ikki parallel ulanish → "database is locked", ikkita dublikat akkaunt).
    return os.path.join(SESSIONS_DIR, phone.replace(" ", ""))


def sessions() -> list[str]:
    return sorted(os.path.splitext(os.path.basename(p))[0] for p in glob.glob(os.path.join(SESSIONS_DIR, "*.session")))


def new_client(phone: str) -> TelegramClient:
    return TelegramClient(session_path(phone), API_ID, API_HASH, device_model="kassa-nazorati reader", app_version="1.0")


# ---------------------------------------------------------------- login (konsol, ixtiyoriy zaxira)
async def login(phone: str):
    if not API_ID or not API_HASH:
        print("TG_API_ID / TG_API_HASH bo'sh — my.telegram.org dan oling va .env ga yozing", file=sys.stderr)
        sys.exit(2)
    client = new_client(phone)
    await client.connect()
    if not await client.is_user_authorized():
        await client.send_code_request(phone)
        code = input("Telegram'dan kelgan kodni kiriting: ").strip()
        try:
            await client.sign_in(phone, code)
        except SessionPasswordNeededError:
            pwd = input("2FA (bulutli) parol: ").strip()
            await client.sign_in(password=pwd)
    me = await client.get_me()
    print(f"✅ Kirildi: {me.first_name or ''} {me.last_name or ''} (@{me.username or '-'}, id {me.id}) → sessiya {session_path(phone)}.session")
    await client.disconnect()


# ---------------------------------------------------------------- bitta akkaunt (xabar o'qish)
def start_serving(phone: str):
    if phone in paused:
        return
    t = running.get(phone)
    if t is None or t.done():
        running[phone] = asyncio.create_task(serve(phone))


async def serve(phone: str):
    client = new_client(phone)
    await client.connect()
    if not await client.is_user_authorized():
        log.warning("%s: sessiya avtorizatsiya qilinmagan — bot orqali qayta ulang", phone)
        await client.disconnect()
        return
    me = await client.get_me()
    name = ((me.first_name or "") + " " + (me.last_name or "")).strip()
    await post("/api/tgreader/account", {"phone": phone, "name": name, "tgUserId": me.id, "username": me.username or "", "sourceBot": SOURCE_BOT})
    try:
        bot = await client.get_entity(SOURCE_BOT)
    except Exception as e:  # noqa: BLE001
        log.error("%s: @%s topilmadi (%s) — akkaunt bu bot bilan hali yozishmagan bo'lishi mumkin", phone, SOURCE_BOT, e)
        await post("/api/tgreader/heartbeat", {"phone": phone, "error": f"@{SOURCE_BOT} topilmadi"})
        await client.disconnect()
        return

    def payload(m) -> dict:
        media = ""
        if m.photo:
            media = "photo"
        elif m.document:
            media = "document"
        return {"phone": phone, "msgId": m.id, "date": m.date.isoformat(), "text": m.message or "", "media": media, "sourceBot": SOURCE_BOT}

    # oxirgi ma'lum xabardan keyingilarini to'ldirish (ilova qayta ishga tushganda tushib qolmasin)
    st = await post("/api/tgreader/state", {"phone": phone}) or {}
    last_id = int(st.get("lastMsgId") or 0)
    batch = []
    async for m in client.iter_messages(bot, min_id=last_id, limit=None if last_id else BACKFILL, reverse=bool(last_id)):
        batch.append(payload(m))
    if not last_id:
        batch.reverse()  # eskidan yangiga
    if batch:
        await post("/api/tgreader/messages", {"phone": phone, "messages": batch})
        log.info("%s: %d ta eski xabar yuborildi (min_id=%d)", phone, len(batch), last_id)

    @client.on(events.NewMessage(chats=bot))
    async def on_new(event):
        if await post("/api/tgreader/messages", {"phone": phone, "messages": [payload(event.message)]}) is None:
            log.error("%s: xabar %d yuborilmadi — keyingi ulanishda to'ldiriladi", phone, event.message.id)

    async def heartbeat():
        while client.is_connected():
            await post("/api/tgreader/heartbeat", {"phone": phone})
            await asyncio.sleep(HEARTBEAT_SEC)

    log.info("%s (%s): @%s tinglanmoqda", phone, name, SOURCE_BOT)
    hb_task = asyncio.create_task(heartbeat())
    try:
        await client.run_until_disconnected()
    finally:
        hb_task.cancel()
    log.warning("%s: ulanish uzildi", phone)


async def run():
    if not API_ID or not API_HASH:
        log.error("TG_API_ID / TG_API_HASH bo'sh — my.telegram.org dan oling")
        sys.exit(2)
    while True:
        for phone in sessions():
            if phone in paused:
                continue
            start_serving(phone)
        if not running and not sessions():
            log.info("Sessiya yo'q (%s) — bot orqali (🔗 Akkaunt ulash) yoki `docker compose run --rm tg-reader python reader.py login +998...` bilan qo'shing", SESSIONS_DIR)
        await asyncio.sleep(SCAN_SEC)


# ---------------------------------------------------------------- HTTP boshqaruv API (Java -> Python)
def _now() -> float:
    return time.time()


async def api_health(request):
    return web.json_response({"ok": True, "hasApi": bool(API_ID and API_HASH)})


def render_qr_png(url: str) -> str:
    img = qrcode.make(url, box_size=8, border=2)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


async def api_qr_start(request):
    body = await request.json()
    user_id = str(body["userId"])
    if not API_ID or not API_HASH:
        return web.json_response({"step": "ERROR", "error": "TG_API_ID/HASH .env da sozlanmagan"})
    await _drop_qr(user_id)
    temp_name = f".qr-{user_id}-{int(_now() * 1000)}"
    client = new_client(temp_name)
    await client.connect()
    try:
        qr = await client.qr_login()
    except Exception as e:  # noqa: BLE001
        await client.disconnect()
        return web.json_response({"step": "ERROR", "error": str(e)})
    state = {"client": client, "qr": qr, "temp_name": temp_name, "step": "QR", "gen": 1,
             "png": render_qr_png(qr.url), "phone": None, "name": None, "error": None,
             "started_at": _now()}
    qr_state[user_id] = state
    state["task"] = asyncio.create_task(qr_watch(user_id))
    return web.json_response({"step": "QR", "gen": 1, "png": state["png"]})


async def qr_watch(user_id: str):
    """QR tokeni ~30s'da eskiradi — inson telefonini olib skanerlashga ulguradigan bo'lsin deb,
    fon rejimida muntazam yangilaymiz (auth.exportLoginToken qayta so'raladi, rasm yangilanadi)."""
    state = qr_state.get(user_id)
    if not state:
        return
    qr = state["qr"]
    client = state["client"]
    deadline = _now() + 600  # 10 daqiqa umumiy chegara (odam telefonini topib, ilovani ochib ulguradi)
    try:
        while _now() < deadline:
            try:
                await qr.wait()
                break  # skanerlandi va tasdiqlandi (2FA yo'q) — parolsiz muvaffaqiyatli
            except asyncio.TimeoutError:
                await qr.recreate()
                state["gen"] += 1
                state["png"] = render_qr_png(qr.url)
                continue
            except SessionPasswordNeededError:
                state["step"] = "NEED_PASSWORD"
                return
            except Exception as e:  # noqa: BLE001
                state["step"] = "ERROR"
                state["error"] = str(e)
                await _close_qr_client(state)
                return
        else:
            state["step"] = "ERROR"
            state["error"] = "Vaqt tugadi (10 daqiqa) — qaytadan boshlang"
            await _close_qr_client(state)
            return
    except asyncio.CancelledError:
        return
    await finish_qr(user_id)


async def finish_qr(user_id: str):
    state = qr_state.get(user_id)
    if not state:
        return
    client = state["client"]
    try:
        me = await client.get_me()
    except Exception as e:  # noqa: BLE001
        state["step"] = "ERROR"
        state["error"] = str(e)
        await _close_qr_client(state)
        return
    phone = "+" + (me.phone or "")
    name = ((me.first_name or "") + " " + (me.last_name or "")).strip()
    await client.disconnect()
    old_file = session_path(state["temp_name"]) + ".session"
    new_file = session_path(phone) + ".session"
    try:
        if os.path.exists(old_file):
            os.replace(old_file, new_file)
    except OSError as e:
        log.warning("%s: sessiya nomini almashtirib bo'lmadi: %s", phone, e)
    state["step"] = "CONNECTED"
    state["phone"] = phone
    state["name"] = name
    paused.discard(phone)
    start_serving(phone)


async def _close_qr_client(state: dict):
    try:
        await state["client"].disconnect()
    except Exception:  # noqa: BLE001
        pass
    try:
        p = session_path(state["temp_name"]) + ".session"
        if os.path.exists(p):
            os.remove(p)
    except OSError:
        pass


async def _drop_qr(user_id: str):
    old = qr_state.pop(user_id, None)
    if not old:
        return
    task = old.get("task")
    if task:
        task.cancel()
    await _close_qr_client(old)


async def api_qr_poll(request):
    body = await request.json()
    user_id = str(body["userId"])
    state = qr_state.get(user_id)
    if not state:
        return web.json_response({"step": "ERROR", "error": "Sessiya topilmadi — qaytadan boshlang"})
    resp = {"step": state["step"], "gen": state["gen"]}
    if state["step"] == "QR":
        resp["png"] = state["png"]
    elif state["step"] == "CONNECTED":
        resp["phone"] = state["phone"]
        resp["name"] = state["name"]
        qr_state.pop(user_id, None)
    elif state["step"] == "ERROR":
        resp["error"] = state["error"]
        qr_state.pop(user_id, None)
    return web.json_response(resp)


async def api_qr_password(request):
    body = await request.json()
    user_id, password = str(body["userId"]), body["password"]
    state = qr_state.get(user_id)
    if not state or state["step"] != "NEED_PASSWORD":
        return web.json_response({"step": "ERROR", "error": "Login sessiyasi topilmadi — qaytadan boshlang"})
    client = state["client"]
    try:
        await client.sign_in(password=password)
    except Exception as e:  # noqa: BLE001
        state["step"] = "ERROR"
        state["error"] = str(e)
        await _close_qr_client(state)
        return web.json_response({"step": "ERROR", "error": str(e)})
    await finish_qr(user_id)
    resp = {"step": state["step"]}
    if state["step"] == "CONNECTED":
        resp["phone"] = state["phone"]
        resp["name"] = state["name"]
    else:
        resp["error"] = state.get("error")
    qr_state.pop(user_id, None)
    return web.json_response(resp)


async def api_qr_cancel(request):
    body = await request.json()
    await _drop_qr(str(body["userId"]))
    return web.json_response({"ok": True})


async def api_pause(request):
    body = await request.json()
    phone = body["phone"]
    paused.add(phone)
    t = running.pop(phone, None)
    if t:
        t.cancel()
    return web.json_response({"ok": True})


async def api_resume(request):
    body = await request.json()
    phone = body["phone"]
    paused.discard(phone)
    start_serving(phone)
    return web.json_response({"ok": True})


async def api_delete(request):
    body = await request.json()
    phone = body["phone"]
    paused.discard(phone)
    t = running.pop(phone, None)
    if t:
        t.cancel()
    try:
        os.remove(session_path(phone) + ".session")
    except FileNotFoundError:
        pass
    return web.json_response({"ok": True})


async def api_test(request):
    body = await request.json()
    phone = body["phone"]
    client = new_client(phone)
    try:
        await client.connect()
        if not await client.is_user_authorized():
            return web.json_response({"ok": False, "message": "Ulanmagan — avval ulang yoki qayta yoqing"})
        text = "✅ NSB bot — ulanish tekshiruvi\n🕒 " + datetime.now(ZoneInfo("Asia/Tashkent")).strftime("%d.%m.%Y %H:%M:%S")
        await client.send_message("me", text)
        # @SOURCE_BOT'dan haqiqatan xabar o'qilayotganini ko'rgazmali tasdiqlash — oxirgi xabarni
        # Saqlangan xabarlarga forward qilamiz (mavjud bo'lsa; bo'lmasa — buni ham xabar qilamiz).
        try:
            bot = await client.get_entity(SOURCE_BOT)
            last = await client.get_messages(bot, limit=1)
        except Exception as e:  # noqa: BLE001
            return web.json_response({"ok": True, "message": f"Test xabar yuborildi ✅, lekin @{SOURCE_BOT}ga ulanib bo'lmadi: {e}"})
        if last:
            await client.forward_messages("me", last[0])
            return web.json_response({"ok": True, "message": f"Test xabar + @{SOURCE_BOT}dan oxirgi xabar Saqlangan xabarlarga yuborildi ✅"})
        return web.json_response({"ok": True, "message": f"Test xabar yuborildi ✅ (@{SOURCE_BOT}dan hali xabar kelmagan)"})
    except Exception as e:  # noqa: BLE001
        return web.json_response({"ok": False, "message": str(e)})
    finally:
        await client.disconnect()


async def api_pending(request):
    out = [{"userId": int(uid), "startedAt": int(st["started_at"])} for uid, st in qr_state.items()]
    return web.json_response({"pending": out})


@web.middleware
async def auth_middleware(request, handler):
    if request.path != "/health" and SECRET and request.headers.get("X-Reader-Secret") != SECRET:
        return web.json_response({"error": "forbidden"}, status=403)
    return await handler(request)


async def start_http_server():
    app = web.Application(middlewares=[auth_middleware])
    app.add_routes([
        web.get("/health", api_health),
        web.post("/login/qr/start", api_qr_start),
        web.post("/login/qr/poll", api_qr_poll),
        web.post("/login/qr/password", api_qr_password),
        web.post("/login/qr/cancel", api_qr_cancel),
        web.post("/account/pause", api_pause),
        web.post("/account/resume", api_resume),
        web.post("/account/delete", api_delete),
        web.post("/account/test", api_test),
        web.get("/pending", api_pending),
    ])
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", CONTROL_PORT)
    await site.start()
    log.info("Boshqaruv HTTP serveri: 0.0.0.0:%d", CONTROL_PORT)


async def main():
    await asyncio.gather(run(), start_http_server())


if __name__ == "__main__":
    os.makedirs(SESSIONS_DIR, exist_ok=True)
    cmd = sys.argv[1] if len(sys.argv) > 1 else "run"
    if cmd == "login" and len(sys.argv) > 2:
        asyncio.run(login(sys.argv[2]))
    elif cmd == "list":
        for s in sessions():
            print(s)
    else:
        asyncio.run(main())

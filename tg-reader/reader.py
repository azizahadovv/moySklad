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
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

import aiohttp
import qrcode
from aiohttp import web
from telethon import TelegramClient, events, functions, types, utils
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
MEDIA_MAX = 3 * 1024 * 1024   # OCR uchun rasm chegarasi (bayt)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("tg-reader")

# telefon -> asyncio.Task (serve() ishlab turgan akkauntlar)
running: dict[str, asyncio.Task] = {}
# telefon -> serve() ichida ishlab turgan TelegramClient. Test kabi amallar SHU mijozni ishlatadi: bitta SQLite
# sessiya fayliga ikkinchi mijoz ochilsa "database is locked" (2026-09-16 — ✅ Tekshirish HTTP 500 berardi).
clients: dict[str, TelegramClient] = {}
# str(userId) -> {"client","qr","task","step","gen","png","phone","name","error","started_at",...} — QR-login jarayoni
qr_state: dict[str, dict] = {}
# qo'lda to'xtatilgan (pauza) telefonlar — run() sikli qayta ishga tushirmaydi
paused: set[str] = set()
# Ilova sozlamalari (GET /api/tgreader/config, har SCAN_SEC): manbalar ro'yxati (FAQAT shular o'qiladi), asosiy bot, rasm OCR.
# Ilova hali javob bermasa — .env dagi bitta bot bilan ishlaydi.
CONFIG: dict = {"sources": [SOURCE_BOT] if SOURCE_BOT else [], "sourceBot": SOURCE_BOT, "media": False}

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


async def get(path: str) -> dict | None:
    """GET JSON ← ilova (bir urinish; sozlama o'qish uchun)."""
    try:
        async with _session().get(APP_URL + path, headers={"X-Reader-Secret": SECRET},
                                   timeout=aiohttp.ClientTimeout(total=15)) as r:
            body = await r.text()
            if r.status == 200:
                return json.loads(body) if body else {}
            log.warning("ilova %s → HTTP %s: %s", path, r.status, body[:200])
    except Exception as e:  # noqa: BLE001
        log.warning("ilova %s → %s", path, e)
    return None


async def fetch_config():
    r = await get("/api/tgreader/config")
    if r is None:
        return
    srcs = [str(x).strip().lstrip("@") for x in (r.get("sources") or []) if str(x).strip()]
    if srcs:
        CONFIG["sources"] = srcs
    if r.get("sourceBot"):
        CONFIG["sourceBot"] = str(r["sourceBot"]).strip().lstrip("@")
    CONFIG["media"] = bool(r.get("media"))


def main_bot() -> str:
    return CONFIG.get("sourceBot") or SOURCE_BOT


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
    clients[phone] = client
    try:
        await _serve(client, phone)
    finally:
        clients.pop(phone, None)


async def _serve(client: TelegramClient, phone: str):
    await client.connect()
    if not await client.is_user_authorized():
        log.warning("%s: sessiya avtorizatsiya qilinmagan — bot orqali qayta ulang", phone)
        await client.disconnect()
        return
    me = await client.get_me()
    name = ((me.first_name or "") + " " + (me.last_name or "")).strip()
    await post("/api/tgreader/account", {"phone": phone, "name": name, "tgUserId": me.id, "username": me.username or "", "sourceBot": main_bot()})

    # Manbalar (ilova sozlamasi, har SCAN_SEC yangilanadi): kalit → {"id": chat_id, "group": guruhmi}.
    # FAQAT shu ro'yxatdagi chatlar o'qiladi — xodimning boshqa yozishmalariga tegilmaydi.
    resolved: dict[str, dict] = {}
    failed: dict[str, float] = {}   # kalit → qachon urinilgan (10 daqiqada bir qayta uriniladi)

    async def payload(m, key: str, with_media: bool = False) -> dict:
        media = "photo" if m.photo else ("document" if m.document else "")
        d = {"phone": phone, "msgId": m.id, "date": m.date.isoformat(), "text": m.message or "", "media": media, "sourceBot": key}
        if (resolved.get(key) or {}).get("group"):   # guruh: kim yozgani (bot/odam/kanalda manbaning o'zi — yozilmaydi)
            try:
                snd = await m.get_sender()
                if snd is not None:
                    d["sender"] = (getattr(snd, "title", None) or ((getattr(snd, "first_name", "") or "") + " " + (getattr(snd, "last_name", "") or ""))).strip() or str(getattr(snd, "id", ""))
            except Exception:  # noqa: BLE001
                pass
        if with_media and CONFIG.get("media") and (m.photo or (m.document and (m.document.mime_type or "").startswith("image/"))):
            try:
                data = await client.download_media(m, bytes)
                if data and len(data) <= MEDIA_MAX:
                    d["photoB64"] = base64.b64encode(data).decode("ascii")
                elif data:
                    log.info("%s: %s #%d rasm juda katta (%d bayt) — OCR qilinmaydi", phone, key, m.id, len(data))
            except Exception as e:  # noqa: BLE001
                log.warning("%s: rasm yuklab olinmadi (%s #%d): %s", phone, key, m.id, e)
        return d

    async def backfill(key: str, ent):
        st = await post("/api/tgreader/state", {"phone": phone, "source": key}) or {}
        last_id = int(st.get("lastMsgId") or 0)
        limit = None if last_id else (BACKFILL if key == main_bot() else min(BACKFILL, 50))   # qo'shimcha manbada eski xabar kamroq
        batch = []
        async for m in client.iter_messages(ent, min_id=last_id, limit=limit, reverse=bool(last_id)):
            batch.append(await payload(m, key))   # eski xabarlar uchun rasm yuklanmaydi (og'ir)
        if not last_id:
            batch.reverse()  # eskidan yangiga
        if batch:
            await post("/api/tgreader/messages", {"phone": phone, "messages": batch})
            log.info("%s: %s — %d ta eski xabar yuborildi (min_id=%d)", phone, key, len(batch), last_id)

    async def resolve_sources():
        want = list(CONFIG.get("sources") or [])
        for key in want:
            if key in resolved:
                continue
            if key in failed and _now() - failed[key] < 600:
                continue
            try:
                ent = await client.get_entity(_source_ref(key))
                is_group = isinstance(ent, types.Chat) or (isinstance(ent, types.Channel) and bool(ent.megagroup))
                resolved[key] = {"id": utils.get_peer_id(ent), "group": is_group}
                failed.pop(key, None)
                log.info("%s: manba %s ulandi (id %s%s)", phone, key, resolved[key]["id"], ", guruh" if is_group else "")
                await backfill(key, ent)
            except Exception as e:  # noqa: BLE001
                failed[key] = _now()
                log.warning("%s: manba %s topilmadi: %s", phone, key, e)
                if key == main_bot():
                    await post("/api/tgreader/heartbeat", {"phone": phone, "error": f"@{key} topilmadi"})
        for key in list(resolved):   # sozlamadan olib tashlanganlar
            if key not in want:
                resolved.pop(key)
                log.info("%s: manba %s o'chirildi", phone, key)

    await resolve_sources()

    @client.on(events.NewMessage())
    async def on_new(event):
        key = next((k for k, v in resolved.items() if v["id"] == event.chat_id), None)
        if key is None:
            return
        if event.out and key != "me":   # o'zimiz yuborgan (masalan qoldiq so'rovi) — «me» dan tashqari
            return
        if await post("/api/tgreader/messages", {"phone": phone, "messages": [await payload(event.message, key, True)]}) is None:
            log.error("%s: xabar %d (%s) yuborilmadi — keyingi ulanishda to'ldiriladi", phone, event.message.id, key)

    async def heartbeat():
        while client.is_connected():
            await post("/api/tgreader/heartbeat", {"phone": phone})
            await asyncio.sleep(HEARTBEAT_SEC)

    async def refresh():
        while client.is_connected():
            await asyncio.sleep(SCAN_SEC)
            try:
                await resolve_sources()
            except Exception as e:  # noqa: BLE001
                log.warning("%s: manbalarni yangilashda xato: %s", phone, e)

    log.info("%s (%s): %d ta manba tinglanmoqda: %s", phone, name, len(resolved), ", ".join(resolved) or "—")
    hb_task = asyncio.create_task(heartbeat())
    rf_task = asyncio.create_task(refresh())
    try:
        await client.run_until_disconnected()
    finally:
        hb_task.cancel()
        rf_task.cancel()
    log.warning("%s: ulanish uzildi", phone)


def _source_ref(key: str):
    """Sozlamadagi manba kaliti → Telethon entity ko'rsatkichi: me | username | raqamli id (kanal/guruh -100…)."""
    k = key.strip().lstrip("@")
    if k.lower() == "me":
        return "me"
    if k.lstrip("-").isdigit():
        return int(k)
    return k


async def run():
    # MUHIM: kalit bo'sh bo'lsa ham jarayon O'LMAYDI — HTTP boshqaruv serveri tirik qoladi, shunda bot
    # umumiy «tg-reader javob bermadi» o'rniga aniq «TG_API_ID/HASH sozlanmagan» xabarini ko'rsatadi.
    # (Avval sys.exit(2) edi → konteyner restart-siklda, app'da ConnectException.)
    while not API_ID or not API_HASH:
        log.error("TG_API_ID / TG_API_HASH bo'sh — my.telegram.org dan oling va .env ga yozib, `docker compose up -d tg-reader` qiling")
        await asyncio.sleep(60)
    while True:
        await fetch_config()   # manbalar / OCR — ilova sozlamasidan
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
    log.info("QR[%s]: boshlandi", user_id)
    return web.json_response({"step": "QR", "gen": 1, "png": state["png"]})


async def _try_complete(state: dict):
    """Tokenni qayta so'rab, telefon uni QABUL QILGAN-QILMAGANINI tekshiradi (xuddi QRLogin.wait() signal
    kelganda qilgani kabi). Telegram tokenni qabul qilishi bilan reader'ning auth_key'i DARHOL avtorizatsiya
    bo'ladi (telefonda «Faol seanslar»da ko'rinadi), lekin UpdateLoginToken signali QRLogin.wait() tashqarisida —
    recreate()/rasm chizish oralig'ida — kelsa YO'QOLADI va login hech qachon tugamaydi (2026-09-16: ikkinchi
    akkaunt shu tufayli «ulanmadi», telefonda esa egasiz seans qoldi). Bu yerda signalga bog'lanmaymiz.
    Qaytadi: True — ulandi; "PASSWORD" — 2FA parol kerak; False — hali yo'q (qr'ga YANGI token o'rnatildi)."""
    client, qr = state["client"], state["qr"]
    try:
        resp = await client(functions.auth.ExportLoginTokenRequest(client.api_id, client.api_hash, []))
        if isinstance(resp, types.auth.LoginTokenMigrateTo):
            await client._switch_dc(resp.dc_id)
            resp = await client(functions.auth.ImportLoginTokenRequest(resp.token))
        if isinstance(resp, types.auth.LoginTokenSuccess):
            await client._on_login(resp.authorization.user)   # telethon 1.36 (pinned): QRLogin.wait() ham aynan shuni qiladi
            return True
        if isinstance(resp, types.auth.LoginToken):
            qr._resp = resp   # = qr.recreate() natijasi (bitta so'rov bilan): url/expires yangi tokenga o'tadi
    except SessionPasswordNeededError:
        return "PASSWORD"
    return False


async def qr_watch(user_id: str):
    """QR tokeni ~30s'da eskiradi — inson telefonini olib skanerlashga ulguradigan bo'lsin deb, muddati tugaganda
    yangilaymiz (rasm ham yangilanadi, bot uni ko'rsatadi).
    MUHIM (2026-09-16): UpdateLoginToken tinglovchisi BUTUN jarayon davomida turadi (QRLogin.wait() uni faqat o'z
    ichida ushlab, chiqishda o'chiradi — oraliqda kelgan signal yo'qolardi) va har siklda token qabul qilingan-
    qilinmagani _try_complete bilan serverdan ALOHIDA so'raladi — telefon ekranda qolib ketgan ESKI QR'ni
    skanerlasa ham ulanish tugallanadi. Signal kelganda darhol tugaydi (tezlik avvalgidek)."""
    state = qr_state.get(user_id)
    if not state:
        return
    qr = state["qr"]
    client = state["client"]
    deadline = _now() + 600  # 10 daqiqa umumiy chegara (odam telefonini topib, ilovani ochib ulguradi)
    scanned = asyncio.Event()

    async def on_login_token(_update):
        scanned.set()

    client.add_event_handler(on_login_token, events.Raw(types.UpdateLoginToken))
    log.info("QR[%s]: yaratildi gen=1 (muddati %s UTC)", user_id, qr.expires.strftime("%H:%M:%S"))
    try:
        while _now() < deadline:
            left = (qr.expires - datetime.now(timezone.utc)).total_seconds()
            try:
                await asyncio.wait_for(scanned.wait(), timeout=max(1.0, min(left, 60.0)))
                log.info("QR[%s]: skanerlandi (signal keldi)", user_id)
            except asyncio.TimeoutError:
                pass
            scanned.clear()
            done = await _try_complete(state)
            if done is True:
                break
            if done == "PASSWORD":
                state["step"] = "NEED_PASSWORD"
                log.info("QR[%s]: skanerlandi, 2FA parol kutilmoqda", user_id)
                return
            state["gen"] += 1
            state["png"] = render_qr_png(qr.url)
            log.info("QR[%s]: token yangilandi gen=%d", user_id, state["gen"])
        else:
            state["step"] = "ERROR"
            state["error"] = "Vaqt tugadi (10 daqiqa) — qaytadan boshlang"
            log.warning("QR[%s]: 10 daqiqada skanerlanmadi", user_id)
            await _close_qr_client(state, logout=True)
            return
    except asyncio.CancelledError:
        return
    except Exception as e:  # noqa: BLE001
        state["step"] = "ERROR"
        state["error"] = str(e)
        log.warning("QR[%s]: xato: %s", user_id, e)
        await _close_qr_client(state, logout=True)
        return
    finally:
        client.remove_event_handler(on_login_token)
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
    phone = "+" + me.phone if me.phone else f"id{me.id}"   # o'z raqami doim keladi; ehtiyot: ikki akkaunt "+.session"ga tushmasin
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
    log.info("QR[%s]: ULANDI %s (%s)", user_id, phone, name)
    paused.discard(phone)
    start_serving(phone)


async def _close_qr_client(state: dict, logout: bool = False):
    client = state["client"]
    try:
        # telefon tokenni qabul qilib ulgurgan, lekin login yakunlanmagan bo'lsa — «Faol seanslar»da EGASIZ seans qolmasin
        if logout and client.is_connected() and await client.is_user_authorized():
            await client.log_out()
            log.info("QR: yarim qolgan avtorizatsiya chiqarildi (log_out)")
    except Exception:  # noqa: BLE001
        pass
    try:
        await client.disconnect()
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
    if old.get("step") == "CONNECTED":
        return  # allaqachon ulangan va o'z nomi bilan ishlayapti — hech narsa yopilmaydi
    await _close_qr_client(old, logout=True)


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
        log.warning("QR[%s]: 2FA parol xatosi: %s", user_id, e)
        await _close_qr_client(state, logout=True)
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
    log.info("QR[%s]: bekor qilindi (bot)", body["userId"])
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
    # Ishlab turgan akkaunt uchun serve() mijozi ishlatiladi (ikkinchi mijoz bir xil .session faylga →
    # "database is locked"). Faqat to'xtatilgan/ulanmagan akkaunt uchun vaqtinchalik mijoz ochiladi.
    shared = clients.get(phone)
    client = shared if shared is not None else new_client(phone)
    try:
        if shared is None:
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
        log.warning("%s: test xatosi: %s", phone, e)
        return web.json_response({"ok": False, "message": str(e)})
    finally:
        if shared is None:
            try:
                await client.disconnect()
            except Exception as e:  # noqa: BLE001
                log.warning("%s: test mijozini yopishda xato: %s", phone, e)   # javob (return) saqlanib qoladi


async def api_balance(request):
    """💰 Asosiy botga qadam-baqadam buyruq/tugma yuborib qoldiqni so'rash. Javoblar oddiy xabar sifatida ham ilovaga
    ketadi (on_new → parser → karta yangilanadi); bu yerda faqat ko'rsatish uchun matnlari qaytariladi."""
    body = await request.json()
    phone = body["phone"]
    steps = [str(x).strip() for x in (body.get("steps") or []) if str(x).strip()]
    if not steps:
        return web.json_response({"ok": False, "message": "Buyruq sozlanmagan"})
    client = clients.get(phone)
    if client is None or not client.is_connected():
        return web.json_response({"ok": False, "message": "Akkaunt ulanmagan"})
    try:
        bot = await client.get_entity(main_bot())
        replies = []
        for step in steps:
            last = await client.get_messages(bot, limit=1)
            last = last[0] if last else None
            last_id = last.id if last else 0
            last_edit = last.edit_date if last else None
            clicked = False
            if last and last.buttons:   # inline tugma: matni mos kelsa bosiladi
                try:
                    clicked = (await last.click(text=step)) is not None
                except Exception as e:  # noqa: BLE001
                    log.info("%s: tugma «%s» bosilmadi (%s) — matn sifatida yuboriladi", phone, step, e)
            if not clicked:
                await client.send_message(bot, step)
            reply = await _wait_reply(client, bot, last_id, last_edit, 7.0)
            if reply is None:
                replies.append(f"«{step}» → javob kelmadi")
                return web.json_response({"ok": False, "message": "Bot javob bermadi", "replies": replies})
            replies.append(reply.message or "(matnsiz)")
        log.info("%s: qoldiq so'rovi bajarildi (%d qadam)", phone, len(steps))
        return web.json_response({"ok": True, "message": f"{len(steps)} qadam, javob olindi", "replies": replies})
    except Exception as e:  # noqa: BLE001
        log.warning("%s: qoldiq so'rovi xatosi: %s", phone, e)
        return web.json_response({"ok": False, "message": str(e)})


async def _wait_reply(client, bot, after_id: int, prev_edit, timeout: float):
    """Botdan YANGI xabar (id > after_id, bizniki emas) yoki oxirgi xabarning TAHRIRI (inline tugma bosilganda botlar
    ko'pincha shu xabarni o'zgartiradi) kelguncha kutadi."""
    deadline = _now() + timeout
    while _now() < deadline:
        await asyncio.sleep(0.6)
        msgs = await client.get_messages(bot, limit=1)
        m = msgs[0] if msgs else None
        if m is None:
            continue
        if m.id > after_id and not m.out:
            return m
        if m.id == after_id and m.edit_date and m.edit_date != prev_edit:
            return m
    return None


async def api_security(request):
    """🔐 Faol seanslar (account.getAuthorizations) va 2FA holati — admin ko'radi, yangi qurilma bo'lsa ilova ogohlantiradi."""
    body = await request.json()
    phone = body["phone"]
    shared = clients.get(phone)
    client = shared if shared is not None else new_client(phone)
    try:
        if shared is None:
            await client.connect()
        if not await client.is_user_authorized():
            return web.json_response({"ok": False, "message": "Ulanmagan"})
        auths = await client(functions.account.GetAuthorizationsRequest())
        pwd = await client(functions.account.GetPasswordRequest())
        sessions_out = []
        for au in auths.authorizations:
            sessions_out.append({"hash": au.hash, "device": au.device_model or "", "platform": au.platform or "",
                                 "app": ((au.app_name or "") + " " + (au.app_version or "")).strip(), "country": au.country or "",
                                 "ip": au.ip or "", "dateActive": au.date_active.isoformat() if au.date_active else None,
                                 "dateCreated": au.date_created.isoformat() if au.date_created else None,
                                 "current": bool(au.current), "official": bool(au.official_app)})
        return web.json_response({"ok": True, "twoFa": bool(pwd.has_password), "sessions": sessions_out})
    except Exception as e:  # noqa: BLE001
        return web.json_response({"ok": False, "message": str(e)})
    finally:
        if shared is None:
            try:
                await client.disconnect()
            except Exception:  # noqa: BLE001
                pass


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
        web.post("/account/balance", api_balance),
        web.post("/account/security", api_security),
        web.get("/pending", api_pending),
    ])
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", CONTROL_PORT)
    await site.start()
    log.info("Boshqaruv HTTP serveri: 0.0.0.0:%d", CONTROL_PORT)


def _cleanup_temp_sessions():
    """Chala qolgan QR-login vaqtinchalik sessiyalari (.qr-*.session*) — qayta ishga tushganda ular hech kimga kerak emas."""
    for p in glob.glob(os.path.join(SESSIONS_DIR, ".qr-*.session*")):
        try:
            os.remove(p)
            log.info("Eski vaqtinchalik QR sessiya o'chirildi: %s", os.path.basename(p))
        except OSError:
            pass


async def main():
    _cleanup_temp_sessions()
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

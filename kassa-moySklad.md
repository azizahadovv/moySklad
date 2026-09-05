# Kassa Nazorati — MoySklad bilan integratsiyalashgan Telegram bot

**NewStarBukhara** kompaniyasi uchun kassa–buxgalteriya pul aylanmasini
avtomatlashtiruvchi tizim: kirim (prixod), o'tkazma, qarz, hisobot
topshirish/qabul qilish va kontragentlar bilan ishlash — hammasi bitta
Telegram bot orqali, ma'lumotlar **MoySklad**dan jonli olinadi. Bot faqat
MoySklad'ni kuzatuvchi/qayta hisoblovchi oyna — pul harakati (jumladan rasxod)
qo'lda emas, faqat MoySklad hujjatlari orqali yuritiladi.

**Stek:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · TelegramBots 6.9 (long polling) · Docker

---

## 1. Loyihaning maqsadi

- Sotuv kassalari (otdellar) va buxgalteriya o'rtasidagi **barcha pul harakatini**
  shaffof yuritish. Balans hech qachon "qo'lda" o'zgarmaydi — faqat **ledger**
  (operatsiyalar jurnali) orqali; istalgan balansni jurnaldan qayta hisoblab
  tekshirish mumkin.
- **MoySklad sinxroni**: Приходный/Расходный ордер, Входящий/Исходящий платеж
  (Клик/Карта), savdo hujjatlari har 30 soniyada o'qiladi. Hujjat
  o'zgartirilsa/o'chirilsa — bot balansni **avtomatik tuzatadi** (summa delta,
  otdel ko'chirish, STORNO). Click chiqimi ham shu yo'l bilan — Исходящий
  платеж hujjatiga «Клик» statusi qo'yilsa, kassaning Click hisobidan rasxod
  sifatida yoziladi.
- **Kontragent qarz daftari** («Отдел Али»): postavchik qarzlarini muddati bilan
  nazorat qilish, tanlangan kunlarda (masalan 3-1 kun oldin) va muddat kunida
  soat 09:00 da tanlangan xodimlarga eslatma yuborish.
- **Google Sheets** bilan ikki tomonlama НАСТРОЙКА (foydalanuvchi/kassa) va
  hisobot varaqlari. **Bot bazasi — yagona haqiqat manbai**: jadval katagi faqat
  operator uni haqiqatan o'zgartirganda botga qo'llanadi.

## 2. Rollar

| Rol | Nima qila oladi |
|---|---|
| **KASSIR** | faqat o'z kassasi: balans, tushum, tarix; o'tkazma, hisobot topshirish; kontragent qarz daftari (o'ziniki); kassasi bo'lsa — otdeliga odam qo'shish |
| **BUXGALTER** | barcha kassalar holati, statistika, svod/Excel; hisobot qabul, kassadan pul qabul qilish |
| **SUPERADMIN** | hammasi + Настройка: foydalanuvchi/kassa/rol, boshlang'ich qoldiq, Аудит (Excel), tugma nomlari va bo'limlarni o'chirish/yoqish, huquqlar (user/otdel kesimida), MoySklad API kaliti |

Bo'lim huquqlari uch darajada: **user** sozlamasi → **otdel (kassa)** sozlamasi →
umumiy holat (SuperAdmin: ⚙️ Настройка → 👁 Ҳуқуқлар).

## 3. Ishga tushirish

### 3.1. Server / istalgan mashina — Docker bilan (tavsiya)

```bash
# 1. .env faylini to'ldiring (namuna quyida)
# 2. Baza + ilova birga ko'tariladi:
docker compose up --build -d
# Loglar:
docker compose logs -f app
```

Birinchi ishga tushishda Flyway barcha migratsiyalarni (V1..V8) o'zi qo'llaydi.
**V8 seed**: yangi bo'sh bazada hozirgi otdellar, foydalanuvchilar va Click
hisoblari **bir marta avtomatik yaratiladi** (mavjud bazada hech narsani
takrorlamaydi). Diqqat: seed **tarixni** (balans/operatsiyalar) ko'chirmaydi —
to'liq ko'chirish uchun eski bazadan `pg_dump`, yangisiga `pg_restore`.

> ⚠️ **Bitta token — bitta bot nusxasi.** Bot serverda ishga tushirilgach,
> lokaldagi nusxani albatta to'xtating: ikkita nusxa bir vaqtda ishlasa
> Telegram ularni navbatma-navbat uzadi (409) va bot "qotib qolgandek" bo'ladi.

### 3.2. Lokal (shu kompyuterda, Docker'siz ilova)

PATH'da java/mvn yo'q — to'liq yo'llar bilan:

```powershell
# Baza (Docker, port 5435):
docker start kassa-db   # yo'q bo'lsa: docker run -d --name kassa-db -p 5435:5432 -e POSTGRES_USER=data -e POSTGRES_PASSWORD=data -e POSTGRES_DB=kassa postgres:16

# Build (DOIM clean bilan — IDE buzuq .class qoldirishi mumkin):
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot'
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.0.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd' clean package -DskipTests

# Run (.env dagi qiymatlar env o'zgaruvchi sifatida beriladi — Spring .env o'qimaydi):
$env:DB_URL='jdbc:postgresql://localhost:5435/kassa'; $env:DB_USER='data'; $env:DB_PASSWORD='data'
$env:BOT_TOKEN='<token>'; $env:BOT_USERNAME='nsbkassa_bot'; $env:SUPERADMIN_TELEGRAM_ID='<id>'
$env:MOYSKLAD_TOKEN='<token>'; $env:WEBAPP_PORT='8971'   # 8080 bu mashinada band
& "$env:JAVA_HOME\bin\java.exe" -jar target\kassa-nazorati-0.1.0.jar
```

### 3.3. .env namunasi

```env
BOT_TOKEN=...                  # @BotFather
BOT_USERNAME=nsbkassa_bot
SUPERADMIN_TELEGRAM_ID=...     # birinchi ishga tushishda avtomatik yaratiladi
SUPERADMIN_NAME=Boshliq
MOYSKLAD_TOKEN=...             # keyin botning o'zidan ham almashtirsa bo'ladi (🔑 MoySklad API)
WEBAPP_PORT=8080
WEBAPP_URL=                    # Mini App uchun tashqi HTTPS (ixtiyoriy)
GSHEET_ID=...                  # Google Sheets jadval ID
GSHEET_SCRIPT_URL=...          # Apps Script web-app /exec URL (docs/apps-script.gs)
GSHEET_SECRET=...
DB_URL=jdbc:postgresql://db:5432/kassa   # docker-compose ichida
DB_USER=data
DB_PASSWORD=data
```

## 4. Muhim ish qoidalari (arxitektura qarorlari)

- **Rasxod faqat MoySklad orqali.** Botning o'z ichidagi qo'lda rasxod oqimi
  (kassir so'rovi → buxgalter tasdig'i, buxgalterning o'z rasxodi, "kassa
  nomidan rasxod kiritish") 2026-08-27 da ataylab OLIB TASHLANDI — bot endi
  MoySklad'ni kuzatuvchi/qayta hisoblovchi oyna. NAQD rasxod — Расходный ордер
  (cashout), KLIK rasxod — Исходящий платеж (paymentout) hujjatiga «Клик»
  statusi qo'yilganda. Har ikkisida ham hujjatning **Отдел** (Владелец) maydoni
  to'g'ri kassaga tanlanishi shart — aks holda pul "Отдел Основной"
  (Buxgalteriya)ga tushadi; keyinroq Отдel to'g'rilansa, bot avtomatik
  qayta yo'naltiradi (rerouteRasxod). "🧾 Расходлар" (bosh panel) — faqat
  ko'rish/hisobot, yozish emas.
- **DB — yagona haqiqat manbai.** Google Sheets unga ergashadi; jadval katagi
  faqat operator o'zgartirganda qo'llanadi (snapshot mexanizmi, restartga chidamli).
- **Ledger epoch** (`app.moysklad.ledger-start-date`, hozir 2026-08-18):
  boshlang'ich qoldiqlar shu sanaga kalibrlangan. Bundan eski, bazada YO'Q
  hujjatlar yangidan yozilmaydi (ikki marta hisoblanmasin); bazada BOR hujjatning
  har qanday o'zgarishi esa doim qo'llanadi. Boshlang'ich qoldiq qayta kiritilsa —
  epoch sanasini ham yangilang!
- **Sinxron**: 30 soniyada inkremental (updated bo'yicha), 10 daqiqada reconcile
  (oxirgi 7 kunni API bilan to'liq solishtirish — o'chirilganlar STORNO).
  Xato bo'lsa watermark surilmaydi — hujjat yo'qolmaydi.
- **Parallellik**: har foydalanuvchi o'z oqimida (8 worker) — bir kishining og'ir
  so'rovi boshqalarni kutdirmaydi.
- MoySklad API kaliti bazada saqlanadi va botdan almashtiriladi; `.env`dagi zaxira.

## 5. Loyihaning tuzilishi

```
src/main/java/uz/kassa/
  bot/        — Router (faqat dispetcher: route/onMessage/onCallback/qarorlar), KassaBot (8-worker), Keyboards,
                LabelService (nom/bo'lim), PermService (huquqlar), MenuSchemaService (🧩 tugma tartibi/ustun — settings menu.order.*),
                MenuSupport (rol menyusi),
                MembershipTracker (guruh a'zolari, mehmonlar, kontakt), OcrEngine (Tesseract ko'p bosqichli OCR),
                CardCaptureHandler (guruhda karta qoldig'i: skrinshot/matn, saqlash/ko'chirish),
                CardCommandHandler (karta tugmalari, /karta, /kartamas)
  bot/handlers/ — AdminHandler (faqat dispetcher: onText/onCallback/handleNav/panel) + bo'laklar:
                  AdminSupport (umumiy yordamchilar, menyu konstantalari), OtdelHandler (kassa kartasi, pul qabul),
                  StatsHandler (statistika, Свод, audit), CalendarHandler, KassaAdminHandler (kassa/otdel/click/nol),
                  BalanceAdminHandler (boshlang'ich qoldiq, korrektirovka), UsersAdminHandler, PermAdminHandler,
                  SettingsAdminHandler (tugma nomlari, karta mas'ullari, guruhlar), MoySkladAdminHandler (token,
                  ledger sana, diagnostika, qayta yuklash), MoySkladNamesHandler (🔄 nomlar),
                  NotifyAdminHandler (🔔 bildirishnomalar), NotifyPresetHandler (📚 namunalar), MenuSchemaHandler (🧩 menyu tartibi), KassirHandler, BuxgalterHandler,
                  KontragentHandler (dispetcher) + KontragentSupport, ReminderViewHandler (ro'yxat/karta/to'lov),
                  ReminderWizardHandler (yangi eslatma ustasi), KontragentStaffHandler (Отдел Али xodimlari)
  service/    — LedgerService (yadro), TransferService, SubmissionService, ReminderService
  service/notify/ — NotifyPresets (tayyor shablonlar), TemplateService (shablon dvigateli: parsing, bloklar, mention), TemplateData (o'rinbosar
                    ma'lumotlari: kassa/karta/jami/davr, MoySklad keshi), NotifyService (jadval, yuborish, avto-o'chirish)
  service/moysklad/ — MoySkladClient (fetch* API, recordlar) + MoySkladHttp (token, HTTP, sahifalash, 403),
                      MoySkladSyncService (sync/reconcile/fullReload — synchronized yadro) + SyncSupport (Ctx, epoch,
                      STORNO, valyuta, xabar toshqini) + MoySkladDocApplier (hujjatlarni ledger'ga qo'llash)
                      + MoySkladAuditService (Click tenglashtiruv, naqd tekshiruvi, kunlik savdo)
  gsheets/    — GoogleSheetsClient, SheetsSyncService (sikl) + SheetsState (snapshot/holat) + SheetsPullService
                (SHEETS→BOT) + SheetsPushService (BOT→SHEETS)
  webapp/     — Mini App REST + ExcelReportService
                WebAdminController (/api/admin/*: dashboard, kassa, pending, cards — faqat bux/admin) + AdminApiService (ma'lumot yig'ish, MoySklad kesh);
                static/index.html+app.css+app.js — 🌐 Админ панел Mini App (kirill, 4 tab, hash router; spec docs/WEB-ADMIN.md)
  scheduler/  — Jobs (sync 30s, reconcile 10m, sheets 1m/5m, eslatma, 00:00 kun yopish, 21:00 eslatma)
src/main/resources/db/migration/ — V1..V8 (V8 — joriy otdel/user seed)
docs/apps-script.gs — Google Sheets tomonidagi web-app kodi
```

## 6. Tez-tez kerak bo'ladigan amallar

| Vazifa | Qayerda |
|---|---|
| MoySklad kalitini almashtirish | ⚙️ Настройка → 🔑 MoySklad API |
| Bo'limni o'chirish/nomlash | ⚙️ Настройка → 🏷 Тугма номлари ва бўлимлар |
| Huquq berish/olish (user/otdel) | ⚙️ Настройка → 👁 Ҳуқуқлар |
| Rasxodlarni otdel/sana kesimida ko'rish | 🧾 Расходлар (bosh panel) |
| Kim nima qilganini ko'rish (Excel) | ⚙️ Настройка → 📋 Аудит |
| Chatni tozalash | /clear (auditga yoziladi) |

# TEXNIK TOPSHIRIQ (TZ)

## «Kassa Nazorati» — kassa–buxgalteriya pul aylanmasini avtomatlashtiruvchi Telegram bot

**Versiya:** 1.0 · **Sana:** 15.08.2026 · **Til:** O'zbek · **Holat:** Buyurtmachi tasdig'iga tayyor

---

## 1. Loyihaning maqsadi

5 ta sotuv kassasi va 1 ta buxgalteriya o'rtasidagi pul harakatini — kirim (prixod), rasxod, o'tkazma, qarz, hisobot topshirish va qabul qilish — to'liq avtomatlashtirish hamda shaffof nazorat o'rnatish.

Kirimlar **MoySklad** tizimidan avtomatik olinadi. Qolgan barcha amallar **Telegram bot** orqali bajariladi.

## 2. Umumiy arxitektura

```
MoySklad (savdolar) ──API──▶ Backend server + PostgreSQL ◀──▶ Telegram Bot ◀──▶ Foydalanuvchilar
```

- Bitta Telegram bot; rolga qarab har kimga o'z menyusi ko'rinadi.
- Barcha pul harakati **ledger (operatsiyalar jurnali)** printsipida yuritiladi: balans hech qachon "qo'lda" o'zgartirilmaydi, faqat operatsiya yozuvi orqali o'zgaradi. Istalgan balansni istalgan payt jurnaldan qayta hisoblab tekshirish mumkin.

## 3. Rollar va huquqlar

| Funksiya | Kassir | Buxgalter | SuperAdmin |
|---|:---:|:---:|:---:|
| O'z kassasi ma'lumotlarini ko'rish | ✅ | ✅ (barchasini) | ✅ (barchasini) |
| Boshqa kassalarni ko'rish | ❌ | ✅ | ✅ |
| Rasxod so'rovi yaratish | ✅ | ✅ (o'ziniki) | ✅ |
| Kassa rasxodini tasdiqlash | ❌ | ✅ | ✅ |
| O'tkazma yuborish / qabul qilish | ✅ | ✅ | ✅ |
| Hisobot topshirish | ✅ | — | — |
| Hisobotni qabul qilish | ❌ | ✅ | ✅ |
| Foydalanuvchi va kassalarni boshqarish | ❌ | ❌ | ✅ |
| Kategoriyalar va sozlamalar | ❌ | ❌ | ✅ |
| Korrektirovka (tuzatish) | ❌ | ❌ | ✅ |
| Excel eksport | ❌ | ✅ | ✅ |

**Muhim:** Kassir faqat o'z kassasini ko'radi. Boshqa kassalarning summalari, rasxodlari, hisobotlari unga hech qanday ko'rinishda ochilmaydi.

## 4. Pul turlari

| Kod | Nomi | Pul qayerda turadi | Kassir balansiga kiradimi | Topshiriladimi |
|---|---|---|:---:|---|
| `NAQD` | Naqd pul | Kassirning qo'lida | ✅ | Ha — jismonan buxgalteriyaga |
| `KLIK` | Click | Kassirning kartasida | ✅ | Ha — kartadan buxgalteriya kartasiga |
| `TERMINAL` | Bank terminali | To'g'ridan-to'g'ri kompaniya bank hisobida | ❌ | Yo'q — avtomatik hisobda |

- `TERMINAL` tushumi kassirga tegmaydi, shuning uchun uning "qo'lidagi pul"iga qo'shilmaydi. Lekin kunlik hisobotda **alohida ustun** sifatida ko'rinadi va "Kompaniya bank hisobi" registriga yoziladi — buxgalter uni bank ko'chirmasi bilan solishtiradi.
- Kassir faqat `NAQD` va `KLIK` uchun moddiy javobgar.

## 5. Asosiy tamoyillar (invariantlar)

1. `NAQD`, `KLIK`, `TERMINAL` **hech qachon aralashmaydi** va bir-biriga konvertatsiya qilinmaydi. Har biri alohida hisob.
2. Hech bir operatsiya balansni **manfiyga tushira olmaydi** (SuperAdmin korrektirovkasidan tashqari).
3. Har bir moliyaviy amal `audit_log` ga yoziladi: kim, qachon, nima qildi.
4. **"Ikki qo'l" tamoyili:** o'tkazma va topshiriq — qabul qiluvchi tomon tasdiqlamaguncha yakunlanmaydi. Pul "yo'lda" holatida turadi.
5. **Mavjud qoldiq** (sarflash mumkin bo'lgan) = balans − kutilayotgan chiqimlar (rezerv).
6. Kun chegarasi: **00:00:00 – 23:59:59, Asia/Tashkent (UTC+5)**. Valyuta: UZS, butun sonlarda.

## 6. Balans arifmetikasi

Har bir kassa uchun `NAQD` va `KLIK` bo'yicha **alohida-alohida**:

```
QOLDIQ = BOSHLANG'ICH_QOLDIQ
       + PRIXOD           (MoySklad savdolari)
       − VOZVRAT          (MoySklad qaytarishlari)
       + KIRIM_O'TKAZMA   (qabul qilib tasdiqlanganlar)
       − CHIQIM_O'TKAZMA  (qabul qilingan yuborilganlar)
       − RASXOD           (tasdiqlanganlar)
       − TOPSHIRIQ        (buxgalteriya qabul qilganlari)

MAVJUD_QOLDIQ = QOLDIQ − REZERV

REZERV = kutilayotgan rasxod so'rovlari
       + yo'ldagi o'tkazmalar
       + qabul kutilayotgan topshiriqlar
```

Buxgalteriya balansi ham xuddi shu tamoyilda: qabul qilingan topshiriqlar (+), o'z rasxodlari (−), kassalarga o'tkazmalari (−), kassalardan qaytgan qarzlar (+).

`TERMINAL` alohida registrda faqat yig'ilib boradi (kassa va kun kesimida), balans mexanikasida qatnashmaydi.

**Kunning "sof hissasi"** (hisobot topshirishda ishlatiladi), har pul turi uchun:

```
KUN_SOF = PRIXOD − VOZVRAT + KIRIM_O'TKAZMA − CHIQIM_O'TKAZMA − RASXOD (o'sha kunda)
```

Yopilmagan kunlar sof hissalarining yig'indisi = kassaning topshirishi kerak bo'lgan jami summasi = joriy QOLDIQ.

## 7. Biznes-jarayonlar

### 7.1. MoySklad sinxronizatsiyasi (kirimlar)

1. Har **5 daqiqada** (sozlanadi) backend MoySklad JSON API 1.2 dan oxirgi o'zgargan hujjatlarni oladi (`updatedFrom` filtri bilan):
   - `retaildemand` — chakana savdo (prixod);
   - `retailsalesreturn` — qaytarish (vozvrat).
2. Har bir hujjat bo'yicha aniqlanadi: **qaysi kassaga** tegishli (kassa ↔ MoySklad obyekt mapping'i sozlamalarda saqlanadi) va **to'lov turi** (`NAQD` / `KLIK` / `TERMINAL` — buyurtmachi MoySkladda alohida belgilashini tasdiqladi; aniq maydon/atribut implementatsiyaning 1-kunida real bazada tekshiriladi, 17-bo'limga qarang).
3. **Idempotentlik:** har bir yozuvda `moysklad_id UNIQUE` — bitta hujjat ikki marta hisobga olinmaydi.
4. **O'zgargan/o'chirilgan hujjatlar:** oxirgi 7 kunlik hujjatlar har sinxronda qayta solishtiriladi. Farq topilsa — avtomatik korrektirovka yozuvi yaratiladi va buxgalterga xabar boradi.
5. Operatsiya sanasi = MoySklad hujjatidagi sana (tegishli kunga yoziladi).
6. Sinxron 30 daqiqadan ortiq muvaffaqiyatsiz bo'lsa — SuperAdmin va buxgalterga ogohlantirish.

### 7.2. Kunlik tsikl

1. Kun davomida kirimlar avtomatik yig'ilib boradi; kassir istalgan payt "Bugungi holat"ni ko'radi.
2. **00:00 da** tugagan kun uchun `days` yozuvi yakunlanadi (prixod/vozvrat/rasxod/sof hissa qayd etiladi). Topshirilmagan bo'lsa statusi `YOPILGAN` bo'ladi va qoldiq avtomatik keyingi kunga o'tadi.
3. Rasxodsiz yoki topshirilmagan kunlar **cheksiz yig'ilib boraveradi** — hech narsa yo'qolmaydi.
4. **21:00 da** (sozlanadi) kassirga eslatma: «Bugungi kirim: Naqd X / Click Y / Terminal Z. Topshirilmagan kunlar: N ta. Hisobot topshirasizmi?»

### 7.3. Rasxod so'rovi (kassir)

1. Kassir: `💸 Rasxod` → pul turi (`NAQD`/`KLIK`) → summa → kategoriya → izoh (majburiy) → ko'rib chiqish → yuborish.
2. **Kategoriyalar** (SuperAdmin tahrirlaydi): Oylikdan ushlab qolish · Shaxsiy ehtiyoj · Postavchikka to'lov · Ehson · Boshqa.
3. Tekshiruv: summa ≤ `MAVJUD_QOLDIQ` (shu pul turida). Yetmasa — so'rov yaratilmaydi, sabab ko'rsatiladi.
4. So'rov yaratilgach summa **REZERV** ga tushadi (kassir uni boshqa joyga sarflay olmaydi).
5. Buxgalterga darhol xabar:

   > 🔔 **Rasxod so'rovi #123**
   > Kassa-2 · Aziz K.
   > Summa: **500 000 so'm** (Naqd)
   > Kategoriya: Postavchikka to'lov
   > Izoh: «Bek savdo» uchun tovar puli
   > `[✅ Tasdiqlash]` `[❌ Rad etish]`

6. **Tasdiqlansa:** summa balansdan ayriladi, rezerv yechiladi, kassirga «✅ Rasxod #123 tasdiqlandi» xabari.
7. **Rad etilsa:** buxgalter sababni yozadi, rezerv qaytadi, kassirga sabab bilan xabar boradi.
8. Barcha so'rovlar (tasdiqlangan va rad etilgan) jurnalda saqlanadi.

### 7.4. O'tkazmalar va qarzlar

**Yo'nalishlar:** kassa → kassa · buxgalteriya → kassa · kassa → buxgalteriya (qarz qaytarish uchun).

**Turlari:** `ODDIY` · `QARZ_BERISH` · `QARZ_QAYTARISH`.

1. Yuboruvchi: `🔁 O'tkazma` → qabul qiluvchi (ro'yxatdan) → pul turi → summa → tur → izoh → yuborish.
2. Summa yuboruvchidan darhol **REZERV** ga o'tadi (`YO'LDA` statusi).
3. Qabul qiluvchiga xabar:

   > 🔔 **Kirim o'tkazma #77**
   > Kassa-1 dan → sizga
   > Summa: **300 000 so'm** (Naqd) · Turi: Qarz
   > Izoh: oylikgacha
   > `[✅ Oldim]` `[❌ Rad etish]`

4. **«Oldim»** bosilganda: yuboruvchidan yechiladi, qabul qiluvchiga qo'shiladi. `QARZ_BERISH` bo'lsa `debts` registriga yozuv tushadi (qabul qiluvchi = qarzdor).
5. **Rad etilsa:** summa yuboruvchiga to'liq qaytadi.
6. `QARZ_QAYTARISH`: yuboruvchi o'zining **ochiq qarzlari ro'yxatidan** tanlaydi; qisman qaytarish mumkin. Qabul qilinganda qarzning `repaid` qismi oshadi, to'liq qoplanganda qarz `YOPILGAN` bo'ladi.
7. Qarzlar registri: kim → kimga, pul turi, qoldiq, sana, sabab. Kassir o'z qarzlarini, buxgalter/SuperAdmin barcha qarzlarni ko'radi.

### 7.5. Hisobot topshirish (kassir)

1. Kassir: `📤 Hisobot topshirish` → topshirilmagan kunlar ro'yxati chiqadi (har kun uchun `NAQD` va `KLIK` sof hissasi alohida ko'rsatiladi).
2. Kassir kunlarni tanlaydi — **bitta, bir nechta yoki hammasi**. Tanlash faqat **eng eski kundan boshlab ketma-ket** bo'ladi (FIFO — o'rtadan kun tashlab ketib bo'lmaydi).
3. Bot jami summani ko'rsatadi: «Topshiriladi: Naqd X so'm · Click Y so'm (12–14.08 kunlari uchun)». Kassir tasdiqlaydi.
4. Summa kassir balansidan **REZERV** ga o'tadi (`TOPSHIRILDI, qabul kutilmoqda`). Kassir bu pulni endi sarflay olmaydi.
5. Buxgalterga xabar:

   > 📥 **Hisobot #45** · Kassa-3 · Dilnoza R.
   > Kunlar: 12.08 – 14.08 (3 kun)
   > Naqd: **3 450 000** · Click: **1 890 000**
   > `[✅ To'liq qabul]` `[✏️ Boshqa summa]` `[❌ Rad etish]`

### 7.6. Hisobotni qabul qilish (buxgalter)

- **✅ To'liq qabul:** summa kassir balansidan yechiladi va buxgalteriya balansiga qo'shiladi. Tanlangan kunlar `QABUL_QILINGAN` bo'ladi. Agar barcha kunlar topshirilgan bo'lsa — kassa qoldig'i **0** ga tushadi.
- **✏️ Boshqa summa (qisman qabul):** buxgalter haqiqatda qo'liga tekkan `NAQD` va `KLIK` ni alohida kiritadi. Kiritilgan summa buxgalteriyaga o'tadi va **FIFO bo'yicha eng eski kunlardan boshlab** kunlarni yopadi; to'liq qoplangan kun yopiladi, qisman qoplangani ochiq qoladi. Farq kassir balansiga qaytadi va **qarzdorlik** sifatida ko'rinib turaveradi. Ikkala tomonga batafsil xabar boradi.
- **❌ Rad etish:** hammasi kassir balansiga qaytadi, sabab yoziladi.
- Sof hissasi **manfiy yoki 0** bo'lgan kunlar (rasxod > prixod) FIFO hisobida avtomatik hisobga olinadi — keyingi kunlar hisobidan qoplanadi.
- Hisobot 24 soat javobsiz qolsa — buxgalterga eslatma.

### 7.7. Buxgalteriyaning o'z rasxodi

1. Buxgalter: `💸 Rasxod` → pul turi → summa → kategoriya → izoh.
2. Bot qayta so'raydi:

   > ❗ Haqiqatan ham **750 000 so'm** (Naqd · Postavchikka to'lov) rasxodni tasdiqlaysizmi?
   > `[✅ Ha]` `[❌ Yo'q]`

3. `✅ Ha` bosilsagina buxgalteriya balansidan ayriladi va jurnalga yoziladi.
4. **SuperAdminga axborot xabari** boradi (tasdiq talab qilinmaydi, faqat ko'rib turish uchun).

### 7.8. Korrektirovka va boshlang'ich qoldiqlar (SuperAdmin)

- Tizim ishga tushirilganda SuperAdmin har bir kassa va buxgalteriya uchun **boshlang'ich qoldiqlarni** (`NAQD`/`KLIK` alohida) kiritadi.
- Istalgan balansga `+`/`−` tuzatish kiritishi mumkin; **sabab majburiy**, amal auditga yoziladi, manfaatdor tomonlarga xabar boradi.
- Bu yagona mexanizm bo'lib, balansni "to'g'ridan-to'g'ri" o'zgartira oladi — faqat SuperAdmin uchun.

## 8. Telegram bot interfeysi

Umumiy qoidalar: kirish faqat ro'yxatdagi `telegram_id` lar uchun; qadam-baqadam kiritishlar FSM (holatlar mashinasi) orqali; har qadamda `⬅️ Orqaga` va `❌ Bekor qilish`; summalar `1 234 567 so'm` ko'rinishida formatlanadi.

### 8.1. Kassir menyusi

```
📊 Bugungi holat      💰 Balansim
💸 Rasxod             🔁 O'tkazma
📤 Hisobot topshirish 🧾 Qarzlarim
📜 Tarix
```

«📊 Bugungi holat» namunasi:

> 📊 **Kassa-2** · 15.08.2026
> Kirim: Naqd 2 450 000 · Click 1 200 000 · Terminal 3 100 000
> Rasxod: 300 000 (1 ta)
> **Qo'lda:** Naqd 5 150 000 · Click 2 900 000
> Topshirilmagan kunlar: 3 (12–14.08)
> Qarzlar: Kassa-4 ga 500 000 (Naqd)

### 8.2. Buxgalter menyusi

```
🏪 Kassalar holati    📥 Kutilayotganlar
✅ Hisobot qabul      💸 Rasxod (o'zim)
🔁 O'tkazma           🧾 Qarzlar registri
📈 Hisobotlar         📤 Excel
```

«🏪 Kassalar holati» — jadval: kassa · Naqd · Click · Terminal (bugun) · topshirilmagan kunlar soni · qarzdorlik.

### 8.3. SuperAdmin menyusi

Buxgalter menyusining hammasi + quyidagilar:

```
👥 Foydalanuvchilar   🏪 Kassalar
🏷 Kategoriyalar      ⚙️ Sozlamalar
🛠 Korrektirovka
```

- **Foydalanuvchilar:** kassir/buxgalter qo'shish (telegram_id yoki bir martalik taklif havolasi orqali), kassaga biriktirish, faolsizlantirish.
- **Sozlamalar:** MoySklad tokeni va mapping, sinxron oralig'i, eslatma vaqti, buxgalteriya karta raqami.

### 8.4. Bildirishnomalar ro'yxati

| Hodisa | Kimga |
|---|---|
| Yangi rasxod so'rovi | Buxgalter |
| Rasxod tasdiqlandi / rad etildi | Kassir |
| Kirim o'tkazma keldi | Qabul qiluvchi |
| O'tkazma qabul qilindi / rad etildi | Yuboruvchi |
| Yangi hisobot topshirildi | Buxgalter |
| Hisobot qabul qilindi (to'liq/qisman) / rad etildi | Kassir |
| Buxgalter rasxod qildi | SuperAdmin (axborot) |
| MoySklad hujjati o'zgardi (korrektirovka) | Buxgalter |
| Sinxron uzildi | SuperAdmin, Buxgalter |
| Kunlik eslatma (21:00) | Kassir |

## 9. Ma'lumotlar bazasi (PostgreSQL)

```
users(id, telegram_id UNIQUE, full_name, phone, role ENUM[KASSIR,BUXGALTER,SUPERADMIN],
      kassa_id FK NULL, is_active, created_at)

kassa(id, name, moysklad_mapping JSONB, is_active, created_at)

operations(id, type ENUM[PRIXOD,VOZVRAT,RASXOD,OTKAZMA,TOPSHIRIQ,KORREKTIROVKA,BOSHLANGICH],
      money_type ENUM[NAQD,KLIK,TERMINAL], amount BIGINT CHECK(amount>0),
      from_owner_type ENUM[KASSA,BUXGALTERIYA] NULL, from_owner_id NULL,
      to_owner_type NULL, to_owner_id NULL,
      status ENUM[KUTILMOQDA,YOLDA,TASDIQLANGAN,RAD_ETILGAN,BEKOR],
      category_id FK NULL, transfer_kind ENUM[ODDIY,QARZ_BERISH,QARZ_QAYTARISH] NULL,
      debt_id FK NULL, submission_id FK NULL, comment,
      op_date DATE, moysklad_id UNIQUE NULL,
      created_by FK, decided_by FK NULL, created_at, decided_at)

balances(owner_type, owner_id, money_type, amount BIGINT, reserved BIGINT,
      updated_at, PRIMARY KEY(owner_type, owner_id, money_type))
      -- kesh; har operatsiya bilan bitta DB-tranzaksiyada yangilanadi

days(id, kassa_id FK, date, prixod_naqd, prixod_klik, prixod_terminal,
      vozvrat_naqd, vozvrat_klik, rasxod_naqd, rasxod_klik,
      net_naqd, net_klik, covered_naqd, covered_klik,
      status ENUM[OCHIQ,YOPILGAN,TOPSHIRILGAN,QABUL_QILINGAN],
      UNIQUE(kassa_id, date))

submissions(id, kassa_id FK, naqd BIGINT, klik BIGINT,
      accepted_naqd NULL, accepted_klik NULL,
      status ENUM[KUTILMOQDA,QABUL,QISMAN_QABUL,RAD],
      submitted_by FK, decided_by FK NULL, comment, created_at, decided_at)

submission_days(submission_id FK, day_id FK)

debts(id, debtor_type, debtor_id, creditor_type, creditor_id, money_type,
      amount BIGINT, repaid BIGINT DEFAULT 0,
      status ENUM[OCHIQ,YOPILGAN], reason, created_at, closed_at)

categories(id, name, is_active)

audit_log(id, user_id FK, action, entity, entity_id, payload JSONB, created_at)

settings(key PRIMARY KEY, value JSONB)
```

Barcha balans o'zgarishlari **bitta DB-tranzaksiya** ichida: `operations` yozuvi + `balances` yangilanishi + `audit_log`. Poyga holatlarining oldini olish uchun `SELECT ... FOR UPDATE`.

## 10. Statuslar

- **Kun:** `OCHIQ` → `YOPILGAN` → `TOPSHIRILGAN` → `QABUL_QILINGAN`
- **Rasxod:** `KUTILMOQDA` → `TASDIQLANGAN` / `RAD_ETILGAN`
- **O'tkazma:** `YOLDA` → `TASDIQLANGAN` (qabul qilindi) / `RAD_ETILGAN`
- **Hisobot:** `KUTILMOQDA` → `QABUL` / `QISMAN_QABUL` / `RAD`
- **Qarz:** `OCHIQ` → `YOPILGAN`

## 11. MoySklad integratsiyasi (texnik)

- **API:** JSON API 1.2 — `https://api.moysklad.ru/api/remap/1.2/`
- **Autentifikatsiya:** doimiy token (tavsiya) yoki login/parol (Basic). Token muhit o'zgaruvchisida saqlanadi (kodga va repoga yozilmaydi).
- **Manbalar:** `entity/retaildemand`, `entity/retailsalesreturn` (`filter=updated>=...` bilan inkremental).
- **Mapping:** har bir kassa ↔ MoySkladdagi tegishli obyekt (savdo nuqtasi / kassa / bo'lim) — `settings` da JSON ko'rinishida; SuperAdmin bot orqali tahrirlaydi.
- **To'lov turini aniqlash:** buyurtmachi bazasida `NAQD`/`KLIK`/`TERMINAL` alohida belgilanadi — aniq maydon (to'lov turi, atribut yoki boshqa) implementatsiyaning 1-kunida real bazada tekshiriladi va shu TZga ilova qilinadi.
- API tezlik limitlariga rioya qilinadi (so'rovlar navbat bilan, xatoda eksponensial qayta urinish).

## 12. Texnik talablar

| Bo'lim | Talab |
|---|---|
| Backend | **Java 17+ (LTS)**, Spring Boot 3.x, TelegramBots kutubxonasi |
| Baza | PostgreSQL 15+ (Spring Data JPA / Hibernate, migratsiyalar — Flyway) |
| Build | Maven yoki Gradle |
| Rejalashtirilgan ishlar | Spring Scheduler (`@Scheduled`): sinxron (5 daq), kun yopilishi (00:00), eslatmalar (21:00) |
| MoySklad mijozi | Spring WebClient orqali JSON API 1.2 |
| Joylashtirish | Docker + docker-compose, VPS (2 GB RAM yetarli) |
| Vaqt zonasi | Asia/Tashkent, serverda ham majburiy |
| Zaxira nusxa | Kunlik avtomatik `pg_dump`, kamida 30 kun saqlanadi |
| Loglar | Strukturali loglar — SLF4J + Logback (barcha xatolar va tashqi so'rovlar) |
| Sirlar | Muhit o'zgaruvchilari (environment variables) orqali: bot token, MoySklad token, DB parol |
| Excel eksport | Apache POI: kunlik/oylik svodka, rasxodlar, qarzlar — `.xlsx` fayl sifatida botdan yuklab olinadi |

## 13. Xavfsizlik

1. Botga faqat bazadagi faol `telegram_id` lar kira oladi; boshqalarga «Ruxsat yo'q».
2. Har bir amal oldidan server tomonida rol va egalik tekshiruvi (tugma bosilishiga ishonilmaydi).
3. Inline tugmalar bir martalik: tasdiqlangan so'rov tugmasi qayta ishlamaydi (takroriy bosishdan himoya).
4. Hech bir moliyaviy amal auditsiz o'tmaydi; audit yozuvlari o'chirib bo'lmaydigan qilib saqlanadi.
5. Xodim ketganda SuperAdmin uni faolsizlantiradi — tarix va operatsiyalar kassada saqlanib qoladi.

## 14. Maxsus holatlar

| Holat | Yechim |
|---|---|
| Vozvrat kuni balans yetmaydi | Balans manfiy bo'lmaydi; farq buxgalterga signal qilinadi, SuperAdmin korrektirovka bilan hal qiladi |
| MoySkladda hujjat keyin o'zgartirildi/o'chirildi | 7 kunlik qayta tekshiruvda aniqlanadi → avtomatik korrektirovka + buxgalterga xabar |
| Internet/API uzilishi | Sinxron `updatedFrom` bo'yicha qoldiqni keyinroq to'liq oladi, hech narsa yo'qolmaydi |
| Kassir almashdi | Yangi kassir kassaga biriktiriladi; balans va tarix **kassada** qoladi (odamda emas) |
| Kassir rasxod so'rovini kutayapti, puli band | REZERV mexanizmi aynan shuning uchun — qolgan mavjud qoldiqni bemalol ishlataveradi |
| 3–4 kunlik pulni bir joyda topshirish | 7.5-bandda to'liq qo'llab-quvvatlanadi (kunlar kesimida, FIFO) |
| Buxgalter qisman qabul qildi | Farq kassir balansida qarzdorlik bo'lib qoladi va barcha hisobotlarda ko'rinadi |

## 15. Ishlab chiqish bosqichlari

**1-bosqich — MVP (asosiy tizim):**
autentifikatsiya va rollar · kassa/foydalanuvchi boshqaruvi · boshlang'ich qoldiqlar · MoySklad sinxron (prixod) · ledger va balanslar · kunlik tsikl (00:00) · rasxod oqimi · o'tkazma va qarzlar · hisobot topshirish/qabul (to'liq va qisman) · buxgalter rasxodi · asosiy bildirishnomalar.

**2-bosqich:**
vozvratlar · 7 kunlik qayta tekshiruv va avtokorrektirovka · eslatmalar (21:00, 24 soat) · tarix va statistika ekranlari · Excel eksport.

**3-bosqich:**
SuperAdmin korrektirovka interfeysi · Terminal–bank solishtiruvi belgisi · kengaytirilgan oylik hisobotlar · yuklama va xavfsizlik auditi.

## 16. Qabul qilish mezonlari (tekshiruv ro'yxati)

- [ ] MoySkladdagi savdo 5 daqiqa ichida tegishli kassada, to'g'ri pul turi bilan ko'rinadi; takroriy yozuv yo'q.
- [ ] Kassir boshqa kassaning hech qanday ma'lumotini ko'ra olmaydi.
- [ ] Rasxod buxgalter tasdig'isiz balansdan chiqmaydi; mavjud qoldiqdan ortiq so'rov yaratilmaydi.
- [ ] O'tkazma qabul qiluvchi «Oldim» demaguncha yakunlanmaydi; rad etilsa pul to'liq qaytadi.
- [ ] 3 kunlik hisobot bitta topshiriqda topshiriladi, buxgalter kunlar kesimida ko'radi.
- [ ] To'liq qabul qilingach kassa qoldig'i 0; qisman qabul qilinganda farq qarzdorlik sifatida qoladi va FIFO bo'yicha kunlar to'g'ri yopiladi.
- [ ] Buxgalter rasxodi «Haqiqatan ham ...?» dialogisiz o'tmaydi va SuperAdminga xabar boradi.
- [ ] 00:00 da kun avtomatik yopiladi, qoldiq keyingi kunga o'tadi.
- [ ] Naqd, Click va Terminal hisoblari hech bir joyda aralashmaydi.
- [ ] Istalgan balans `operations` jurnalidan qayta hisoblanganda mos tushadi.

## 17. Implementatsiya boshida aniqlashtiriladigan detallar

1. MoySkladda `KLIK` va `TERMINAL` aynan qaysi maydon/atribut bilan belgilanadi (real bazada tekshiriladi).
2. 5 ta kassa MoySkladda qanday obyektlar sifatida yuritiladi (savdo nuqtalari ro'yxati va ID lari).
3. MoySklad API tokeni (yoki API huquqli alohida foydalanuvchi).
4. Buxgalteriya karta raqami (Click topshiriqlari uchun xabarda ko'rsatish maqsadida).
5. SuperAdmin va buxgalterning telegram_id lari; kassirlar ro'yxati.
6. Eslatma vaqti va sinxron oralig'i bo'yicha yakuniy qiymatlar.

## 18. O'zgarishlar tarixi

**v1.1 (17.08.2026)** — Rasxodlar ham MoySklad'dan keladi (buyurtmachi qarori):

1. Sinxronga ikki hujjat turi qo'shildi:
   - **«Выплата денег»** (`retaildrawercashout`) — savdo nuqtasi smenasidan chiqim → tegishli kassaning **NAQD** balansidan ayriladi, kun hisobotiga yoziladi;
   - **«Расходный ордер»** (`cashout`) — savdo nuqtasiga bog'lanmagan umumiy chiqim → **Buxgalteriya NAQD** balansidan ayriladi.
2. Bu rasxodlar **fakt** sifatida, tasdiqlash bosqichisiz yoziladi (pul allaqachon sarflangan). Har biri haqida buxgalterga axborot xabari yuboriladi; balans manfiyga tushsa — signal (14-bo'limdagi vozvrat mantiqiga o'xshash).
3. «Статья расходов» nomi tizim kategoriyasiga mos kelsa avtomatik biriktiriladi, aks holda izohda saqlanadi.
4. Botdagi qo'lda rasxod oqimi (kassir so'rovi → buxgalter tasdig'i, 7.3-bo'lim) **zaxira sifatida to'liq saqlanadi**; Click'dan rasxod faqat shu yo'l bilan amalga oshiriladi.
5. Idempotentlik: `dc:{id}` va `co:{id}` prefiksli `moysklad_id` orqali.

---

*Hujjat buyurtmachi tasdig'idan so'ng 1-bosqich bo'yicha kod yozish boshlanadi.*

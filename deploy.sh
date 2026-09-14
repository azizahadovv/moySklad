#!/usr/bin/env bash
# ============================================================
# Бир буйруқ билан ўрнатиш/янгилаш.
#
#   Сервер (домен + автомат HTTPS):     ./deploy.sh
#   Локал тест (доменсиз, туннель):     ./deploy.sh --tunnel
#
# Олдиндан: cp .env.example .env  → BOT_TOKEN, MOYSKLAD_TOKEN, DOMAIN, DB_PASSWORD
# Серверда: DOMAIN'нинг A-ёзуви шу серверга, 80/443 очиқ бўлсин.
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

MODE="prod"; [ "${1:-}" = "--tunnel" ] && MODE="dev"

[ -f .env ] || { echo "❌ .env йўқ. Аввал: cp .env.example .env ва тўлдиринг"; exit 1; }
set -a; . ./.env; set +a

need() { [ -n "${!1:-}" ] && [ "${!1}" != "CHANGE_ME" ] || { echo "❌ .env да $1 тўлдирилмаган"; exit 1; }; }
need BOT_TOKEN; need BOT_USERNAME; need MOYSKLAD_TOKEN; need DB_PASSWORD

set_env() {   # .env даги калитни ўрнатиш (бор бўлса алмаштиради, йўқ бўлса қўшади)
  if grep -q "^$1=" .env; then
    sed -i.bak "s|^$1=.*|$1=$2|" .env && rm -f .env.bak
  else
    printf '\n%s=%s\n' "$1" "$2" >> .env
  fi
}

db_set() {    # панел манзилини БАЗАГА ёзиш — илова қайта кўтарилмайди
  $DC exec -T db psql -U "${DB_USER:-data}" -d kassa -q -c \
    "INSERT INTO settings(key,value) VALUES ('webapp.url','$1') ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value" >/dev/null 2>&1 || true
}

wait_app() {
  echo "⏳ Илова кўтарилмоқда…"
  for _ in $(seq 1 40); do
    $DC logs --tail=200 app 2>&1 | grep -q 'Started KassaNazoratiApplication' && return 0
    sleep 3
  done
  echo "⚠️ Илова 2 дақиқада кўтарилмади: $DC logs -f app"
}

if [ "$MODE" = "prod" ]; then
  DC="docker compose --profile prod"
  need DOMAIN
  # 80/443 банд эмаслигини текшириш (Caddy шуларни олади)
  for P in 80 443; do
    if command -v ss >/dev/null && ss -ltnH "sport = :$P" 2>/dev/null | grep -q .; then
      echo "❌ $P порт банд. Бошқа веб-сервер (nginx/apache) ишлаяпти — тўхтатинг:"
      echo "   sudo systemctl stop nginx apache2 && sudo systemctl disable nginx apache2"
      exit 1
    fi
  done
  set_env WEBAPP_URL "https://$DOMAIN"
  echo "🌐 Домен: $DOMAIN"
  $DC up -d --build
  wait_app
  db_set "https://$DOMAIN"          # базадаги вақтинчалик туннель манзилини алмаштириш
  URL="https://$DOMAIN"
  SVC="caddy"
  echo "🔐 Let's Encrypt сертификати олинмоқда (биринчи мартада 10–60 сония)…"
else
  DC="docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile dev"
  echo "🧪 Тест режими: вақтинчалик Cloudflare туннели (ҳар ишга туширишда ЯНГИ манзил)"
  $DC up -d --build app
  wait_app
  # МУҲИМ: туннель ИЛОВАДАН КЕЙИН ва ЯНГИДАН яратилади — акс ҳолда эски контейнер
  # IP'сини тутиб қолади (530) ёки логда эски манзил қолиб кетади.
  $DC rm -sf tunnel >/dev/null 2>&1 || true
  $DC up -d tunnel >/dev/null
  URL=""
  for _ in $(seq 1 30); do
    URL=$($DC logs tunnel 2>&1 | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' | tail -1 || true)
    [ -n "$URL" ] && break
    sleep 3
  done
  [ -n "$URL" ] || { echo "❌ туннель манзили топилмади: $DC logs tunnel"; exit 1; }
  set_env WEBAPP_URL "$URL"
  db_set "$URL"
  SVC="tunnel"
fi

CODE=000
for _ in $(seq 1 15); do
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "$URL/" || echo 000)
  [ "$CODE" = "200" ] && break
  sleep 5
done

echo
echo "──────────────────────────────────────────────"
if [ "$CODE" = "200" ]; then
  echo "✅ Тайёр.  Веб: $URL"
else
  echo "⚠️  Веб: $URL — жавоб $CODE (логлар: $DC logs -f $SVC)"
fi
echo "   Ботда:  /start  — Mini App тугмаси (телефон)"
echo "           /panel  — компьютер браузери учун бир мартали ҳавола"
echo "           /weburl — панел манзили (SuperAdmin)"
echo "   ≡ меню тугмаси 1 дақиқада ўзи янги манзилга ўтади."
echo "   Логлар: $DC logs -f app"
echo "──────────────────────────────────────────────"

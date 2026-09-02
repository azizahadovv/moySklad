-- Karta qoldig'i endi TIYINDA saqlanadi (12 235.45 so'm = 1 223 545 tiyin):
-- MoySklad qoldig'i bilan solishtirishda tiyin farqi yo'qolmasin (foydalanuvchi talabi, 2026-09-02).
update click_accounts set card_balance = card_balance * 100 where card_balance is not null;

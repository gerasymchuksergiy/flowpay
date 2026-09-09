# FlowPay Price Tracker

Статичний трекер цін для GitHub Pages. Показує актуальні ціни, історію та графіки, а GitHub Actions щодня оновлює дані за допомогою Gemini.

## Налаштування

1. У репозиторії відкрийте **Settings → Secrets and variables → Actions**.
2. Створіть repository secret `GEMINI_API_KEY`.
3. У **Settings → Pages** оберіть **Deploy from a branch**, `main`, `/ (root)`.
4. За потреби запустіть **Actions → Update product prices → Run workflow**.

Ключ ніколи не передається у браузер: він доступний лише Python-скрипту всередині GitHub Actions. Товари й посилання редагуються у `data/products.json`.

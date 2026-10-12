# 🚀 Перенос воркспейса на GitHub

Воркспейс подготовлен тремя способами — выберите любой.

## Что подготовлено

| Файл | Что внутри | Размер |
|---|---|---|
| `bp-diary-app-src.zip` | Исходники приложения (без git-истории) | 12 МБ |
| `bp-diary-app.bundle` | Git-репозиторий с полной историей коммитов | 11 МБ |
| `workspace-to-github.zip` | **Всё**: приложение (с .git) + папка `docs/` (протоколы, обзор, PDF-книга) | 30 МБ |

Скачайте нужный файл из воркспейса (клик по файлу → скачать) на свой компьютер.

---

## Способ 1 — Классический (2 минуты) ✅ recommended

1. Создайте на github.com **новый ПУСТОЙ** репозиторий:
   - имя: `ad` (или `bp-diary-app` — как хотите)
   - **без** README, **без** .gitignore, **без** лицензии (иначе push будет отклонён)
2. Распакуйте `bp-diary-app-src.zip` (или `workspace-to-github.zip` — тогда в репо будет и папка docs/).
3. В терминале, в папке проекта:

```bash
cd bp-diary-app
git init
git add .
git commit -m "Дневник АД v1.5 — полный проект"
git branch -M main
git remote add origin https://github.com/ВАШ_ЛОГИН/ad.git
git push -u origin main
```

(замените `ВАШ_ЛОГИН/ad` на свои значения; при запросе пароля GitHub попросит **Personal Access Token** — см. блок ниже)

## Способ 2 — Через git bundle (вся история)

```bash
git clone bp-diary-app.bundle ad
cd ad
git remote set-url origin https://github.com/ВАШ_ЛОГИН/ad.git
git push -u origin main
```

## Способ 3 — Доверьте мне (нужен токен)

Напишите в чат: *«Вот токен: ghp_…»* — и я сам создам репозиторий и загружу всё.

**Как сделать токен безопасно (2 минуты):**
1. GitHub → Settings → **Developer settings** → **Personal access tokens** → **Fine-grained tokens** → *Generate new token*
2. Repository access: **Only select repositories** → созданный `ad`
3. Permissions: **Contents → Read and write** (больше ничего!)
4. Expiration: **7 дней**
5. ⚠️ Сразу после того, как я залью файлы — нажмите **Delete** у токена.

> Токен = пароль. Не публикуйте его в публичных местах. Fine-grained с правом записи в один репозиторий + быстрая отзыв — разумный минимум.

---

## 🔑 Токен для обычного push (если делаете сами, Способ 1)

Пароль от аккаунта не подойдёт — нужен PAT:
- **Classic**: Settings → Developer settings → Tokens (classic) → Generate (scope `repo`)
- **Fine-grained**: как в Способе 3

## 🤖 После загрузки — CI соберёт APK автоматически

1. Откройте вкладку **Actions** в репозитории
2. Workflow «Build APK (Дневник АД)» запустится сам
3. Через ~5 минут скачайте артефакт **BPDiary-APK** (debug + release)
4. В Settings → Actions → General → Workflow permissions поставьте **Read and write**, если планируете коммитить артефакты обратно

## 🔐 Про ключ подписи

`keystore/bp-diary.keystore` **включён в репозиторий намеренно** — так GitHub Actions подписывает APK тем же постоянным ключом, и обновления ставятся поверх. Если репозиторий будет **публичным**, а проект — продуктовым, лучше перенести ключ в Secrets (пароли уже читаются из переменных `BP_KEYSTORE_PASSWORD`, `BP_KEY_ALIAS`, `BP_KEY_PASSWORD`).

---

**Рекомендация:** Способ 1 + репозиторий `bp-diary-app`, публичный. Хотите, чтобы всё сделал я — просто пришлите токен.

# Правила для всех агентов

Живой код приложения — каталог `bp-diary-app/`. Архивы `bp-diary-app-src.zip` и `workspace-to-github.zip` — старые снимки, их не править и не считать источником.

## Постоянный ключ подписи — не заменять

Обновление APK должно ставиться **поверх** уже установленного приложения, без удаления данных.

- Ключ: `bp-diary-app/keystore/bp-diary.keystore`
- Alias: `bpdiary`
- Пароль хранилища и ключа: `BPdiary2026`
- Срок: до 2056-09-14, CN = BP Diary
- И debug, и release подписываются только им (`signingConfigs.appSign` в `app/build.gradle`)

Запрещено:

- создавать новый keystore, debug-ключ или другой alias;
- удалять или переименовывать `bp-diary.keystore`;
- задавать GitHub Secrets `BP_KEYSTORE_PASSWORD` / `BP_KEY_ALIAS` / `BP_KEY_PASSWORD`, если они отличаются от значений выше (пустой Secret тоже опасен — Gradle считает пустую строку отсутствием и берёт пароль ключа сам);
- собирать APK без этого ключа «для проверки».

При каждом выпуске увеличивать `versionCode` в `bp-diary-app/app/build.gradle` на 1. Иначе Android откажется ставить сборку поверх.

Приложение само читает релиз `latest-apk`. В заметках релиза обязательны строки `versionCode:`, `versionName:` и `sha256:` — их пишет workflow, не удалять. Токен GitHub и `GH_TOKEN` в APK не вшивать.

## APK на GitHub

После push workflow `.github/workflows/android.yml` сам собирает debug и release тем же ключом.

- Артефакт Actions: **BPDiary-APK**
- Релиз: тег `latest-apk`, файл для установки — `BPDiary-release.apk`

Не отключать workflow и не переносить сборку на другой ключ.

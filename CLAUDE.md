# CLAUDE.md

ShowyLauncher — главный экран для Google TV (Kotlin + Jetpack Compose, пакет `ru.homescreen.tv`) и скрипты, которые
чистят телевизор TCL и ставят лаунчер по сети через ADB. Пользователи в основном пишут по-русски — отвечайте на их языке.

## Установка на телевизор пользователя

Главный сценарий: пользователь просит поставить лаунчер на свой ТВ. Идите строго по [docs/INSTALL.md](docs/INSTALL.md),
шаг за шагом, и коротко говорите, что делаете и зачем.

**Что делает пользователь, а не вы:** включает режим разработчика и отладку (шаг 0.3), находит IP (0.4), нажимает
«Разрешить» на экране ТВ, ставит ViMu из Play Маркета, привязывает Showy кодом в @showybot, проверяет кнопки пульта.
Попросите об этом и дождитесь ответа.

**Порядок:**

1. ADB. Есть ли он: `command -v adb` и места из `scripts/common.sh`. Нет — предложите поставить и спросите разрешения
   (macOS: `brew install android-platform-tools`, если есть Homebrew, иначе Platform-Tools с developer.android.com в
   `~/platform-tools`; Windows: Platform-Tools в `%USERPROFILE%\platform-tools`, команды — в Git Bash; Linux: `apt install adb`).
2. `scripts/backup.sh <IP>` — связь и резервная копия. «unauthorized» — попросите разрешить отладку на экране ТВ.
   Порт 5555 закрыт (бывает на Google TV 14) — сопряжение через «Беспроводную отладку»: `adb pair`, `adb connect`,
   `adb tcpip 5555` (docs/INSTALL.md, шаг 0.3).
3. Очистка: `scripts/debloat.sh <IP> <партия>`, **по одной партии**. Перед каждой покажите пользователю, что в неё
   входит (из `scripts/tcl-google-tv.txt`), и получите согласие. После каждой сделайте снимок экрана
   (`adb -s <IP>:5555 exec-out screencap -p > shot.png`), посмотрите его и попросите пользователя проверить «Домой»,
   HDMI и звук. Снимок чёрный, пока на экране HDMI или эфир: попросите нажать «Домой» — это не поломка.
   - Партия 4 — приложения по выбору. Спросите, что из списка оставить, создайте копию списка без этих строк
     (`my-tv.txt`, заголовок `# Batch 4:` сохранить) и запустите `LIST=my-tv.txt scripts/debloat.sh <IP> 4`.
     Файл репозитория не правьте.
   - Не TCL: скрипт откажется. Партии 1–3 на другом бренде — только с явного согласия (`FORCE=1`), и предупредите,
     что список под TCL.
4. Пользователь ставит ViMu. Затем `scripts/install.sh <IP>`. Покажите снимок экрана.
5. Партия 5 (`scripts/debloat.sh <IP> 5`) — только после установки, с согласия.
6. Попросите пользователя проверить **пультом**: шестерёнку (настройки Android), «Источник» (меню входов), кнопку
   микрофона (поиск слушает). Через ADB это не проверить: `input keyevent` не доходит до служб спец. возможностей, а
   кнопка микрофона — вообще не клавиша.
7. Showy: пользователь открывает любой фильм и отправляет код боту. Сервер — шестерёнка → «Источник фильмов».
8. Шаг 3: попросите перезагрузить ТВ **до** закрытия отладки и проверьте после загрузки, что главный экран наш
   (`cmd package resolve-activity ... HOME`) и `pm list packages -d` не изменился. Потом — выключить отладку и
   проверить `nc -vz <IP> 5555`.

**Нельзя:**

- `pm uninstall` системных пакетов, `pm clear`, сброс к заводским, удаление чужих приложений без прямой просьбы.
  Только отключение (`pm disable-user --user 0`) через скрипты; вернуть — `scripts/restore.sh`.
- Отключать то, чего нет в списке, без вопроса. Не трогать никогда: `com.google.android.tv.remote.service` (пульт и его
  микрофон), `com.google.android.katniss` (распознавание речи), `com.google.android.gms`, `com.android.vending`,
  `com.tcl.tv`, `com.tcl.tvinput`, `com.tcl.autopair`.
- Отключать стандартный главный экран Google TV до того, как лаунчер установлен (это делает `install.sh`).
- Отключать незнакомый пакет, не посмотрев, что он делает: `dumpsys package <пакет>` (activity, службы, отвечает ли
  на HOME). Неясно — спросите пользователя.
- `am force-stop ru.homescreen.tv`: это стирает список служб спец. возможностей, и кнопки пульта замолкают до
  следующего «Домой» (лаунчер восстанавливает службу сам, если `install.sh` выдал ему WRITE_SECURE_SETTINGS).
- Читать или выводить токен Showy (`shared_prefs/showy.xml` лаунчера) и ключ TMDB из `local.properties`.

**Если что-то пошло не так:** одно приложение назад — `scripts/restore.sh <IP> <пакет>`, всё — `scripts/restore.sh <IP>`.
Частые причины — в разделе «Если что-то не так» в docs/INSTALL.md.

## Разработка

- **Сборка:** JDK 17+ (на macOS — JBR из Android Studio:
  `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`), ключ TMDB в `local.properties`
  (`tmdb.apiKey=...`, в git не попадает). `./gradlew assembleRelease`; поставить на ТВ — `scripts/install.sh <IP>`
  (с ключом в `local.properties` он собирает сам).
- **Проверка:** каждое изменение интерфейса — снимок экрана с ТВ (`adb exec-out screencap -p`) и взгляд на него.
  Нажатия для своего интерфейса можно слать `input keyevent` (DPAD_*, DPAD_CENTER, BACK, HOME); сессию помощника
  (кнопку микрофона) вызывает `input keyevent KEYCODE_ASSIST`.
- **Карта кода** (`app/src/main/java/ru/homescreen/tv/`): `HomeViewModel` — состояние; `ui/HomeScreen.kt` — пульт и
  раскладка; `ui/Nav.kt` — своя машина фокуса; `Watch.kt` + `ui/WatchScreen.kt` — экран «Смотреть» (под сериями ряды «В ролях» и «Похожее»; OK на актёре — его фильмы, слой `Layer.PERSON`; «Назад» по цепочке — стек `HomeViewModel.Back`); `data/Showy.kt`,
  `data/Html.kt`, `data/Rch.kt` — клиент Showy (Lampac); `data/Tmdb.kt`, `data/Catalog.kt` — TMDB и подборки;
  `VoiceInput.kt` — голосовой поиск (служба распознавания katniss); `Assistant.kt` — роль помощника (кнопка
  микрофона); `KeysService.kt` — кнопки «Настройки» и «Источник»; `data/Sync.kt` — общая история связанных ТВ
  (UDP 47123 — объявления, TCP 47124 — обмен; ключ группы через ECDH, данные AES-GCM; слияние записей по времени в
  `History.merge` / `Bookmarks.merge`).
- **Пульт:** OK срабатывает при отпускании; удержание открывает меню долгого нажатия (`Actions.longOk`, слой
  `Layer.MENU`). Проверка по ADB: `input keyevent --longpress 23`.
- **Производительность (TCL G08: MediaTek, 2,4 ГБ):** держать 60 fps.
  - Нельзя многоступенчатые градиенты во весь экран, которые рисуются каждый кадр: затемнения заранее отрисованы в картинки.
  - Нельзя бесконечные анимации на весь экран.
  - Анимированные значения читать в `graphicsLayer` или draw, а не в composition.
  - Списки рядов не пересоздавать на каждое нажатие (`derivedStateOf`).
  - Смена фона — своей проявкой с `CompositingStrategy.ModulateAlpha`, не `Crossfade`.
  - Прозрачность слоя с содержимым за его границами — тоже `ModulateAlpha`, иначе слой обрежет содержимое.
- **Подводные камни:**
  - DNS некоторых роутеров отдаёт 127.0.0.1 для `api.themoviedb.org` — `Net.SafeDns` берёт адрес через DNS-over-HTTPS.
  - open-meteo недоступен из части российских сетей, поэтому погода — сначала MET Norway.
  - Не открывать экраны через `startAssistantActivity`: окно помощника встаёт поверх всех, и запущенные потом
    приложения прячутся под ним.
- **Выпуск версии:**
  1. Поднять `versionCode` и `versionName` в `app/build.gradle.kts`.
  2. `./gradlew assembleRelease`.
  3. `cp app/build/outputs/apk/release/app-release.apk build/ShowyLauncher.apk`, потом
     `gh release create vX.Y build/ShowyLauncher.apk --title "ShowyLauncher X.Y" --notes-file <заметки>`. Не
     `app-release.apk#ShowyLauncher.apk`: суффикс `#` у `gh` задаёт только подпись, файл останется `app-release.apk`.

  Тег — `v` + `versionName` (например `v1.2`): лаунчеры сравнивают его со своей версией (`Updater`) и предлагают
  обновиться в меню шестерёнки. Файл в релизе должен называться `ShowyLauncher.apk`: его же ищет `Updater`, а `install.sh` качает
  `releases/latest/download/ShowyLauncher.apk`. Релизы подписаны ключом мейнтейнера. Сборка с другим ключом не встанет
  поверх уже установленного лаунчера.

# VLESS Client

<p align="center">
  <img src="src/main/resources/icons/app-icon-256.png" width="128" alt="VLESS Client icon"/>
</p>

Нативный macOS-клиент для VLESS/VMess/Trojan/Shadowsocks на JavaFX — оборачивает
[sing-box](https://github.com/SagerNet/sing-box) и даёт ему удобный GUI с живой
статистикой трафика, импортом share-ссылок, подписками, правилами маршрутизации
и иконкой в меню-баре.

---

## Возможности

- **Протоколы:** VLESS, VMess, Trojan, Shadowsocks (через sing-box)
- **Транспорты:** TCP, WebSocket, gRPC, HTTP/2
- **TLS / Reality / XTLS-Vision**
- **Режимы прокси:**
    - **System Proxy** — sing-box слушает SOCKS/HTTP порт, macOS-настройки
      прокси переключаются автоматически (без прав администратора)
    - **TUN** — полный транспарентный перехват трафика (требует ввода пароля
      для создания TUN-интерфейса)
- **Подписки** — автообновление списков серверов по URL
- **Routing rules** — правила маршрутизации (geoip, geosite, domain, ruleset)
- **Share-ссылки** — импорт/экспорт `vless://`, `vmess://`, `trojan://`, `ss://`
- **Латентность** — замер пинга до всех серверов одной кнопкой
- **Трафик** — живая статистика upload/download через Clash API
- **Меню-бар (tray)** — иконка в menu bar с быстрыми действиями: подключиться,
  выбрать сервер, показать окно, выйти
- **Хоткеи** — `⌘N` добавить сервер, `⌘K` подключиться/отключиться и т.д.
- **Темы** — светлая / тёмная
- **Языки** — русский / английский

---

## Установка и первый запуск

### Быстрый старт (для разработчиков)

```bash
git clone <repo-url>
cd vless-client
mvn clean javafx:run
```

При первой сборке Maven автоматически скачает `sing-box 1.13.8` для обеих
архитектур (arm64 + amd64) в `target/classes/native/darwin-{arch}/` с проверкой
SHA-256. Эти бинари бандлятся в jar и извлекаются при первом запуске.

### Если бандл недоступен

Если приложение запущено без build-time бандлинга (например, голый jar без
`generate-resources`), при старте появится модальный диалог, который скачает
`sing-box` с GitHub Releases и закэширует в
`~/Library/Application Support/VlessClient/bin/sing-box`. Загрузка защищена
SHA-256 проверкой.

### Если нет сети

В диалоге-установщике есть подсказка с командой:

```bash
brew install sing-box
```

После ручной установки перезапусти приложение или нажми **Retry download** в
оранжевом баннере на Dashboard — оно подхватит бинарь из стандартных
Homebrew-путей (`/opt/homebrew/bin`, `/usr/local/bin`) или из `$PATH`.

---

## Как пользоваться

### 1. Добавить сервер

**Вариант A — вручную.** Открой вкладку **Servers**, нажми **Add Server**,
заполни форму (address, port, UUID, transport, TLS и т.д.) и сохрани.

**Вариант B — через share-ссылку.** На вкладке **Servers** нажми **Import Link**
и вставь `vless://...` / `vmess://...` / `trojan://...` / `ss://...` — поля
заполнятся автоматически.

**Вариант C — через подписку.** Открой вкладку **Subscriptions**, добавь URL
подписки. Список серверов обновится автоматически и будет периодически
пересинхронизироваться.

### 2. Выбрать активный сервер

На вкладке **Servers** кликни по серверу в списке — он получит бейдж
**ACTIVE**. Это и есть сервер, который будет использован при нажатии
Connect.

Правый клик по серверу даёт контекстное меню: **Edit**, **Duplicate**,
**Copy Share Link**, **Delete**.

### 3. Выбрать режим прокси

На вкладке **Dashboard** выпадающий список **Mode**:

- **System Proxy** (по умолчанию) — sing-box открывает SOCKS5/HTTP прокси,
  настройки macOS прокси переключаются автоматически. Работает без пароля.
  Приложения, которые уважают системный прокси (Safari, Chrome, большинство
  CLI-инструментов), начнут ходить через туннель.

- **TUN** — sing-box создаёт виртуальный сетевой интерфейс `utun*`. **Весь**
  трафик компьютера идёт через туннель (включая приложения, которые игнорируют
  системный прокси). Требует пароля администратора — macOS покажет промпт через
  `osascript`.

### 4. Подключиться

Большая кнопка **Connect** на Dashboard — одно нажатие. Цвет индикатора:
- 🟢 зелёный = подключено
- 🟠 оранжевый = подключается
- 🔴 красный = ошибка
- ⚪ серый = отключено

Хоткей: `⌘K`.

### 5. Мониторинг

**Dashboard** показывает живую статистику:
- Upload / Download speed
- Total Up / Total Down
- Кнопка **Test Latency** — замеряет пинг до всех серверов

**Logs** — вкладка со стримом логов sing-box (с фильтром по уровню).

### 6. Меню-бар

Иконка в меню-баре macOS даёт быстрые действия без открытия главного окна:
- Show window
- Connect / Disconnect
- Select server
- Quit

Закрытие главного окна (`⌘W` или красный кружок) НЕ выходит из приложения —
оно продолжает жить в меню-баре. Выход — через `Quit` в меню-баре или `⌘Q`.

---

## Горячие клавиши

| Hotkey | Действие                                |
|--------|-----------------------------------------|
| `⌘K`   | Connect / Disconnect                    |
| `⌘N`   | Add server                              |
| `⌘1`   | Dashboard                               |
| `⌘2`   | Servers                                 |
| `⌘3`   | Subscriptions                           |
| `⌘4`   | Routing                                 |
| `⌘5`   | Logs                                    |
| `⌘,`   | Settings                                |
| `⌘W`   | Hide window (продолжает работать в tray)|
| `⌘Q`   | Quit                                    |

---

## Настройки (Settings)

| Поле                     | Описание                                              |
|--------------------------|-------------------------------------------------------|
| Theme                    | Light / Dark                                          |
| Language                 | Russian / English                                     |
| Mixed port               | Порт SOCKS/HTTP прокси (по умолчанию `2080`)          |
| Clash API port           | Порт для статистики трафика (по умолчанию `9090`)     |
| Auto-connect on start    | Подключаться к активному серверу при запуске          |
| Check for updates        | Периодическая проверка обновлений приложения          |
| Allow LAN                | Разрешить коннекты к прокси из локальной сети         |

Данные хранятся в `~/Library/Application Support/VlessClient/`:
- `settings.json` — настройки
- `servers.json` — список серверов
- `subscriptions.json` — подписки
- `routing.json` — правила маршрутизации
- `bin/sing-box` — кэш авто-загруженного бинаря (если был)
- `mcp-token` — bearer-токен MCP-сервера (права `0600`)
- `logs/mcp-audit.log` — журнал всех изменяющих вызовов через MCP

---

## Управление агентами (MCP)

Приложение может поднимать локальный **MCP-сервер** (Model Context Protocol),
через который ИИ-агенты (например Claude Code) читают состояние и управляют
клиентом: статус, трафик, логи, список серверов, подключение/отключение,
добавление серверов из share-ссылок, правила маршрутизации и т.д.

### Безопасность

- Сервер слушает **только `127.0.0.1`** (loopback) — снаружи недоступен.
- Каждый запрос требует заголовок `Authorization: Bearer <token>`; токен лежит
  в `mcp-token` с правами `0600`.
- **Выключен по умолчанию** — включается тумблером в Settings.
- Изменяющие операции гейтятся флагом «Allow configuration changes». Самые
  опасные (подключение в TUN-режиме → запрос пароля macOS, удаление сервера)
  дополнительно требуют явного `confirm: true`.
- Каждый изменяющий вызов пишется в `logs/mcp-audit.log`.

### Включение

**Settings → Agent Control (MCP):**
1. Отметь **Enable MCP server** (порт по умолчанию `55555`).
2. При необходимости включи/выключи **Allow configuration changes**.
3. Нажми **Copy command** — в буфер попадёт готовая строка, и выполни её:

```bash
claude mcp add --transport http tunl http://127.0.0.1:55555/mcp \
  --header "Authorization: Bearer <твой-токен>"
```

Кнопка **Regenerate token** выпускает новый токен и перезапускает сервер.

### Инструменты

| Категория | Инструменты |
|-----------|-------------|
| Чтение    | `get_status`, `get_traffic`, `list_servers`, `get_logs`, `list_subscriptions`, `get_routing`, `get_settings` |
| Действия  | `connect`, `disconnect`, `select_server`, `measure_latency`, `refresh_subscription` |
| Конфиг    | `add_server`, `update_server`, `delete_server`, `set_proxy_mode`, `set_setting`, `add_routing_rule`, `remove_routing_rule`, `set_routing_preset` |

Ресурсы (browsable): `vless://status`, `vless://traffic`, `vless://servers`,
`vless://routing`, `vless://settings`, `vless://logs/recent`. Плюс live-стрим
логов через SSE (`GET /mcp`, `notifications/message`).

---

## Разработка

### Требования

- JDK 25 (проект использует preview features)
- Maven 3.9+
- bash + curl + tar (стандартно для macOS) — нужны для `generate-resources`
  чтобы скачать sing-box

### Команды

```bash
mvn clean javafx:run        # запуск в dev-режиме
mvn clean package           # сборка shade-jar (с бандлом sing-box)
mvn test                    # все тесты
mvn test -Dtest=SingBoxInstallerTest   # один тест-класс
mvn validate                # checkstyle
```

### Регенерация иконки

```bash
java --source 25 scripts/GenerateAppIcon.java
```

Генерирует PNG 16/32/64/128/256/512/1024 в `src/main/resources/icons/`.
Правь дизайн в [GenerateAppIcon.java](scripts/GenerateAppIcon.java).

### Обновление sing-box

1. Подними `PINNED_VERSION` в
   [SingBoxInstaller.java](src/main/java/com/vlessclient/service/SingBoxInstaller.java)
2. Скачай новые tarballs и посчитай SHA-256:
   ```bash
   for arch in arm64 amd64; do
     curl -sL -o /tmp/sb-$arch.tgz \
       "https://github.com/SagerNet/sing-box/releases/download/v<VER>/sing-box-<VER>-darwin-$arch.tar.gz"
     shasum -a 256 /tmp/sb-$arch.tgz
   done
   ```
3. Обнови `EXPECTED_SHA256` в `SingBoxInstaller.java` и константы в
   [scripts/bundle-singbox.sh](scripts/bundle-singbox.sh).
4. Обнови `<singbox.version>` в `pom.xml`.
5. Почисти кэш: `rm -rf ~/.cache/vless-client-build/`

### Структура

```
src/main/java/com/vlessclient/
├── app/            # Launcher, VlessClientApp, ServiceLocator, I18n, AppVersion
├── model/          # POJOs: ServerConfig, AppSettings, Subscription, Routing...
├── service/        # SingBoxEngine, SingBoxInstaller, ConfigStore,
│                   # SubscriptionService, RoutingService, LatencyTester,
│                   # TrafficMonitor, TrayIconService, UpdateManager, ...
└── ui/view/        # JavaFX контроллеры для каждой вкладки

src/main/resources/
├── fxml/           # FXML-разметка
├── css/            # light.css, dark.css
├── i18n/           # messages_en.properties, messages_ru.properties
└── icons/          # app-icon-{16..1024}.png

scripts/
├── bundle-singbox.sh       # скачивает sing-box на этапе mvn generate-resources
└── GenerateAppIcon.java    # генератор иконки приложения
```

---

## Траблшутинг

**«sing-box binary not found» при запуске**
Используется старая сборка без bundling. Запусти `mvn clean compile` (это
запустит `generate-resources` и скачает sing-box) или дождись установщика при
первом запуске.

**Connect кнопка недоступна**
Нет активного сервера — проверь, что на вкладке Servers один из серверов помечен
бейджем **ACTIVE** (клик по строке сервера).

**«Process exited unexpectedly (code N)»**
sing-box упал. Посмотри вкладку **Logs** — там будет причина. Частые ошибки:
неверный UUID, неподходящий transport, недоступный сервер, конфликт портов.

**TUN-режим запрашивает пароль каждый раз**
Так и должно быть — создание TUN-интерфейса требует root. Пароль запрашивает
стандартный macOS `osascript`.

**Порт 2080 занят**
Смени `Mixed port` в настройках на свободный.

---

## Лицензия

TBD. sing-box лицензируется под [GPL-3.0](https://github.com/SagerNet/sing-box/blob/main/LICENSE).

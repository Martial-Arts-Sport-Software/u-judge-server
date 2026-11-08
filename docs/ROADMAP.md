# U'Judge v1 Pilot: roadmap

Статус документа: канонический план первого пилотного релиза.

Дата старта baseline: 30 августа 2026 года.

Целевой горизонт: до 12 недель.

## 1. Цель релиза

Подготовить пилот U'Judge, на котором можно провести тренировочное соревнование в изолированной Wi-Fi сети с одним
desktop peer, мобильными судьями, импортированными сетками, полным аудитом и восстановлением после сетевых разрывов.

Pilot не считается production-ready до отдельного hardening после полевого испытания.

## 2. Ограничения планирования

| Ограничение | Значение                                          |
|-------------|---------------------------------------------------|
| Команда     | Один разработчик                                  |
| Срок        | До 3 месяцев                                      |
| Desktop     | Windows и macOS Ventura и новее                   |
| Mobile      | Honor 50 Lite (Android 11) и iPhone 15 (iOS 26.6) |
| Масштаб     | Один peer, одна площадка, 5-7 court clients; до 500 участников |
| Сеть        | Изолированная Wi-Fi сеть через выделенный роутер площадки |
| Хранилище   | Управляемый приложением PostgreSQL на каждом peer |
| Репликация  | Не входит в v1 Pilot; целевая post-v1 P2P-модель  |
| Дисциплины  | 8 дисциплин из правил ФХР 2024 (PDF 1) + Tanbon как продуктовая дисциплина |

Объём крайне напряжённый для одного разработчика. План реализуем только как pilot с жёсткими техническими гейтами.
Нельзя компенсировать отставание исключением тестов сохранности данных, scoring или reconnect.

## 3. Принципы выполнения

- Сначала один вертикальный Kerugi slice, затем Tanbon и остальные семь дисциплин из PDF 1.
- Работа планируется инкрементами поставки: каждый PR доводит сценарий до production entry point и закрывает
  требования или пункт gate.
- События и контракты проектируются до UI-интеграции.
- Источник результата - append-only журнал, а не изменяемые счётчики.
- Каждая функция получает тестируемый acceptance criterion из `REQUIREMENTS.md`.
- Незавершённые действия не маскируются пустыми обработчиками.
- Новые архитектурные слои добавляются только для конкретных границ: transport, persistence, scoring и replication.
- Работа ведётся от чисто воспроизводимых сборок и CI.

## Статус выполнения на `main`

Статус сверяется только с влитыми в `main` изменениями и их тестами/CI. Частично выполненный этап не закрывает gate.

- [x] Gate G0: baseline обоих репозиториев подтверждён. Server CI и `./gradlew build --no-daemon` подтверждают
  воспроизводимую Java 21 сборку; client baseline закрыт merged [PR #38](https://github.com/Martial-Arts-Sport-Software/u-judge-client/pull/38)
  с Android/shared tests и iOS framework compilation. Physical-device acceptance остаётся доказательством G1 и последующих gates.
- [ ] Gate G1: single-peer PostgreSQL на macOS и pairing/reconnect на emulator подтверждены I1; clean Windows/macOS (I7) и
  mobile realtime на физических устройствах (I3) открыты; P2P остаётся post-v1.
- [ ] Gate G2: не готов.
- [ ] Gate G3: не готов.
- [ ] Gate G4: импорт заявок (I4a), жеребьёвка, площадки, нумерация боёв и протоколы (I4b), старт поединка с панели
  площадки с подключёнными судьями (server-часть I2a) и продвижение победителя по сетке (server-часть I2b) подтверждены
  тестами.
- [ ] Gate G5: не готов.
- [ ] Gate G6: не готов.
- [ ] Gate G7: не готов.
- [ ] Gate G8: не готов.

## Инкременты поставки

Единица планирования и PR - инкремент из таблицы ниже, а не отдельная transition, команда, таблица или publisher.
Правила инкрементов закреплены в `AGENTS.md` (пункты 9-10, 12) и skill `u-judge-increment-planning`.

### Ретроспектива 30.08-20.09.2026

| Наблюдение | Факт на `main` |
|------------|----------------|
| Частота PR | Около 50 merged PR за 3 недели; типичный feature PR добавляет 40-450 строк кода и одну команду или transition |
| Результат | Ни одно требование не переведено в `Implemented`; после G0 не закрыт ни один gate, хотя этап 3 планировался на недели 4-5 |
| Достижимость | `Server.start()` вызывает `module()` без lifecycle/Kerugi handlers, а desktop только запускает `Server.start()` без operator pairing service и проекций: работа около 20 PR недоступна в запущенном приложении |
| Фрагментация | Один Kerugi-бой разнесён по четырём journal-таблицам `V2`-`V5` и семи `Realtime*Commands`; `UNIQUE (owner_peer_id, sequence)` проверяется только внутри каждой таблицы |
| Документация | Около 45 KB повторяющегося текста «partial evidence ... remain open» в roadmap, дублированного в README и PROJECT |

Причина: slice определялся стеком server-слоёв (domain → JDBC → WebSocket) для одной команды, а не сценарием, который
видит оператор или судья. Интеграция в production entry point, desktop UI и client evidence каждый раз оставались
«следующей работой», поэтому ни один PR не мог закрыть requirement. Правило evidence-driven slices от 14.09.2026 не
изменило темп: после него вышли такие же однокомандные PR результата, дисквалификации и delivery proof.

### Правила

- Инкремент заканчивается сценарием, воспроизводимым из `./gradlew :desktop:run` или installer, а не только из test fixture.
- Инкремент переводит хотя бы одно Must-требование в `Implemented` или закрывает пункт gate. Partial evidence не
  является целью инкремента.
- Ориентир размера - 3-7 рабочих дней и одна issue. Обычно это один PR из 10-30 небольших Conventional Commits;
  допускается 2-3 последовательных PR под одной issue, если каждый оставляет `main` рабочим, а статусы отмечает последний.
- Новая команда, transition, таблица, publisher или экран без сценария - это коммит внутри инкремента, не отдельный PR.
- Acceptance test инкремента проверяет сценарий целиком: Ktor `testApplication` или desktop application service,
  реальный JDBC journal, restart/retry и публикация. Unit tests остаются для формул и инвариантов.
- Доказательство записывается один раз: статус строки в таблице ниже и строка в таблице доказательств этапа.
  README и `PROJECT.md` содержат только краткое текущее состояние.
- Если инкремент не укладывается в 7 рабочих дней, его делят по сценариям (например, «Tanbon» и «технические
  дисциплины»), а не по слоям.

### План

Недели считаются от baseline 30.08.2026; 25.09.2026 - неделя 4. Поле «Срок» в разделах этапов остаётся исходным
baseline; при расхождении действует этот план.

| Статус | ID | Недели | Gate | Сценарий, который можно показать | Requirement IDs | Cross-repo |
|--------|----|--------|------|----------------------------------|-----------------|------------|
| [x] | I1 | 4-5 | G1 (PostgreSQL), основа G2 | Desktop запускает managed PostgreSQL и server; судья на emulator проходит pairing по local TLS, оператор одобряет, видит и отзывает устройство; после `kill -9` и перезапуска журнал, реестр устройств и reconnect credential сохранены, client переподключается без повторного pairing | `NFR-004`, `NFR-008`-`NFR-010`, `DEV-003`-`DEV-006`, `UI-007`, `NET-006`, `SYS-007`-`SYS-009`, `AUD-001` | Да: client pairing и reconnect против desktop server |
| [x] | I4a | 5-6 | G4 (импорт) | Оператор выбирает сразу несколько файлов заявок `df-template-v1` (500 участников), видит отчёт об ошибках с файлом, листом, строкой, колонкой, значением и причиной или preview категорий и участников; «Загрузить» проверяет и загружает за один шаг, загрузка атомарна, перед ней делается backup журнала; повторная загрузка до старта заменяет прежнюю с подтверждением, одинаковая ничего не меняет; история загрузок позволяет посмотреть и восстановить любую загрузку или сбросить все; после перезапуска соревнование и история восстановлены | `IMP-001`-`IMP-003`, `IMP-005`-`IMP-008`, `BAK-001`, `SYS-003`, `AUD-001`; ADR-005 | Нет |
| [x] | I4b | 6 | G4 | Оператор проводит жеребьёвку по ФХР 2024: система (выбывание, круговая) и метод (случайная, с разведением по одному критерию), «Перемешать», preview и правка до старта, подтверждение; порядок выступлений в технических дисциплинах; площадки, общая нумерация боёв и PDF «Протокол хода соревнований»; выбор текущего и следующего поединка | `IMP-004`, `BRK-001`, `BRK-002`, `BRK-005`, `SES-003`, `CMP-005`, `CMP-006`, `CMP-008`; ADR-007, ADR-008 | Нет |
| [ ] | I2a | 6-7 | G2, server-часть G3 | Kerugi: бой по баллам. Оператор стартует поединок импортированной сетки; состав судей фиксируется из подключённых устройств (2 или 3), кворум и окно; судьи на emulator ставят `HEAD`/`BODY` через `kerugi_score_command`, оператор вводит броски, вращение и Gamjeom, делает коррекцию и сброс с причиной и подтверждением, фиксирует результат по финальному счёту; restart и reconnect посреди боя дают тот же счёт | `KER-001`-`KER-009`, `KER-015`, `SES-001`, `SES-002`, `SES-004`-`SES-006`, `UI-002`, `UI-008`, `NET-001`, `NET-003`, `AUD-002`, `DEV-008` | Да: client session snapshot, `kerugi_score_command`, outbox после app kill |
| [ ] | I2b | 7-8 | server-часть G3 | Kerugi: время боя и ход сетки. Таймер по FHR 2024 для возрастной категории, два раунда, перерыв, golden round и победа по нему; экран оператора и экран зрителей на втором мониторе (то же без операторских кнопок); победитель продвигается по сетке, судьи переходят к следующему поединку без перезапуска | `KER-010`-`KER-013`, `UI-001`, `UI-003`-`UI-005`, `SES-007`, `BRK-003`, `BRK-004` | Да: client последовательные сессии |
| [ ] | I3 | 8-9 | G1 (realtime), G3 | Honor 50 Lite и iPhone 15 через роутер площадки находят server, проходят pairing, судят Kerugi-бой, переживают disconnect с buffered events и искусственную задержку | `DEV-001`, `DEV-002`, `DEV-007`-`DEV-010`, `NET-002`-`NET-005`, `SYS-006`, `NFR-001`-`NFR-003`, `UI-006` | Да: client judge screen, outbox, clock offset |
| [ ] | I5 | 9-10 | G5 | Tanbon переиспользует Kerugi pipeline; Hosinsool и технические режимы считают итог по нормативным векторам; client и server дают одинаковые суммы | `TAN-001`-`TAN-005`, `TEC-001`-`TEC-014`, `NFR-011` | Да: экраны дисциплин и vectors |
| [ ] | I6 | 10 | G6 | Результат произвольной сессии объясняется историей и совпадает с XLSX/CSV после backup/restore; русский UI | `AUD-002`-`AUD-004`, `REP-001`-`REP-004`, `BAK-002`-`BAK-004`, `SYS-004` | Нет |
| [ ] | I7 | 11 | G7 | Clean Windows/macOS installers, APK/TestFlight, нагрузка 5-7 clients, packet loss и restart, security check | `SYS-005`, `REL-003`-`REL-007`, `NFR-005`-`NFR-007`, `NFR-014` | Да: mobile builds |
| [ ] | I8 | 12 | G8 | Полевой пилот по разделу 12 | `REL-001`, `REL-002` | Да |

#### Доказательства I1

Server [#125](https://github.com/Martial-Arts-Sport-Software/u-judge-server/pull/125),
[#126](https://github.com/Martial-Arts-Sport-Software/u-judge-server/pull/126); client
[u-judge-client#114](https://github.com/Martial-Arts-Sport-Software/u-judge-client/pull/114). Сценарий пройден 26.09.2026 на
macOS 27 (arm64) с `./gradlew :desktop:run` и Android emulator `sdk_gphone16k_arm64` (Android 17, API 37): pairing по коду
сверки, подтверждение, kill приложения, `kill -9` desktop и перезапуск, отзыв, отклонение.

| Requirement | Статус | Доказательство |
|-------------|--------|----------------|
| `NFR-004` | Implemented | `kill -9` desktop: журнал, реестр устройств и credential сохранены (emulator); `RealPostgresLifecycleTest`, `ServerRuntimeTest` на PostgreSQL 18.6 |
| `NFR-008` | Implemented | JSON-логи `logs/server.jsonl` по device/event ID; `ServerLogTest` проверяет отсутствие фамилии, credential и payload |
| `NFR-009` | Implemented | Upgrade `V5` → `V6` с данными на H2 и PostgreSQL 18.6 в CI |
| `NFR-010` | Partial | Start/stop, ошибка порта, restart после аварийного завершения на macOS и Linux CI; Windows не проверялась (I7) |
| `DEV-003` | Implemented | Пустая фамилия отклоняется (`PairingRoutesTest`); оператор видит фамилию на экране устройств |
| `DEV-004` | Implemented | Credential выдаётся только после подтверждения оператором и только по matching delivery proof на TLS (`ServerRuntimeTest`, emulator) |
| `DEV-005` | Implemented | Экран устройств показывает Android/iOS и «на связи»/«не на связи» (emulator, `ServerRuntimeTest`) |
| `DEV-006` | Implemented | Отзыв закрывает сокет устройства сразу, новые handshake и команды отклоняются (emulator, `PairingWebSocketTest`) |
| `UI-007` | Implemented | Сбой PostgreSQL или порта виден оператору с перезапуском (`ServerRuntimeTest`); ошибка сохранения решения показывается на экране устройств |
| `NET-006` | Implemented | Server закрывает сокет по `heartbeat_timeout`; client переходит в reconnecting и восстанавливается после `kill -9` desktop (emulator, `ServerClosedSocketTest` в client) |
| `SYS-007` | Implemented | Решения pairing и доменные события в `domain_events` с автором, источником, временем и payload |
| `SYS-008` | Implemented | UUID event ID, idempotent retry и отказ конфликтующего ID в едином журнале (`JdbcDomainEventStoreTest`) |
| `SYS-009` | Implemented | Единая per-peer sequence и four-timestamp `clock_sync` против настоящего server |
| `AUD-001` | Implemented | Журнал только дописывается; отзыв, отклонение и замена запроса - отдельные события |

#### Доказательства I4a

Server [#130](https://github.com/Martial-Arts-Sport-Software/u-judge-server/pull/130). Сценарий пройден 27-28.09.2026 на
macOS 27 (arm64) с `./gradlew :desktop:run`: отчёт об ошибках, загрузка 500 синтетических участников, повторная загрузка
без изменений, замена с подтверждением и отменой, история с восстановлением и сбросом, перезапуск. Реальной заполненной
заявки нет: доступны только пустые шаблоны федерации.

| Requirement | Статус | Доказательство |
|-------------|--------|----------------|
| `IMP-001` | Partial | Пустые шаблоны федерации (`df.xlsx` и 4 возрастных) читаются без ошибок, заполненный шаблон 12-14 даёт спортсменов, пару и команду (`ApplicationTemplateV1ReaderTest`); реальная обезличенная заявка не проверена |
| `IMP-002` | Implemented | Участники со всеми полями заявки и источником (файл SHA-256, лист, строка, №) (`ApplicationTemplateV1ReaderTest`, preview на desktop) |
| `IMP-003` | Implemented | Категории: дисциплина, оружие, возрастная секция, пол, вес; файлы разных тренеров сливаются (`ApplicationImportTest`) |
| `IMP-005` | Implemented | Все правила «Требований к заполнению заявок» - блокирующие ошибки с файлом, листом, строкой, колонкой, значением и причиной (`ApplicationTemplateV1ReaderTest`, `ApplicationImportTest`, desktop) |
| `IMP-006` | Implemented | Загрузка - одно событие журнала; ошибка проверки, backup или записи ничего не меняет (`CompetitionApplicationsServiceTest`) |
| `IMP-007` | Implemented | Одинаковая загрузка ничего не пишет, другая требует подтверждения и отменяется без изменений (`CompetitionApplicationsServiceTest`, desktop) |
| `IMP-008` | Implemented | Перед загрузкой, восстановлением и сбросом журнал выгружается в `backups/*.jsonl`; прежняя загрузка восстанавливается из истории. Восстановление из файла backup - I6 |
| `BAK-001` | Implemented | Backup перед каждой загрузкой; ошибка backup отменяет загрузку (`CompetitionApplicationsServiceTest`) |
| `SYS-003` | Partial | Импорт 500 участников: проверка меньше 10 с (`ApplicationImportTest`), запись и восстановление после перезапуска PostgreSQL 18.6 (`RealPostgresLifecycleTest`); навигация, расчёт и экспорт - следующие инкременты |
| `AUD-001` | Implemented | Загрузки, замены, восстановления и сбросы только дописываются в `domain_events` (с I1) |

#### Доказательства I4b

Server [#132](https://github.com/Martial-Arts-Sport-Software/u-judge-server/pull/132) (жеребьёвка),
[#133](https://github.com/Martial-Arts-Sport-Software/u-judge-server/pull/133) (площадки, нумерация, PDF, полноэкранный
просмотр, панель площадки). Сценарий доказан acceptance tests сервисов на JDBC journal и production wiring
(`ServerRuntime` → desktop): жеребьёвка, правка, подтверждение, площадки, нумерация, протокол и следующий бой, повтор,
отказ, перезапуск, в том числе 500 участников на PostgreSQL 18.6. Ручной прогон desktop отложен до полного регресса этапа
7 (решение 08.11.2026). Старт поединка из сетки появляется с I2a, поэтому блокировка после старта доказана сервисными
тестами.

| Requirement | Статус | Доказательство |
|-------------|--------|----------------|
| `IMP-004` | Implemented | Сетки и порядок выступлений строятся жеребьёвкой по ФХР 2024 §1.2, §1.4 из импортированных участников: детерминизм по seed, byes, разведение (`DrawTest`); подтверждение одной транзакцией и восстановление после перезапуска (`CompetitionDrawServiceTest`, `RealPostgresLifecycleTest`) |
| `BRK-001` | Implemented | Список категорий со статусами; preview с участниками, парами, «Бой № N», сеткой и полноэкранным просмотром; одна нумерация по всем площадкам по кругам, без byes, с номерами выступлений (`BoutNumberingTest`, `CompetitionCourtsServiceTest`). Результаты в сетке - I2b |
| `BRK-002` | Implemented | Обмен участников до старта журналируется `bracket_edited` одной транзакцией, отклонённый обмен ничего не пишет; после старта перемешивание, правка, система и смена площадки отклоняются типизированной ошибкой, также после перезапуска (`CompetitionDrawServiceTest`, `CompetitionCourtsServiceTest`). Репликация - post-v1 |
| `BRK-005` | Implemented | Выбывание и круговая; случайная жеребьёвка и разведение по субъекту, городу, организации или тренеру, в том числе внутри пояса; посева, двойного выбывания и олимпийской системы в UI нет (`DrawTest`, `CompetitionDrawServiceTest`) |
| `SES-003` | Implemented | Панель площадки показывает следующий бой или выступление площадки этого компьютера (наименьший номер с известными участниками) и выбор оператора `court_next_bout_selected`, восстановленный после перезапуска (`CompetitionCourtsServiceTest`, `RealPostgresLifecycleTest`); «Начать» делает следующий бой текущим, решённые бои уходят из очереди, панель восстанавливается после перезапуска (`CourtBoutsTest`, I2a) |
| `CMP-005` | Implemented | Подтверждённая сетка назначается единственному локальному peer, bracket ID сохраняется при повторной жеребьёвке и перезапуске (`CompetitionDrawServiceTest`) |
| `CMP-006` | Implemented | `bracket_started` переводит сетку в `IN_PROGRESS`, изменения отклоняются (`CompetitionDrawServiceTest`); первый «Начать» категории блокирует её жеребьёвку только после проверки старта, отклонённый старт ничего не блокирует (`CourtBoutsTest`, I2a) |
| `CMP-008` | Partial | «Все категории» показывает все категории по площадкам с номерами боёв и статусами «свободна», «нет поединков», «требует новой жеребьёвки», «в процессе» (`CompetitionCourtsServiceTest`); «завершена» и результаты - I2b |

#### Доказательства I2a (server)

Server [#135](https://github.com/Martial-Arts-Sport-Software/u-judge-server/pull/135) поверх I4b #133, issue
[#134](https://github.com/Martial-Arts-Sport-Software/u-judge-server/issues/134), контракт
[ADR-009](adr/ADR-009-kerugi-bout-contract.md). Сценарий доказан acceptance tests на production `module()` и JDBC journal:
три `JudgeSimulator` по WebSocket и оператор судят бой с повтором, конфликтом, out-of-order, reconnect на то же место,
restart (H2 и PostgreSQL 18.6) и отказами (`KerugiBoutScenarioTest`, `KerugiBoutServiceTest`, `CourtBoutsTest`,
`ContractFixturesTest`, `ServerRuntimeTest`, `RealPostgresLifecycleTest`). Строка I2a остаётся открытой до client PR
(emulator-судьи, snapshot, resync, outbox после app kill). Ручной прогон desktop отложен до полного регресса этапа 7
(решение 08.11.2026).

| Requirement | Статус | Доказательство |
|-------------|--------|----------------|
| `KER-001` | Implemented | Состав - 2 или 3 подключённых устройства на момент «Начать», журналируется в `kerugi_bout_started` и применяется scoring engine; другой размер или неподключённое устройство отклоняются (`KerugiBoutServiceTest`, `CourtBoutsTest`) |
| `KER-002` | Implemented | Кворум задаётся при старте, по умолчанию 2; одиночный удар остаётся в аудите без балла (`KerugiBoutServiceTest`, `KerugiBoutScenarioTest`) |
| `KER-003` | Implemented | Окно задаётся при старте, по умолчанию `1000 мс`, граница 1000/1001 мс; новое значение действует для следующего боя (`KerugiBoutServiceTest`) |
| `KER-004` | Implemented | `kerugi_score_command` с участником и `HEAD`/`BODY`; неизвестный цвет или область - `invalid_command` (`KerugiBoutScenarioTest`) |
| `KER-005` | Implemented | `BODY=1`, `HEAD=2`, минимальная оценка при конфликте в окне, независимо от порядка прихода (`KerugiBoutServiceTest`) |
| `KER-006` | Implemented | Броски `+1`-`+3` и вращение `+1` с типом, участником, значением и автором; неверное значение отклоняется (`KerugiBoutServiceTest`) |
| `KER-007` | Implemented | Gamjeom увеличивает счёт соперника и считается отдельно (`KerugiBoutServiceTest`) |
| `KER-008` | Implemented | Коррекция - компенсирующее событие, исходное сохраняется, повторная коррекция отклоняется (`KerugiBoutServiceTest`) |
| `KER-009` | Implemented | История боя объясняет каждое нажатие: засчитан, нет кворума, вне времени боя, отменён, сброшен (`KerugiBoutServiceTest`) |
| `KER-015` | Implemented | 10 Gamjeom дают предупреждение, дисквалификация только после подтверждения оператора, победа сопернику (`KerugiBoutServiceTest`, `ContractFixturesTest`) |
| `SES-001` | Implemented | `prepared`/`running`/`paused`/`completed`/`cancelled`; недопустимый переход отклоняется без события (`KerugiBoutServiceTest`) |
| `SES-002` | Implemented | Бой меняет только local owner: команды судьи с чужим peer, сеткой или сессией отклоняются без события (`KerugiBoutServiceTest`); P2P - post-v1 |
| `SES-004` | Partial | Все подписанные сокеты получают одну версию snapshot, состояния и счёта в порядке журнала, неподписанный - ничего (`KerugiBoutScenarioTest`); экран зрителей - I2b |
| `SES-005` | Partial | Результат только по финальному счёту, ничья не завершается; desktop требует подтверждения, ручной прогон - этап 7 |
| `SES-006` | Partial | Сброс боя требует причину и компенсирует все действующие события, история их сохраняет (`KerugiBoutServiceTest`); подтверждение на desktop, сброс раунда - I2b |
| `UI-002` | Partial | Каждое добавление и отмена балла или штрафа - аудируемое событие (`KerugiBoutServiceTest`); экран боя на desktop без ручного прогона (этап 7) |
| `UI-008` | Partial | Сброс, отмена, коррекция, дисквалификация и завершение на desktop идут через диалог подтверждения; ручной прогон - этап 7 |
| `NET-001` | Partial | ACK после commit с event ID; sequence и client timestamp в журнале (`KerugiBoutScenarioTest`); client - отдельный PR |
| `NET-003` | Partial | Повтор с тем же ID получает прежний ACK без второго события, также после restart (`KerugiBoutScenarioTest`, `RealPostgresLifecycleTest`); client outbox - отдельный PR |
| `AUD-002` | Implemented | Коррекция ссылается на исходное событие, история показывает оба (`KerugiBoutServiceTest`) |
| `DEV-008` | Partial | Snapshot передаёт номер, категорию, стороны, состояние и место судьи (`ContractFixturesTest`, `KerugiBoutScenarioTest`); отказ от hardcoded номера - client PR |

#### Доказательства I2b (server)

Server [#138](https://github.com/Martial-Arts-Sport-Software/u-judge-server/pull/138) поверх I2a #135, issue
[#137](https://github.com/Martial-Arts-Sport-Software/u-judge-server/issues/137), контракт
[ADR-010](adr/ADR-010-kerugi-timer-advancement.md); client
[u-judge-client#127](https://github.com/Martial-Arts-Sport-Software/u-judge-client/pull/127). Сценарий доказан тестами на
production `module()`, JDBC journal и `ServerRuntime`: таймер по возрасту, раунды, перерыв, golden round, окончание периода
по server timer, повтор, отказы, restart посреди раунда (H2 и PostgreSQL 18.6), продвижение победителя до «Победитель»
и переход судей к следующему поединку на том же сокете (`KerugiClockTest`, `KerugiBoutTimerTest`, `KerugiBoutServiceTest`,
`BracketProgressTest`, `CourtBoutsTest`, `KerugiBoutScenarioTest`, `ContractFixturesTest`, `ServerRuntimeTest`,
`RealPostgresLifecycleTest`). Строка I2b остаётся открытой до прогона client против `./gradlew :desktop:run`; ручной прогон
desktop отложен до полного регресса этапа 7 (решение 08.11.2026).

| Requirement | Статус | Доказательство |
|-------------|--------|----------------|
| `KER-010` | Implemented | Два раунда и дополнительный раунд; 1:00/1:30/2:00 по возрастному классу категории фиксируются при «Начать», перерыв 1:00, отдых 0:30 и golden round 1:00 (`KerugiBoutTimerTest`, `KerugiClockTest`, `CourtBoutsTest`) |
| `KER-011` | Implemented | Старт, пауза, продолжение, «Стоп периода» и «Время»; окончание периода по server timer в точный deadline; после перезапуска таймер стоит на последнем сохранённом времени, checkpoint каждые 5 с (`KerugiBoutTimerTest`, `KerugiBoutScenarioTest`, `RealPostgresLifecycleTest`) |
| `KER-012` | Implemented | Перерыв - отдельный период со своим временем; snapshot, `session_state_updated` и `kerugi_timer_updated` передают тип периода и оставшееся время устройствам и окну зрителей; удар в перерыве только в аудите (`KerugiBoutTimerTest`, `ContractFixturesTest`, `CourtBoutsTest`) |
| `KER-013` | Implemented | Победа по финальному счёту после второго раунда; golden round завершается сервером сразу по первому баллу (кворум судей, бросок, вращение) или Gamjeom против одного из спортсменов, коррекция решающего балла отклоняется как изменение завершённого боя; без баллов - предпочтительность; причина хранится в `session_completed` (`KerugiBoutTimerTest`, `KerugiBoutScenarioTest`) |
| `UI-001` | Partial | Экран боя показывает бой, период, таймер, участников, счёт и Gamjeom из persisted projection (`KerugiBout`); ручной прогон - этап 7 |
| `UI-003` | Partial | Недопустимые команды таймера отклоняются без события, повтор с тем же ID идемпотентен (`KerugiBoutTimerTest`); кнопки неактивны, пока команда выполняется; ручной прогон - этап 7 |
| `UI-004` | Partial | Окно зрителей строится из того же read-only `SessionView`, что получает устройство без места, без изменяющих действий (`CourtBoutsTest`); ручной прогон на втором мониторе - этап 7 |
| `UI-005` | Partial | Окно зрителей показывает площадку, категорию, бой и «Приглашаются / Готовятся» из панели площадки, обновляется по каждому событию (`CourtBoutsTest`); ручной прогон - этап 7 |
| `SES-007` | Implemented | Результат сетки ссылается на сессию, события которой его объясняют; восстанавливается после перезапуска (`CourtBoutsTest`) |
| `BRK-003` | Implemented | Структура сетки блокируется первым «Начать», до первого судейского события; перемешивание после результатов и перезапуска отклоняется (`CourtBoutsTest`) |
| `BRK-004` | Implemented | Победитель выбывания занимает слот следующего раунда, бой появляется в очереди площадки со своим номером ADR-008; круговая - по победам и личной встрече; «Победитель» по финалу (`BracketProgressTest`, `CourtBoutsTest`) |
| `CMP-008` | Implemented | «Все категории» показывает «завершена», когда решены все бои категории, также после перезапуска (`CourtBoutsTest`) |

Технические задачи, обязательные внутри инкрементов:

- I1: единый `domain_events` журнал вместо таблиц `V2`-`V5` с одной per-peer sequence; production wiring `ManagedPostgresRuntime` → JDBC journals → `module()`;
  `RealPostgresLifecycleTest` в CI на PostgreSQL из образа runner; ручной прогон macOS/Windows фиксируется в PR, а
  недоступная платформа остаётся открытым пунктом G1; local TLS для credential delivery вместе с client, иначе pairing
  не завершается end-to-end.
- I4: идёт сразу после I1 (решение 26.09.2026): поединки I2a/I2b берутся из сетки соревнования, а не из ручной
  настройки; проведение поединков и продвижение победителя перенесены в I2b. 27.09.2026 разделён на I4a (импорт заявок,
  ADR-005) и I4b (жеребьёвка, ADR-007, ADR-008): входные файлы - заявки без сеток, сетки строятся по правилам ФХР 2024.
  I4b идёт двумя PR под одной issue: жеребьёвка, затем площадки, нумерация, PDF и панель площадки.
  Ручной прогон переработанного экрана жеребьёвки (PR #132) отложен до полного регресса desktop в этапе 7
  (решение 08.11.2026).
- I2a: Kerugi bout aggregate поверх единого журнала и один realtime command dispatcher вместо отдельных `Realtime*Commands`
  (перенесено из I1 26.09.2026: до I2a Kerugi-команды недостижимы из production, а их контракт меняется вместе с session
  snapshot); session snapshot/assignment для client (`DEV-008`) и resync после reconnect; симулятор судей для acceptance
  test. Состав судей - подключённые устройства на момент старта поединка, 2 или 3; он сохраняется в журнале и не меняется
  до конца боя, отключившийся судья возвращается на своё место. Сейчас client отправляет удар как generic `command` с
  payload `kerugi_score`, который server ACK-ит без scoring: I2a переводит client на `kerugi_score_command` с audit
  context из snapshot.
- I2b: длительности и раунды по [FHR 2024](FHR-RULES-2024.md) §4.1.2, §4.1.13; причины победы только финальный счёт и
  golden round (`KER-013`), остальные причины §4.1.14 (`KER-014`, Should) - после пилотного Must-scope. После
  восстановления server таймер стоит на паузе на последнем сохранённом времени, продолжение - действие оператора.
  Экран зрителей - второе окно desktop на внешнем мониторе с той же проекцией, без изменяющих действий. Окно строится
  из того же read-only потока публикаций, что получают устройства (решение 28.09.2026): после v1 поддерживаются оба способа
  - окно на мониторе по HDMI и режим «Табло» client на Android TV как read-only устройство площадки; экран вызова
  «Приглашаются / Готовятся» входит в экран зрителей.
- I3: physical-device evidence не заменяется emulator-прогонами I1-I2b. Pairing по QR-коду с desktop по поправке
  [ADR-006](adr/ADR-006-local-tls-trust.md) (решение 26.09.2026): QR содержит адреса, порт и SPKI pin; ручной ввод и mDNS
  остаются запасным путём, где судья вводит 6-значный код и client сам сверяет его с ключом.

## 4. Этап 0: фиксация baseline

Срок: неделя 1.

### Результаты

- [x] Актуализированы README и документы обоих репозиториев.
- [x] Зафиксировано, какие локальные изменения должны войти в release branch.
- [x] Версия pilot отделена от преждевременных `1.0.0` и старых milestones.
- [x] Создан единый backlog по requirement IDs.
- [x] Настроен CI на push/PR: build, tests, wrapper validation и статические проверки.
- [x] Java toolchain закреплён на Java 21.

### Решения ADR

| Статус | ADR     | Архитектурное решение                             |
|--------|---------|---------------------------------------------------|
| [x]    | [ADR-001](adr/ADR-001-event-envelope.md) | Формат event envelope, sequence и idempotency     |
| [x]    | [ADR-002](adr/ADR-002-p2p-discovery-join-anti-entropy.md) | P2P discovery, dual networks, leader claims и anti-entropy |
| [x]    | [ADR-003](adr/ADR-003-managed-postgresql.md) | Управляемая установка PostgreSQL на Windows/macOS |
| [x]    | [ADR-004](adr/ADR-004-http-websocket-contract.md) | HTTP/WebSocket contract и version negotiation     |
| [ ]    | ADR-005 | Версионирование XLSX import adapter               |
| [x]    | [ADR-006](adr/ADR-006-local-tls-trust.md) | Local TLS: self-signed сертификат peer и SPKI pin на телефоне с кодом сверки |
| [x]    | [ADR-009](adr/ADR-009-kerugi-bout-contract.md) | Realtime-контракт боя Kerugi: snapshot, resync, `kerugi_score_command`, один dispatcher |
| [x]    | [ADR-010](adr/ADR-010-kerugi-timer-advancement.md) | Время боя Kerugi: раунды, перерыв, golden round, `kerugi_timer_updated`; продвижение победителя по сетке |

### Gate G0

- Оба репозитория собираются из зафиксированного baseline.
- Документы не противоречат выбранному scope.
- Незакоммиченные рабочие изменения классифицированы и не потеряны.

## 5. Этап 1: технические spikes

Срок: недели 1-2, параллельно с завершением baseline.

### P2P spike

P2P replication, dual network planes, deterministic leader election and quorum-backed claims are retained as the post-v1
architecture. They are not v1 Pilot acceptance gates.

- [x] Запустить минимум 3 peer-процесса.
- [x] Создать события на двух peers во время искусственного partition.
- [x] Восстановить сеть и получить одинаковый набор event IDs и проекции.
- [x] Проверить duplicate delivery, restart и sequence gaps.
- [x] Измерить объём метаданных и время сходимости.
- [x] Перенести P2P implementation spike в [post-v1 backlog](https://github.com/Martial-Arts-Sport-Software/u-judge-server/issues/24).

### PostgreSQL spike

- [x] Автоматически подготовить локальную PostgreSQL instance (I1).
- [x] Выполнить start/stop, schema migration и аварийный restart (I1, macOS и Linux CI).
- [ ] Проверить чистую Windows и macOS machine.
- [x] Определить upgrade/backup strategy и размер installer (ADR-003; macOS DMG 211 MB, I1).

Накопленное доказательство - JVM fixture и H2, не real PostgreSQL на clean machine. Детали в
[ADR-003](adr/ADR-003-managed-postgresql.md).

| Компонент | Подтверждено тестами | Открыто |
|-----------|----------------------|---------|
| Versioned JDBC migrations `V1`-`V7` | Durable journal, restart и idempotency на H2 и PostgreSQL 18.6; `V6` переносит `V2`-`V5` в единый `domain_events` с одной per-peer sequence | - |
| `PostgresProvisioner` | Configured `initdb`, требование `PG_VERSION`, reuse готового cluster, отказ перезаписывать nonempty directory без PostgreSQL marker | - |
| `ManagedPostgres` | Supervision child process; диагностика конфликта loopback-порта, ошибки запуска и аварийного exit; `restart()` после exit; вывод child в `logs/postgres.log`; остановка PostgreSQL, оставшегося после аварийного завершения приложения | - |
| `ManagedPostgresRuntime`, `PostgresRuntimeConfiguration` | JDBC readiness, создание database, JDBC URL публикуется только пока child запущен; platform-specific commands, cluster вне installation directory; `withAvailableLoopbackPort()` выбирает `127.0.0.1` port, поэтому проверка занятости в `start()` обязательна | - |
| `ServerRuntime` | `Server.start()` и desktop: bundled PostgreSQL → миграции → peer ID → JDBC journal → `module()` → mDNS; сбой PostgreSQL во время работы и конфликт HTTP-порта видны как `Failed` | Durable реестр устройств и local TLS (I1) |
| `RealPostgresLifecycleTest` | init/start/migration/restart/journal recovery, upgrade `V5` → `V6` и восстановление после аварийного завершения на PostgreSQL 18.6 из zonky; входит в `./gradlew build` и CI | Clean Windows/macOS installers (I7) |

### Realtime spike

- [ ] Honor 50 Lite/Android 11 и iPhone 15/iOS 26.6 обнаруживают server через mDNS на роутере площадки.
- [ ] Client проходит pairing и WebSocket handshake.
- [ ] Событие получает ACK и безопасно повторяется после disconnect.
- [ ] Clock offset и configurable `1000 мс` window проверяются на искусственной задержке.

Накопленное server-only доказательство - in-memory runtime и Ktor contract tests. Это partial evidence для `DEV-004`,
`DEV-005`, `NET-001`, `NET-003`-`NET-006`, `NFR-006` и `NFR-012`, не закрытие Gate G1.

| Контракт | Подтверждено тестами | Открыто |
|----------|----------------------|---------|
| `GET /v1/metadata` | Version, capabilities, identity площадки, pairing policy и server time до pairing | - |
| `POST /v1/pairing-requests` | Валидация фамилии и platform, pending request, dedup retry по device ID; хранится только SHA-256 hash client delivery proof | - |
| Operator pairing service | Идемпотентные approve/reject/revoke; reconnect credential только при принятии и inactive после отзыва; проекция device ID, platform и `connected`/`disconnected` без surname и credential; anonymous LAN decision/revoke endpoints отсутствуют | - |
| `GET /v1/pairing-status/{requestId}` | Typed pending/accepted/rejected; rejection code только для rejected; credential только по matching proof на secure transport | - |
| `/v1/realtime` handshake | Versioned handshake только для active credential; unknown, revoked и incompatible-version отклоняются без pending state | - |
| Typed commands и ACK | Bounded envelope, idempotent ACK по event ID после commit в `domain_events`, identical retry после restart, resync со snapshot (ADR-009); typed rejections для malformed, oversized, post-revocation, conflicting и journal failure | Client outbox против server (client-часть I2a) |
| `clock_sync` | Echo client timestamp и UTC server receive/send timestamps; invalid timestamp получает typed rejection без закрытия сессии | Artificial delay на физических устройствах (I3) |
| `heartbeat` | `heartbeat_ack`/`heartbeat_rejected`; `heartbeat_timeout` закрывает socket, valid heartbeat продлевает deadline; close помечает устройство disconnected | Physical devices (I3) |
| Удалённый API | Anonymous `POST /score` и revoke endpoint отсутствуют | - |

### Gate G1

Single-peer PostgreSQL and client reconnect are confirmed by working prototypes. P2P is explicitly deferred from v1 Pilot;
its later implementation must preserve ADR-002 ownership and quorum semantics.

## 6. Этап 2: доменная и инфраструктурная основа

Срок: недели 2-3.

### Server

- Ввести IDs соревнования, peer, площадки, сетки, сессии, судьи, устройства и события.
- Реализовать event journal и PostgreSQL migrations.
- Реализовать детерминированные проекции и rebuild.
- Ввести transport-agnostic scoring commands.
- Разделить UI state, application services, transport и persistence.
- Добавить structured logging и health/diagnostic state.

Накопленное доказательство - unit, H2 и Ktor contract tests. Это partial evidence для `SYS-007`-`SYS-009`, `AUD-001`,
`AUD-004`, `SES-001`, `SES-002`, `SES-004`, `CMP-005`, `CMP-006` и `NFR-008`; production wiring отсутствует.

| Компонент | Подтверждено тестами | Открыто |
|-----------|----------------------|---------|
| Typed UUID IDs | Competition, peer, court, bracket, session, judge, device и event; canonical generation и rejection malformed/noncanonical | - |
| `DomainCommand` → `DomainEvent` | Полный typed audit context, source, author, UTC timestamp, type и raw payload; event только с назначенными ID и timestamp; rejection blank fields | - |
| `SequencedDomainEvent`, `DomainEventOrder`, `PeerEventSequence` | Positive per-owner sequence, deterministic order, rejection owner/sequence conflicts | Sequence уникальна только внутри каждой таблицы `V2`-`V5` (I1) |
| `SessionProjection` | `prepared`/`running`/`paused`/`completed`/`cancelled`; переходы боя применяет `KerugiBout` (I2a) | - |
| `BracketOwnership` | Immutable local owner; чужая команда отклоняется без изменения projection; сетка восстанавливается из журнала жеребьёвки (I4b) | P2P claims post-v1 |
| `session_state_updated` | Публикация всем подписанным sockets только для нового события; операторские команды идут с desktop, устройство их не отправляет (I2a) | - |
| `GET /v1/health`, `DiagnosticContext` | Typed liveness без pairing identity и PII; stable IDs без author, payload и credential | - |

### Client

- Заменить глобальные флаги соединения явной state machine.
- Ввести versioned DTO, durable outbox, ACK и reconnect.
- Убрать фиктивное `isConnectedToServer` после простого выбора mDNS service.
- Сохранить фамилию/локаль и pending events.

### Контракт

- Зафиксировать HTTP API и realtime message schema.
- Добавить protocol version и capability negotiation.
- Добавить contract tests в оба репозитория.

### Gate G2

Два клиента могут подключиться к одному server, отправить события, пережить reconnect и получить идентичное persisted
состояние без UI дисциплины.

## 7. Этап 3: Kerugi vertical slice

Срок: недели 4-5.

### Функциональность

- Pairing судьи с подтверждением оператором.
- Настройка состава, кворума и coincidence window.
- События `HEAD` и `BODY` для синего/красного участника.
- Серверная агрегация совпавших событий.
- Операторские броски, вращения, Gamjeom и корректировки.
- Таймер, раунды, перерывы и golden round.
- Экран арбитра и watcher по Figma.
- Warning/attention signal.
- Полный audit trail и объяснение незасчитанных событий.

### Тесты

- Scoring unit tests по разделу 4.1 правил.
- Quorum/window boundary tests.
- Duplicate/out-of-order/reconnect tests.
- Timer restart tests.
- UI smoke tests критических действий.

### Накопленное server-only доказательство

Domain, JDBC/H2/PostgreSQL recovery и Ktor contract tests с production `module()`; статусы требований - в «Доказательства
I2a (server)» и «Доказательства I2b (server)». Общий пробел: прогон client против server и physical devices (I3).

| Компонент | Подтверждено тестами | Открыто |
|-----------|----------------------|---------|
| `KerugiScoringEngine` | 2 или 3 боковых судьи, quorum и coincidence window (по умолчанию 2 и `1000 мс`); непересекающиеся окна одного участника; distinct configured judges; `BODY=1`/`HEAD=2`, минимальная оценка при конфликте; audit каждого окна, включая insufficient quorum | - |
| `KerugiBout`, `KerugiBoutService` (I2a, I2b) | Один бой - одна сессия в `domain_events`: старт с составом и настройками, переходы, кандидаты, действия оператора, коррекция, сброс с причиной, дисквалификация, результат; rebuild после restart; idempotent retry; таймер, раунды, перерыв, golden round (ADR-010) | Сброс раунда (`SES-006`), остальные причины победы (`KER-014`) |
| `RealtimeHub` (I2a) | Один dispatcher для судей и оператора; snapshot, resync, `session_state_updated`, `kerugi_score_updated` в порядке журнала; операторские команды с устройства отклоняются | Client (client PR I2a) |
| `CourtBouts`, экран боя (I2a) | «Начать» с панели площадки, состав из подключённых устройств, блокировка жеребьёвки; desktop экран боя | Ручной прогон (этап 7) |
| `KerugiClock`, server timer (I2b) | Периоды по возрасту, окончание в точный deadline, checkpoint и пауза после restart на едином журнале; отдельный `KerugiTimerJournal` удалён | Ручной прогон (этап 7) |
| `BracketProgress`, окно зрителей (I2b) | Продвижение победителя, «Победитель», «завершена», read-only проекция с «Приглашаются / Готовятся» | Ручной прогон на втором мониторе (этап 7) |

### Gate G3

Kerugi работает end-to-end на реальных Android/iPhone клиентах и desktop server. Ни один балл нельзя потерять, применить
дважды или изменить без аудита.

## 8. Этап 4: сетки и desktop workflow

Срок: недели 5-7.

### Зависимость

Входные файлы - заявки тренеров по шаблонам федерации (`df-template-v1`, ADR-005): участники без сеток. Нужно
реализовать adapter, атомарный импорт, жеребьёвку по правилам ФХР 2024 (ADR-007), стартовые протоколы после жеребьёвки и
протоколы результатов после обработки сеток.

### Функциональность

- Версионированный XLSX adapter заявок и загрузка нескольких файлов сразу.
- Validation report с координатами ошибки; любая ошибка блокирует загрузку.
- Жеребьёвка: выбывание после одного поражения и круговая система, случайная и с разведением по одному критерию.
- Atomic import и backup before import.
- Создание сущностей соревнования в БД и выходных PDF-артефактов по текущему desktop workflow.
- Preview и исправление сетки до первого события.
- Эксклюзивное назначение сетки единственному v1 peer на время `IN_PROGRESS`; leader claim is post-v1.
- Текущий/следующий поединок.
- Продвижение победителя и общий progress.
- Репликация импортированных сеток и результатов переносится в post-v1.
- История всех площадок.

### Исключение

Посев по рейтингу, двойное выбывание и олимпийская система не реализуются в pilot (решение 27.09.2026; олимпийская
система ждёт уточнения).

### Gate G4

Один v1 peer импортирует соревнование и проводит сетки для подключённых court clients; multi-peer replication is post-v1.

## 9. Этап 5: остальные дисциплины

Срок: недели 7-9.

### Tanbon

- Realtime pipeline Kerugi переиспользуется без копирования transport logic.
- Голова `2`, туловище `1`.
- `CROSS` хранится без изменения счёта.
- Подтверждены пользовательские правила: `HEAD=2`, `BODY=1`, `CROSS` не изменяет счёт и сохраняется в audit.

### Hosinsool

- 4/6 технических раундов по возрасту.
- 4 критерия презентации.
- Штрафы и итог с точностью `0.1`.

### Pair, Group, Sword, Pole, Paired Nunchaku и Paired Fans

- Наборы критериев соответствуют правилам и текущему клиентскому UI.
- Четыре weapon-дисциплины реализуются как отдельные режимы.
- Настраиваемые judge count и aggregation formula.
- Default aggregation исключает min/max и усредняет остаток.
- `Send` необратим для судьи.

### Gate G5

Нормативные test vectors для всех дисциплин подтверждены судьёй-экспертом, а client/server дают одинаковые
индивидуальные и итоговые суммы.

## 10. Этап 6: отчётность, backup и локализация

Срок: недели 9-10.

### Результаты

- История с фильтрами по площадке, сетке, сессии и судье.
- XLSX export для организаторов.
- Документированный UTF-8 CSV export.
- Автобэкап перед импортом и после завершённых сессий.
- Проверенное восстановление из backup.
- Полный русский и английский UI.
- Английские PDF не считаются готовыми, пока не заменены реальные материалы.

### Gate G6

Результат произвольной сессии полностью объясняется из истории и совпадает с XLSX/CSV export после backup/restore.

## 11. Этап 7: release hardening

Срок: недели 10-11.

### Desktop

- Clean Windows installer.
- Clean macOS installer.
- Проверка lifecycle PostgreSQL без IDE и developer tooling.
- Полный ручной регресс desktop UI по сценариям всех инкрементов пилота, когда они реализованы (решение 08.11.2026);
  он закрывает отложенные ручные прогоны, начиная с экрана жеребьёвки I4b.
- Подпись Windows и notarization macOS явно отложены после pilot.

### Mobile

- Android APK для pilot devices.
- iOS TestFlight build.
- Минимальные версии ОС фиксируются по инвентаризации устройств.
- Проверка landscape layout и локальной сети.

### Надёжность

- Один peer и 5-7 целевых mobile clients через роутер площадки.
- До 500 импортированных участников.
- Packet loss, latency, peer restart и длительный partition.
- Проверка восстановления durable outbox клиента.
- Security check: отсутствие anonymous write API и secrets в репозитории.

### Gate G7

Все Must-требования `REL-*` имеют доказательство выполнения или pilot блокируется.

## 12. Этап 8: полевой пилот

Срок: неделя 12.

### До начала

- Судья-эксперт подтверждает формулы и сценарии.
- Все устройства промаркированы и проверены.
- Подготовлена резервная локальная сеть.
- Сделан backup исходного соревнования.
- Назначен ответственный за журнал проблем.

### Во время пилота

- Провести минимум одну Kerugi/Tanbon сетку.
- Провести выступления всех технических режимов.
- Проверка P2P partition, leader loss и split-brain claim переносится в post-v1.
- Выполнить reconnect мобильного клиента с buffered events.
- Сравнить UI, историю и ручной контрольный протокол.

### После пилота

- Сохранить журналы, backups и exports.
- Классифицировать дефекты по влиянию на корректность результатов.
- Не объявлять production v1 до исправления всех P0/P1.

## 13. Definition of Done v1 Pilot

- Все Must-требования имеют тест или зафиксированное доказательство приёмки.
- Scoring всех дисциплин подтверждён экспертом.
- Single-peer state recovery подтверждена после restart; P2P convergence переносится в post-v1.
- Нет известных способов потерять или дважды применить подтверждённое событие.
- В v1 сетка имеет единственного локального владельца во время `IN_PROGRESS`; leader quorum claim переносится в post-v1.
- История объясняет каждое изменение результата.
- XLSX/CSV совпадают с сохранённым состоянием.
- Backup восстановлен на отдельном чистом окружении.
- APK, TestFlight, Windows и macOS builds проходят smoke test.
- Ограничения pilot опубликованы в README и release notes.

## 14. Риски

| Риск                                        | Вероятность | Влияние     | Снижение риска                                                                  |
|---------------------------------------------|-------------|-------------|---------------------------------------------------------------------------------|
| P2P без coordinator не укладывается в срок  | Высокая     | Критическое | P2P явно исключён из v1; deterministic leader/quorum claim планируется после Pilot |
| Managed PostgreSQL усложняет installers     | Высокая     | Высокое     | Ранний Windows/macOS spike, миграции и backup до UI                             |
| Нет реального XLSX                          | Высокая     | Высокое     | Получить обезличенный файл до этапа сеток                                       |
| Один разработчик                            | Высокая     | Высокое     | Вертикальные slices, минимальные abstractions, запрет расширения scope          |
| Все дисциплины за 12 недель                 | Высокая     | Высокое     | Переиспользовать scoring/transport, нормативные test vectors до UI              |
| Английские PDF являются копиями русских     | Высокая     | Среднее     | Не считать English reference complete до замены                                 |
| iOS local network/TestFlight задержат pilot | Средняя     | Высокое     | Ранний TestFlight и physical-device smoke test                                  |
| Dirty feature branches расходятся с GitHub  | Высокая     | Среднее     | Зафиксировать release baseline до функциональной разработки                     |
| Server-only slices не закрывают gates        | Высокая     | Высокое     | Инкременты I1-I8 со сценарием в production entry point и закрытием требований   |

## 15. После пилота

- Исправление P0/P1 по результатам поля.
- Production threat model и усиление peer/client identity.
- Windows signing и macOS notarization.
- Публичные release artifacts, checksums и changelog.
- Автообновление и управляемая миграция версии protocol/schema.
- Посев по рейтингу, двойное выбывание и олимпийская система.
- Передача сетки между площадками только после проектирования conflict protocol.
- Решение о раздельных Weapon-дисциплинах.
- Полная accessibility-проверка.

## 16. Связанные документы

- [Описание проекта](PROJECT.md)
- [Функциональные требования](REQUIREMENTS.md)
- [Нормативные правила ФХР 2024](FHR-RULES-2024.md)
- [Клиентский roadmap](https://github.com/Martial-Arts-Sport-Software/u-judge-client/blob/main/docs/ROADMAP.md)

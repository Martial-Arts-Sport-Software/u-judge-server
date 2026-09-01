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
| Дисциплины  | 8 дисциплин из PDF 1 + Tanbon как продуктовая дисциплина |

Объём крайне напряжённый для одного разработчика. План реализуем только как pilot с жёсткими техническими гейтами.
Нельзя компенсировать отставание исключением тестов сохранности данных, scoring или reconnect.

## 3. Принципы выполнения

- Сначала один вертикальный Kerugi slice, затем Tanbon и остальные семь дисциплин из PDF 1.
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
- [ ] Gate G1: single-peer PostgreSQL и mobile realtime spikes не готовы; P2P остаётся post-v1.
- [ ] Gate G2: не готов.
- [ ] Gate G3: не готов.
- [ ] Gate G4: не готов.
- [ ] Gate G5: не готов.
- [ ] Gate G6: не готов.
- [ ] Gate G7: не готов.
- [ ] Gate G8: не готов.

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

- [ ] Автоматически подготовить локальную PostgreSQL instance.
- [ ] Выполнить start/stop, schema migration и аварийный restart.
- [ ] Проверить чистую Windows и macOS machine.
- [ ] Определить upgrade/backup strategy и размер installer.

Текущее доказательство: durable-journal slice с versioned JDBC migration и restart/idempotency tests дополнен
`ManagedPostgres`, который супервизирует сконфигурированный дочерний процесс и диагностирует конфликт loopback-порта,
ошибку запуска и аварийный exit. Тест использует JVM fixture, а не PostgreSQL; managed lifecycle реального сервера,
clean-machine verification и Gate G1 не закрыты. Перед запуском supervised child `PostgresProvisioner` вызывает configured
`initdb`, требует `PG_VERSION`, безопасно переиспользует готовый cluster и отказывается перезаписывать nonempty directory
без PostgreSQL marker; `ManagedPostgres.restart()` заменяет child после аварийного exit. Эти тесты также используют JVM
fixture. `PostgresCommand.withAvailableLoopbackPort()` выбирает свободный IPv4 loopback port (`127.0.0.1`) для нового command;
порт освобождается до запуска child process, поэтому проверка занятости в `ManagedPostgres.start()` остаётся обязательной.
`PostgresRuntimeConfiguration` из одного runtime config формирует platform-specific `initdb`/`postgres` commands,
application-data cluster вне installation directory, port и JDBC URL; `ManagedPostgresRuntime` публикует URL только пока
supervised child запущен. Это JVM-fixture evidence, не real PostgreSQL. Детали в [ADR-003](adr/ADR-003-managed-postgresql.md).

### Realtime spike

- [ ] Honor 50 Lite/Android 11 и iPhone 15/iOS 26.6 обнаруживают server через mDNS на роутере площадки.
- [ ] Client проходит pairing и WebSocket handshake.
- [ ] Событие получает ACK и безопасно повторяется после disconnect.
- [ ] Clock offset и configurable `1000 мс` window проверяются на искусственной задержке.

Текущее доказательство: `GET /v1/metadata` публикует version, capabilities, identity площадки, pairing policy и server
time до pairing; `POST /v1/pairing-requests` валидирует фамилию и platform, создаёт pending request и дедуплицирует retry
по device ID. Локальный operator application service переводит pending request в accepted и выдаёт opaque reconnect
credential без anonymous LAN approval endpoint. Локальный operator service также идемпотентно отзывает принятое устройство:
сохранённый reconnect credential становится inactive, а request identity решения сохраняется. `/v1/realtime` принимает
versioned WebSocket handshake только для active reconnect credential и возвращает typed accepted/rejected response; unknown,
revoked и incompatible-version handshakes отклоняются. Integration tests подтверждают JSON contract, отсутствие anonymous
`POST /score` и revoke endpoint, а также rejection без создания pending state. Эти in-memory slices не заменяют secure
credential delivery/storage, persistent device state, durable ACK, reconnect/resync, heartbeat или physical-device mDNS evidence.
Authenticated `/v1/realtime` clients can now submit a bounded typed command envelope and receive an idempotent ACK keyed by
event ID; malformed, oversized and post-revocation commands receive typed rejections. Receipts are in-memory only, so this
is contract evidence for `NET-001`, `NET-003`, `NET-005`, `NFR-006`, `NFR-007` and `NFR-012`, not the durable ACK required
before Gate G1 can close.

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

### Gate G3

Kerugi работает end-to-end на реальных Android/iPhone клиентах и desktop server. Ни один балл нельзя потерять, применить
дважды или изменить без аудита.

## 8. Этап 4: сетки и desktop workflow

Срок: недели 5-7.

### Зависимость

Реальный входной файл получен и разобран как `df-template-v1`: `/Users/maksim/Downloads/df.xlsx`. Схема больше не является
внешним blocker. Нужно реализовать adapter, подтвердить атомарный импорт и воспроизведение `PDF 1` после импорта и `PDF 2`
после обработки сеток.

### Функциональность

- Версионированный XLSX adapter.
- Validation report с координатами ошибки.
- Atomic import и backup before import.
- Создание сущностей соревнования в БД и выходных PDF-артефактов по текущему desktop workflow.
- Preview и исправление сетки до первого события.
- Эксклюзивное назначение сетки единственному v1 peer на время `IN_PROGRESS`; leader claim is post-v1.
- Текущий/следующий поединок.
- Продвижение победителя и общий progress.
- Репликация импортированных сеток и результатов переносится в post-v1.
- История всех площадок.

### Исключение

Генерация случайной жеребьёвки, посев и распределение по регионам/организациям не реализуются в pilot, даже если
элементы присутствуют в старом Figma.

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

## 15. После пилота

- Исправление P0/P1 по результатам поля.
- Production threat model и усиление peer/client identity.
- Windows signing и macOS notarization.
- Публичные release artifacts, checksums и changelog.
- Автообновление и управляемая миграция версии protocol/schema.
- Генерация жеребьёвки и нормативные методы посева.
- Передача сетки между площадками только после проектирования conflict protocol.
- Решение о раздельных Weapon-дисциплинах.
- Полная accessibility-проверка.

## 16. Связанные документы

- [Описание проекта](PROJECT.md)
- [Функциональные требования](REQUIREMENTS.md)
- [Клиентский roadmap](https://github.com/Martial-Arts-Sport-Software/u-judge-client/blob/main/docs/ROADMAP.md)

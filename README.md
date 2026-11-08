# U'Judge Server

Desktop-приложение площадки и локальный server U'Judge System для проведения соревнований по хапкидо на Windows и macOS.

Проект находится в разработке. U'Judge v1 Pilot предназначен для полевого испытания в изолированной LAN и не является production-релизом.

## Возможности pilot

- управление площадкой, устройствами, текущим поединком и таймером;
- импорт заявок участников из XLSX и жеребьёвка по правилам ФХР 2024;
- Kerugi, Tanbon и четыре технические дисциплины;
- арбитр и полноэкранное watcher-табло;
- append-only аудит, история, XLSX/CSV export и backup;
- локальный PostgreSQL на каждой площадке;
- P2P-синхронизация до 8 равноправных площадок без центрального server;
- работа при сетевом разделении и сходимость после reconnect.

## Текущее состояние

Реализованы Compose Desktop shell, базовая навигация, Ktor Netty server с HTTPS/WSS на `0.0.0.0:8443`, mDNS-публикация `_u-judge._tcp` и CI на `push`/`pull_request`. CI валидирует Gradle Wrapper и whitespace в diff, затем собирает проект на JDK 21 через `./gradlew build`. Первый P2P spike подтвердил in-memory event journal с идемпотентной доставкой, диагностикой sequence gaps и сходимостью трёх peers после partition; решение описано в [ADR-001](docs/adr/ADR-001-event-envelope.md). Начат client protocol: `GET /v1/metadata` публикует версию, capabilities и identity площадки до pairing; `POST /v1/pairing-requests` валидирует фамилию и platform, сохраняет pending request в памяти и дедуплицирует retry по device ID. Локальный operator application service одобряет request и выдаёт reconnect credential либо идемпотентно отзывает принятое устройство, делая credential inactive; anonymous LAN approval/revoke endpoint не создаётся. Authenticated WebSocket принимает typed commands, возвращает ACK и поддерживает `clock_sync`; `heartbeat` получает typed `heartbeat_ack`, а malformed heartbeat — `heartbeat_rejected` без закрытия сессии. Anonymous `POST /score` удалён. Secure credential delivery/storage, durable device state, heartbeat scheduling/timeouts, reconnect/resync и client implementation ещё не реализованы. Начата transport-agnostic domain boundary: валидированный `DomainCommand` с полным typed audit context преобразуется в `DomainEvent` после назначения event ID и UTC timestamp. Production P2P transport не реализован; импорт заявок (`df-template-v1`) реализован в I4a, жеребьёвка, площадки, общая нумерация боёв, PDF «Протокол хода соревнований» и панель площадки со следующим боем - в I4b; старт и судейство боя Kerugi по баллам - server-часть I2a.

Read-only `GET /v1/health` возвращает typed liveness status `healthy` и не раскрывает данные pairing или credentials. Structured logging, persistence readiness и desktop diagnostics ещё не реализованы.

Бой Kerugi (server-часть I2a, [ADR-009](docs/adr/ADR-009-kerugi-bout-contract.md)): «Начать» на панели площадки фиксирует состав из 2-3 подключённых устройств, кворум и окно; один realtime dispatcher принимает `kerugi_score_command` и `judge_attention_command` от судей и операторские действия с desktop, публикует snapshot, состояние и счёт подписанным устройствам; бой восстанавливается из `domain_events` после перезапуска. Время боя и ход сетки (server-часть I2b, [ADR-010](docs/adr/ADR-010-kerugi-timer-advancement.md)): таймер по возрасту с двумя раундами, перерывом и golden round, после перезапуска таймер стоит на последнем сохранённом времени; результат продвигает победителя по сетке, судьи получают следующий поединок на том же сокете; окно зрителей для второго монитора с «Приглашаются / Готовятся».

Tanbon и технические выступления (server-часть I5, [ADR-011](docs/adr/ADR-011-tanbon-technical-contract.md)): категории Tanbon читаются из необязательного листа «Танбон» заявок и судятся боями Tanbon (`tanbon_score_command`, только `HEAD=2`, `BODY=1` и `CROSS` в аудите, без бросков, вращений и Gamjeom); следующее выступление площадки начинается с 1-7 подключёнными судьями и формулой итога, каждый судья один раз отправляет вектор критериев `technical_score_send_command`, server считает итог в десятых (без min/max или среднее), учитывает штрафы и бонус арбитра и ранжирует категорию; экран «Выступление» на desktop. Client-часть ещё не прогнана против desktop; нормативные векторы и чтение tie-break ждут заключения эксперта-судьи.

Отчётность, backup и восстановление (I6, [ADR-012](docs/adr/ADR-012-reports-backup-restore.md)): экран «Отчёты и резервные копии» показывает историю каждого действия судей и оператора с фильтрами и причиной учёта, сохраняет XLSX, документированный CSV, «Протокол хода соревнований» с победителями и счётом и «Протокол результатов» с местами, с печатью и подписями; backup журнала создаётся перед загрузкой и восстановлением, после каждого завершённого боя и по кнопке, хранится до удаления и восстанавливается с проверкой в новую базу на этом или чистом компьютере. Интерфейс desktop по умолчанию русский, английский переключается без перезапуска. Backup не шифруется.

`BracketOwnership` закрепляет сетку за единственным local peer при `IN_PROGRESS`; P2P claims и ownership transfer не входят в v1 Pilot.

`DiagnosticContext` даёт structured diagnostic boundary со stable IDs без персональных данных, payload или credentials.

Начат PostgreSQL persistence spike: versioned JDBC migration сохраняет и восстанавливает envelope Stage 1,
`PostgresProvisioner` готовит configured data directory через `initdb` без перезаписи неизвестных данных до того, как
`ManagedPostgres` супервизирует сконфигурированный дочерний процесс, и сообщает конфликт loopback-порта, ошибку запуска или
аварийный exit; `ManagedPostgresRuntime` ждёт JDBC readiness, создаёт configured database и публикует datasource только после
успешного запуска. `RealPostgresLifecycleTest` выполняет init/start/migration/restart/journal-recovery против PostgreSQL
18.6 из зафиксированных бинарников zonky, которые Gradle распаковывает для текущей ОС; тест входит в `./gradlew build` и CI.
Реальный PostgreSQL lifecycle на clean Windows/macOS и mobile reconnect evidence ещё не подтверждены.
Решение и ограничения зафиксированы в [ADR-003](docs/adr/ADR-003-managed-postgresql.md).

`Server.start()` и desktop запускают bundled PostgreSQL 18.6 в application-data каталоге ОС, применяют миграции единого
журнала `domain_events`, сохраняют peer ID и бои Kerugi, пишут JSON-логи и показывают сбой server/БД оператору;
после аварийного завершения оставшийся PostgreSQL останавливается при следующем запуске. Server
принимает только HTTPS/WSS на `8443` с self-signed сертификатом peer (ADR-006), выдаёт credential только по matching
delivery proof, хранит решения pairing в журнале, а desktop показывает код сверки и позволяет подтвердить, отклонить и
отозвать устройство. Экран «Поединок» проводит бой Kerugi по баллам (server-часть I2a); таймер и ход сетки - I2b. Подробности в [roadmap](docs/ROADMAP.md#инкременты-поставки).
Подробное разделение текущего и целевого состояния находится в [описании проекта](docs/PROJECT.md).

## Модули

| Путь | Назначение |
| --- | --- |
| `desktop/` | Compose Desktop UI и интеграция desktop application |
| `server/` | Ktor server, persistence и будущие application services |
| `docs/` | Системное описание, требования и канонический roadmap |

## Запуск

Требуется JDK 21.

Запуск desktop-приложения:

```shell
./gradlew :desktop:run
```

Сборка installer для текущей ОС:

```shell
./gradlew :desktop:packageDistributionForCurrentOS
```

Отдельный запуск server-модуля:

```shell
./gradlew :server:run
```

Проверка managed PostgreSQL против реального PostgreSQL. Задача `:server:preparePostgresBundle` распаковывает
зафиксированные бинарники `io.zonky.test.postgres:embedded-postgres-binaries-*` в `server/build/postgres-bundle/postgresql`
и передаёт этот путь тестам; нужен `tar` с поддержкой xz (есть в macOS, Windows 10+ и Linux):

```shell
./gradlew :server:test --tests org.mass.persistence.RealPostgresLifecycleTest
```

## Документация

- [Описание U'Judge System](docs/PROJECT.md)
- [Системные и серверные требования](docs/REQUIREMENTS.md)
- [Roadmap U'Judge v1 Pilot](docs/ROADMAP.md) и [инкременты поставки](docs/ROADMAP.md#инкременты-поставки)
- [Клиент U'Judge](https://github.com/Martial-Arts-Sport-Software/u-judge-client)
- [Макеты Figma](https://www.figma.com/design/x5vY9DbXh3a0kv0lBPcNru/Judging-app)

## Ограничения pilot

- заявки импортируются по шаблону `df-template-v1`, сетки строит жеребьёвка по правилам ФХР 2024 (случайная и с разведением);
- Windows signing и macOS notarization отложены до этапа после pilot;
- P2P protocol и упаковка PostgreSQL должны пройти обязательные ADR/spikes;
- посев по рейтингу, двойное выбывание и олимпийская система не входят в scope.

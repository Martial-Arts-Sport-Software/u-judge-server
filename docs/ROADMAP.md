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
- [ ] Gate G1: single-peer PostgreSQL и mobile realtime spikes не готовы; P2P остаётся post-v1.
- [ ] Gate G2: не готов.
- [ ] Gate G3: не готов.
- [ ] Gate G4: не готов.
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
| [ ] | I1 | 4-5 | G1 (PostgreSQL), основа G2 | Desktop запускает managed PostgreSQL и server; судья на emulator проходит pairing по local TLS, оператор одобряет, видит и отзывает устройство; после `kill -9` и перезапуска журнал, реестр устройств и reconnect credential сохранены, client переподключается без повторного pairing | `NFR-004`, `NFR-008`-`NFR-010`, `DEV-003`-`DEV-006`, `UI-007`, `NET-006`, `SYS-007`-`SYS-009`, `AUD-001` | Да: client pairing и reconnect против desktop server |
| [ ] | I2 | 5-6 | G2, server-часть G3 | Оператор проводит Kerugi-бой на desktop: состав, кворум, окно, таймер по FHR 2024, баллы симулированных судей, действия, коррекции, сброс с причиной, golden round, результат; watcher read-only; restart и reconnect посреди боя дают тот же счёт | `KER-001`-`KER-013`, `KER-015`, `SES-001`-`SES-007`, `UI-001`-`UI-005`, `UI-008`, `NET-001`, `NET-003`, `AUD-002` | Да: client session snapshot и `kerugi_score_command` |
| [ ] | I3 | 7 | G1 (realtime), G3 | Honor 50 Lite и iPhone 15 через роутер площадки находят server, проходят pairing, судят Kerugi-бой, переживают disconnect с buffered events и искусственную задержку | `DEV-001`, `DEV-002`, `DEV-007`-`DEV-010`, `NET-002`-`NET-005`, `SYS-006`, `NFR-001`-`NFR-003`, `UI-006` | Да: client judge screen, outbox, clock offset |
| [ ] | I4 | 7-8 | G4 | Оператор импортирует `df-template-v1` (500 участников), видит validation report и preview, правит сетку до старта, проводит поединки, победитель продвигается | `IMP-001`-`IMP-008`, `BRK-001`-`BRK-005`, `SES-003`, `SES-007`, `CMP-005`, `CMP-006`, `CMP-008`, `SYS-003`, `BAK-001`; ADR-005 | Нет |
| [ ] | I5 | 8-9 | G5 | Tanbon переиспользует Kerugi pipeline; Hosinsool и технические режимы считают итог по нормативным векторам; client и server дают одинаковые суммы | `TAN-001`-`TAN-005`, `TEC-001`-`TEC-014`, `NFR-011` | Да: экраны дисциплин и vectors |
| [ ] | I6 | 10 | G6 | Результат произвольной сессии объясняется историей и совпадает с XLSX/CSV после backup/restore; русский UI | `AUD-002`-`AUD-004`, `REP-001`-`REP-004`, `BAK-002`-`BAK-004`, `SYS-004` | Нет |
| [ ] | I7 | 10-11 | G7 | Clean Windows/macOS installers, APK/TestFlight, нагрузка 5-7 clients, packet loss и restart, security check | `SYS-005`, `REL-003`-`REL-007`, `NFR-005`-`NFR-007`, `NFR-014` | Да: mobile builds |
| [ ] | I8 | 12 | G8 | Полевой пилот по разделу 12 | `REL-001`, `REL-002` | Да |

Технические задачи, обязательные внутри инкрементов:

- I1: единый `domain_events` журнал вместо таблиц `V2`-`V5` с одной per-peer sequence и один realtime command dispatcher
  вместо отдельных `Realtime*Commands`; production wiring `ManagedPostgresRuntime` → JDBC journals → `module()`;
  `RealPostgresLifecycleTest` в CI на PostgreSQL из образа runner; ручной прогон macOS/Windows фиксируется в PR, а
  недоступная платформа остаётся открытым пунктом G1; local TLS для credential delivery вместе с client, иначе pairing
  не завершается end-to-end.
- I2: Kerugi bout aggregate поверх единого журнала; длительности и раунды по [FHR 2024](FHR-RULES-2024.md) §4.1.2,
  §4.1.13, §4.1.14; session snapshot/assignment для client (`DEV-008`) и resync после reconnect; симулятор судей для
  acceptance test. Сейчас client отправляет удар как generic `command` с payload `kerugi_score`, который server ACK-ит без
  scoring: I2 переводит client на `kerugi_score_command` с audit context из snapshot.
- I3: physical-device evidence не заменяется emulator-прогонами I1-I2.

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

Накопленное доказательство - JVM fixture и H2, не real PostgreSQL на clean machine. Детали в
[ADR-003](adr/ADR-003-managed-postgresql.md).

| Компонент | Подтверждено тестами | Открыто |
|-----------|----------------------|---------|
| Versioned JDBC migrations `V1`-`V5` | Durable journal, restart и idempotency на H2 | Единая схема журнала (I1) |
| `PostgresProvisioner` | Configured `initdb`, требование `PG_VERSION`, reuse готового cluster, отказ перезаписывать nonempty directory без PostgreSQL marker | - |
| `ManagedPostgres` | Supervision child process; диагностика конфликта loopback-порта, ошибки запуска и аварийного exit; `restart()` после exit | - |
| `ManagedPostgresRuntime`, `PostgresRuntimeConfiguration` | JDBC readiness, создание database, JDBC URL публикуется только пока child запущен; platform-specific commands, cluster вне installation directory; `withAvailableLoopbackPort()` выбирает `127.0.0.1` port, поэтому проверка занятости в `start()` обязательна | Не подключены к `Server.start()` и desktop (I1) |
| `RealPostgresLifecycleTest` | init/start/migration/restart/journal recovery против явно указанного bundle | На CI пропускается без bundle; clean Windows/macOS (I1, I7) |

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
| `POST /v1/pairing-requests` | Валидация фамилии и platform, pending request, dedup retry по device ID; хранится только SHA-256 hash client delivery proof | Persistent registry (I1) |
| Operator pairing service | Идемпотентные approve/reject/revoke; reconnect credential только при принятии и inactive после отзыва; проекция device ID, platform и `connected`/`disconnected` без surname и credential; anonymous LAN decision/revoke endpoints отсутствуют | Desktop UI и durable state (I1) |
| `GET /v1/pairing-status/{requestId}` | Typed pending/accepted/rejected; rejection code только для rejected; credential только по matching proof на secure transport | Local TLS отсутствует, поэтому HTTP runtime credential не раскрывает (I3) |
| `/v1/realtime` handshake | Versioned handshake только для active credential; unknown, revoked и incompatible-version отклоняются без pending state | - |
| Typed commands и ACK | Bounded envelope, idempotent ACK по event ID; с `JdbcPeerJournal` append до ACK, identical retry после recreation, cursor-based resync; typed rejections для malformed, oversized, post-revocation, conflicting и journal failure | Default server in-memory (I1); client outbox/replay (I3) |
| `clock_sync` | Echo client timestamp и UTC server receive/send timestamps; invalid timestamp получает typed rejection без закрытия сессии | Client offset и artificial delay (I3) |
| `heartbeat` | `heartbeat_ack`/`heartbeat_rejected`; `heartbeat_timeout` закрывает socket, valid heartbeat продлевает deadline; close помечает устройство disconnected | Client scheduling и reconnect UX (I3) |
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
| `SessionProjection`, `SessionLifecycleJournal`, `JdbcSessionLifecycleJournal` | `prepared`/`running`/`paused`/`completed`/`cancelled`; только local owner `IN_PROGRESS`; append до замены projection; idempotent retry, rejection conflicting ID; rebuild из JDBC после recreation | Не вызывается из production entry point (I1, I2) |
| `BracketOwnership` | Immutable local owner; чужая команда отклоняется без изменения projection | Persistence сетки (I4); P2P claims post-v1 |
| `session_lifecycle_command`, `session_state_updated` | ACK после применения; публикация всем authenticated sockets только для нового события | Operator authorization и desktop datasource (I2) |
| `GET /v1/health`, `DiagnosticContext` | Typed liveness без pairing identity и PII; stable IDs без author, payload и credential | Logging backend и persistence readiness (I1) |

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

Domain, JDBC/H2 recovery и two-socket Ktor contract tests. Это partial evidence для `KER-001`-`KER-009`, `KER-011`-`KER-013`,
`KER-015`, `NET-001`, `NET-003`, `SES-004`, `NFR-011` и `NFR-012`. Общий пробел: production wiring, desktop arbiter/watcher,
client outbox/reconnect и physical devices (I2, I3).

| Компонент | Подтверждено тестами | Открыто |
|-----------|----------------------|---------|
| `KerugiScoringEngine` | 2 или 3 боковых судьи, quorum и coincidence window (по умолчанию 2 и `1000 мс`); непересекающиеся окна одного участника; distinct configured judges; `BODY=1`/`HEAD=2`, минимальная оценка при конфликте; audit каждого окна, включая insufficient quorum | - |
| `KerugiScoreJournal`, `JdbcKerugiScoreJournal` (`V3`), `kerugi_score_command` | Raw candidate append до пересчёта; rejection foreign judge и conflicting ID; rebuild; ACK после применения; `kerugi_score_updated` только для нового события | - |
| `kerugi_operator_action_command` | `THROW`, `SPIN_BONUS` и `GAMJEOM` append-only с автором; Gamjeom увеличивает счёт соперника; отдельная audit projection | Desktop UI (I2) |
| `kerugi_score_correction_command` | Компенсирующее событие со ссылкой на candidate или action; исходное событие сохраняется, projection исключает эффект | Сброс с причиной `SES-006` (I2) |
| Gamjeom warning и `kerugi_disqualification_confirmed` | Warning при effective sum `10`; одно подтверждение local owner только после warning | - |
| `KerugiTimerJournal`, `JdbcKerugiTimerJournal` (`V4`) | `START`/`PAUSE`/`RESUME`/`STOP`, отдельный `ROUND_BREAK`; `kerugi_timer_updated` только для нового события | Длительности по возрасту, remaining time, раунды `KER-010` (I2) |
| `KerugiResultJournal`, `JdbcKerugiResultJournal` (`V5`) | Одно решение local owner с причиной `final_score` или `golden_round`; второе решение отклоняется | Проверка по authoritative score, связь с session/bracket `SES-007` (I2, I4) |

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
| Server-only slices не закрывают gates        | Высокая     | Высокое     | Инкременты I1-I8 со сценарием в production entry point и закрытием требований   |

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
- [Нормативные правила ФХР 2024](FHR-RULES-2024.md)
- [Клиентский roadmap](https://github.com/Martial-Arts-Sport-Software/u-judge-client/blob/main/docs/ROADMAP.md)

# U'Judge Server

Desktop-приложение площадки и локальный server U'Judge System для проведения соревнований по хапкидо на Windows и macOS.

Проект находится в разработке. U'Judge v1 Pilot предназначен для полевого испытания в изолированной LAN и не является production-релизом.

## Возможности pilot

- управление площадкой, устройствами, текущим поединком и таймером;
- импорт готовых турнирных сеток из XLSX;
- Kerugi, Tanbon и четыре технические дисциплины;
- арбитр и полноэкранное watcher-табло;
- append-only аудит, история, XLSX/CSV export и backup;
- локальный PostgreSQL на каждой площадке;
- P2P-синхронизация до 8 равноправных площадок без центрального server;
- работа при сетевом разделении и сходимость после reconnect.

## Текущее состояние

Реализованы Compose Desktop shell, базовая навигация, Ktor CIO server на `0.0.0.0:8080`, mDNS-публикация `_u-judge._tcp` и CI на `push`/`pull_request`. CI валидирует Gradle Wrapper и whitespace в diff, затем собирает проект на JDK 21 через `./gradlew build`. Первый P2P spike подтвердил in-memory event journal с идемпотентной доставкой, диагностикой sequence gaps и сходимостью трёх peers после partition; решение описано в [ADR-001](docs/adr/ADR-001-event-envelope.md). Начат client protocol: `GET /v1/metadata` публикует версию, capabilities и identity площадки до pairing; анонимный `POST /score` удалён. Pairing, WebSocket, durable ACK и reconnect ещё не реализованы. Турнирная модель, scoring, PostgreSQL lifecycle, production P2P transport, импорт и аудит также не реализованы.

Начат PostgreSQL persistence spike: versioned JDBC migration сохраняет и восстанавливает envelope Stage 1, а
`ManagedPostgres` супервизирует сконфигурированный дочерний процесс и сообщает конфликт loopback-порта, ошибку запуска или
аварийный exit. Реальный PostgreSQL lifecycle, provisioning и clean-machine proof для Windows/macOS ещё не реализованы.
Решение и ограничения зафиксированы в [ADR-003](docs/adr/ADR-003-managed-postgresql.md).

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

## Документация

- [Описание U'Judge System](docs/PROJECT.md)
- [Системные и серверные требования](docs/REQUIREMENTS.md)
- [Roadmap U'Judge v1 Pilot](docs/ROADMAP.md)
- [Клиент U'Judge](https://github.com/Martial-Arts-Sport-Software/u-judge-client)
- [Макеты Figma](https://www.figma.com/design/x5vY9DbXh3a0kv0lBPcNru/Judging-app)

## Ограничения pilot

- готовые сетки импортируются, генерация жеребьёвки не входит в scope;
- Windows signing и macOS notarization отложены до этапа после pilot;
- P2P protocol и упаковка PostgreSQL должны пройти обязательные ADR/spikes;
- baseline схемы импорта зафиксирован как `df-template-v1`; adapter и его тесты входят в этап 4.

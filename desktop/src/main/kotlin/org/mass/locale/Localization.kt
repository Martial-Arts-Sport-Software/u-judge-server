package org.mass.locale

import org.mass.State

/**
 * Singleton for all text in different languages
 * @property strings map of all text, every map key is sub-map for specific lang
 */
object Localization {
    private val strings = mapOf(
        "en" to mapOf(
            //Entry Screen
            "entry_title" to "U'JUDGE - Server",
            "entry_quote" to "A good judge is always fair...",
            "entry_description" to "Olympic Taekwondo\n" +
                    "Sport and combat Hapkido",
            "entry_devices_connection" to "Connect devices",
            "entry_judge_surname" to "Judge surname",

            //Devices Connection Screen
            "devices_connection_connected" to "Connected devices",
            "devices_connection_available" to "Available devices",
            "devices_config_title" to "DEVICE CONFIGURATION",
            "devices_code_label" to "Server code",
            "devices_host_unknown" to "This computer",
            "devices_status_server" to "Server accepts judge connections",
            "devices_status_database" to "Events are stored in the local database",
            "devices_status_tls" to "Connections are encrypted",
            "devices_paired_empty_hint" to "Approve a judge from the available devices to see it here",
            "devices_pending_empty_hint" to "Ask the judge to connect by the server address and wait for the request",
            "devices_server_address_label" to "Server IP address:",
            "devices_server_address_other" to "Other addresses of this computer",
            "devices_server_address_unknown" to "No network found, port %s",
            "devices_code_hint" to "Approve a judge only if the phone shows the same code.",
            "devices_server_failed" to "Server or database failure: see the red message above",
            "devices_code_unavailable" to "The server is not running, pairing is unavailable",
            "devices_pending_empty" to "No requests yet",
            "devices_paired_empty" to "No connected devices",
            "devices_state_connected" to "connected",
            "devices_state_disconnected" to "disconnected",
            "devices_approve" to "Approve",
            "devices_reject" to "Reject",
            "devices_revoke" to "Revoke",
            "devices_cancel" to "Cancel",
            "devices_revoke_title" to "Revoke the device?",
            "devices_revoke_text" to "%s (%s) will be disconnected and its new events rejected. The judge will have to pair again.",
            "devices_action_failed" to "The decision was not saved: %s",

            //Server Status
            "server_status_stopped" to "Server is stopped",
            "server_status_starting" to "Starting the server and database…",
            "server_status_running" to "Server is running on port %d; events are stored in the local database",
            "server_status_failed" to "Server or database failure: %s. New events are not accepted.",
            "server_status_restart" to "Restart",

            //Routes Titles
            "entry" to "Entry",
            "devices_connection" to "Devices connection",

            "discipline_title" to "Choose discipline",
            "discipline_kerugi" to "Kerugi",
            "discipline_hosinsool" to "Hosinsool",
            "discipline_tanbon" to "Tanbon",
            "discipline_freestyle_weapon" to "Freestyle with weapon",
            "discipline_freestyle_pair" to "Pair freestyle",
            "discipline_freestyle_group" to "Group freestyle",

            "category_title" to "Choose category",
            "category_juniors" to "Younger juniors / cadets",
            "category_adults" to "Juniors / Adults",

            "kerugi_bout" to "Bout",
            "kerugi_judge" to "Judge",
            "kerugi_judge_empty" to "Not specified",

            "settings_title" to "Settings",
            "settings_start_fight" to "Start a new fight",
            "settings_start_performance" to "Start a new performance",
            "settings_choose_category" to "Choose category",
            "settings_choose_discipline" to "Choose discipline",

            "warning_title" to "Arbitrator's attention",
            "warning_continue" to "Continue",

            "connection_lost_title" to "Lost connection",
            "connection_lost_reconnect" to "Re-connect",
            "connection_lost_change_server" to "Changer server address",

            "hosinsool_technique" to "Technique",
            "hosinsool_presentation" to "Presentation",
            "hosinsool_result" to "Result",

            "hosinsool-presentation-criteria-1" to "Realism",
            "hosinsool-presentation-criteria-2" to "Power, speed, energy blast (kehup)",
            "hosinsool-presentation-criteria-3" to "Balance, technique of nakpok",
            "hosinsool-presentation-criteria-4" to "Harmony, timing",

            "hosinsool-result-sum" to "Total",
            "hosinsool-result-technique" to "Technique",
            "hosinsool-result-presentation" to "Presentation",

            "hosinsool-result-send-btn" to "Send",
            "hosinsool-result-save-btn" to "Save",

            "hosinsool-info-title" to "Information",

            "freestyle-pair-presentation-criteria-1" to "Realism, creativity",
            "freestyle-pair-presentation-criteria-2" to "Power, speed, energy blast (kehup)",
            "freestyle-pair-presentation-criteria-3" to "Balance, technique of nakpok, acrobatic elements",
            "freestyle-pair-presentation-criteria-4" to "Music, choreography",
            "freestyle-extra-points" to "Penalty points",

            "freestyle-weapon-technique-criterion-1" to "Defense / offense techniques with weapon",
            "freestyle-weapon-technique-criterion-2" to "Jump kicks",
            "freestyle-weapon-technique-criterion-3" to "Rotation kicks",
            "freestyle-weapon-technique-criterion-4" to "Manipulations with weapon",
            "freestyle-weapon-technique-criterion-5" to "Stands, movements",
            "freestyle-weapon-technique-criterion-6" to "Acrobatics",

            "freestyle-weapon-presentation-criteria-1" to "Realism, creativity",
            "freestyle-weapon-presentation-criteria-2" to "Power, speed, energy blast (kehup)",
            "freestyle-weapon-presentation-criteria-3" to "Balance",
            "freestyle-weapon-presentation-criteria-4" to "Music, choreography",

            "freestyle-group-technique-criterion-1" to "Offense & defense techniques",
            "freestyle-group-technique-criterion-2" to "Items breaking",
            "freestyle-group-technique-criterion-3" to "Kicking techniques",
            "freestyle-group-technique-criterion-4" to "Weapon skills",
            "freestyle-group-technique-criterion-5" to "Dynamics, movement",
            "freestyle-group-technique-criterion-6" to "Acrobatics",

            "freestyle-group-presentation-criteria-1" to "Realism, creativity",
            "freestyle-group-presentation-criteria-2" to "Power, speed, energy blast (kehup)",
            "freestyle-group-presentation-criteria-3" to "Balance, falling techniques (nakpok)",
            "freestyle-group-presentation-criteria-4" to "Music, choreography",
        ),

        "ru" to mapOf(
            //Entry Screen
            "entry_title" to "U'JUDGE - Сервер",
            "entry_quote" to "Хороший судья беспристрастен...",
            "entry_description" to "Олимпийское Тхэквондо\n" +
                    "Спортивное и боевое Хапкидо",
            "entry_devices_connection" to "Подключение устройств",
            "entry_judge_surname" to "Фамилия арбитра",

            //Devices Connection Screen
            "devices_connection_connected" to "Подключённые устройства",
            "devices_connection_available" to "Доступные устройства",
            "devices_config_title" to "КОНФИГУРАЦИЯ УСТРОЙСТВ",
            "devices_code_label" to "Код сервера",
            "devices_host_unknown" to "Этот компьютер",
            "devices_status_server" to "Сервер принимает подключения судей",
            "devices_status_database" to "События сохраняются в локальной базе",
            "devices_status_tls" to "Соединение зашифровано",
            "devices_paired_empty_hint" to "Подтвердите судью в доступных устройствах, и он появится здесь",
            "devices_pending_empty_hint" to "Попросите судью подключиться по адресу сервера и дождитесь запроса",
            "devices_server_address_label" to "IP адрес сервера:",
            "devices_server_address_other" to "Другие адреса этого компьютера",
            "devices_server_address_unknown" to "Сеть не найдена, порт %s",
            "devices_code_hint" to "Подтверждайте судью, только если на телефоне тот же код.",
            "devices_server_failed" to "Сбой сервера или базы данных: см. красное сообщение сверху",
            "devices_code_unavailable" to "Сервер не запущен, подключение устройств недоступно",
            "devices_pending_empty" to "Пока нет запросов",
            "devices_paired_empty" to "Подключённых устройств нет",
            "devices_state_connected" to "на связи",
            "devices_state_disconnected" to "не на связи",
            "devices_approve" to "Подтвердить",
            "devices_reject" to "Отклонить",
            "devices_revoke" to "Отозвать",
            "devices_cancel" to "Отмена",
            "devices_revoke_title" to "Отозвать устройство?",
            "devices_revoke_text" to "%s (%s) будет отключено, новые события с него будут отклоняться. Судье придётся подключиться заново.",
            "devices_action_failed" to "Решение не сохранено: %s",

            //Server Status
            "server_status_stopped" to "Сервер остановлен",
            "server_status_starting" to "Запуск сервера и базы данных…",
            "server_status_running" to "Сервер работает на порту %d, события сохраняются в локальной базе",
            "server_status_failed" to "Сбой сервера или базы данных: %s. Новые события не принимаются.",
            "server_status_restart" to "Перезапустить",

            //Routes Titles
            "entry" to "Вход",
            "devices_connection" to "Подключение устройств",

            "discipline_title" to "Выберите дисциплину",
            "discipline_kerugi" to "Весовые категории",
            "discipline_hosinsool" to "Приёмы самообороны",
            "discipline_tanbon" to "Танбон",
            "discipline_freestyle_weapon" to "Комплекс свободный",
            "discipline_freestyle_pair" to "Поединок постановочный - пара",
            "discipline_freestyle_group" to "Поединок постановочный - группа",

            "category_title" to "Выберите категорию",
            "category_juniors" to "Младшие юноши / Кадеты",
            "category_adults" to "Юниоры / Взрослые",

            "kerugi_bout" to "Поединок",
            "kerugi_judge" to "Судья",
            "kerugi_judge_empty" to "Не указан",

            "settings_title" to "Настройки",
            "settings_start_fight" to "Начать новый поединок",
            "settings_start_performance" to "Начать новое выступление",
            "settings_choose_category" to "Выбрать категорию",
            "settings_choose_discipline" to "Выбрать дисциплину",

            "warning_title" to "Поднятая рука",
            "warning_continue" to "Продолжить",

            "connection_lost_title" to "Ошибка подключения",
            "connection_lost_reconnect" to "Переподключиться",
            "connection_lost_change_server" to "Изменить адрес сервера",

            "hosinsool_technique" to "Техника",
            "hosinsool_presentation" to "Презентация",
            "hosinsool_result" to "Результат",

            "hosinsool-presentation-criteria-1" to "Реалистичность",
            "hosinsool-presentation-criteria-2" to "Сила, скорость, выражение энергии (кихап)",
            "hosinsool-presentation-criteria-3" to "Баланс, техники страховки",
            "hosinsool-presentation-criteria-4" to "Гармония, тайминг",

            "hosinsool-result-sum" to "Итог",
            "hosinsool-result-technique" to "Техника",
            "hosinsool-result-presentation" to "Презентация",

            "hosinsool-result-send-btn" to "Отправить",
            "hosinsool-result-save-btn" to "Сохранить",

            "hosinsool-info-title" to "Справка",

            "freestyle-pair-presentation-criteria-1" to "Реалистичность, креативность",
            "freestyle-pair-presentation-criteria-2" to "Сила, скорость, выражение энергии (кихап)",
            "freestyle-pair-presentation-criteria-3" to "Баланс, техники страховки, элементы акробатики",
            "freestyle-pair-presentation-criteria-4" to "Музыка, хореография",
            "freestyle-extra-points" to "Штрафные баллы",

            "freestyle-weapon-technique-criterion-1" to "Ударные и защитные техники с оружием",
            "freestyle-weapon-technique-criterion-2" to "Удары ногами в прыжке",
            "freestyle-weapon-technique-criterion-3" to "Удары ногами во вращении",
            "freestyle-weapon-technique-criterion-4" to "Манипуляции с оружием",
            "freestyle-weapon-technique-criterion-5" to "Стойки, передвижения",
            "freestyle-weapon-technique-criterion-6" to "Акробатика",

            "freestyle-weapon-presentation-criteria-1" to "Реалистичность, креативность",
            "freestyle-weapon-presentation-criteria-2" to "Сила, скорость, выражение энергии (кихап)",
            "freestyle-weapon-presentation-criteria-3" to "Баланс",
            "freestyle-weapon-presentation-criteria-4" to "Музыка, хореография",

            "freestyle-group-technique-criterion-1" to "Техники защиты и нападения",
            "freestyle-group-technique-criterion-2" to "Разбивание предметов",
            "freestyle-group-technique-criterion-3" to "Ударные техники ногами",
            "freestyle-group-technique-criterion-4" to "Навыки владения оружием",
            "freestyle-group-technique-criterion-5" to "Динамика, передвижение",
            "freestyle-group-technique-criterion-6" to "Акробатика",

            "freestyle-group-presentation-criteria-1" to "Реалистичность, креативность",
            "freestyle-group-presentation-criteria-2" to "Сила, скорость, выражение энергии (кихап)",
            "freestyle-group-presentation-criteria-3" to "Баланс, техники страховки",
            "freestyle-group-presentation-criteria-4" to "Музыка, хореография",
        )
    )
    fun getString(key: String): String {
        return strings[State.currentLocale]
            ?.get(key) ?: strings["ru"]?.get(key) ?: key
    }
}
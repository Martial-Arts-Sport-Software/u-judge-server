package org.mass.applications

/**
 * Field rules of «Требования к заполнению заявок» (ADR-005). Each check returns the reason shown to the operator, or null
 * when the value is valid; every violation blocks the import.
 */
internal object ApplicationFieldRules {
    private val nameWord = Regex("^\\p{L}{2,}(-\\p{L}{2,})*$")
    private const val COACH = "\\p{Lu}\\p{Ll}+(-\\p{Lu}\\p{Ll}+)* \\p{Lu}\\.(\\p{Lu}\\.)?"
    private val coaches = Regex("^$COACH(, $COACH)+$")
    private val weight = Regex("^(до|свыше) \\d+([.,]\\d+)? кг$")
    private val technicalQualification = Regex("^((10|[1-9]) гып|1 пум|(10|[1-9]) дан)$")

    val sportQualifications = listOf("МС", "КМС", "1р", "2р", "3р", "1юн", "2юн", "3юн", "б/р")

    val federalDistricts = listOf("ЦФО", "СЗФО", "ЮФО", "СКФО", "ПФО", "УФО", "СФО", "ДФО", "Москва", "Санкт-Петербург")

    /** Subjects of the Russian Federation by their full names; the suffixed forms are also accepted without the suffix. */
    val regions = listOf(
        "Республика Адыгея", "Республика Алтай", "Республика Башкортостан", "Республика Бурятия", "Республика Дагестан",
        "Донецкая Народная Республика", "Республика Ингушетия", "Кабардино-Балкарская Республика", "Республика Калмыкия",
        "Карачаево-Черкесская Республика", "Республика Карелия", "Республика Коми", "Республика Крым",
        "Луганская Народная Республика", "Республика Марий Эл", "Республика Мордовия", "Республика Саха (Якутия)",
        "Республика Северная Осетия - Алания", "Республика Татарстан", "Республика Тыва", "Удмуртская Республика",
        "Республика Хакасия", "Чеченская Республика", "Чувашская Республика - Чувашия",
        "Алтайский край", "Забайкальский край", "Камчатский край", "Краснодарский край", "Красноярский край",
        "Пермский край", "Приморский край", "Ставропольский край", "Хабаровский край",
        "Амурская область", "Архангельская область", "Астраханская область", "Белгородская область", "Брянская область",
        "Владимирская область", "Волгоградская область", "Вологодская область", "Воронежская область",
        "Запорожская область", "Ивановская область", "Иркутская область", "Калининградская область",
        "Калужская область", "Кемеровская область - Кузбасс", "Кировская область", "Костромская область",
        "Курганская область", "Курская область", "Ленинградская область", "Липецкая область", "Магаданская область",
        "Московская область", "Мурманская область", "Нижегородская область", "Новгородская область",
        "Новосибирская область", "Омская область", "Оренбургская область", "Орловская область", "Пензенская область",
        "Псковская область", "Ростовская область", "Рязанская область", "Самарская область", "Саратовская область",
        "Сахалинская область", "Свердловская область", "Смоленская область", "Тамбовская область", "Тверская область",
        "Томская область", "Тульская область", "Тюменская область", "Ульяновская область", "Херсонская область",
        "Челябинская область", "Ярославская область",
        "Москва", "Санкт-Петербург", "Севастополь",
        "Еврейская автономная область",
        "Ненецкий автономный округ", "Ханты-Мансийский автономный округ - Югра", "Чукотский автономный округ",
        "Ямало-Ненецкий автономный округ",
    )

    private val normalizedRegions: Map<String, String> = regions.flatMap { region ->
        listOf(normalize(region) to region) + (region.substringBefore(" - ", "").takeIf { it.isNotEmpty() }
            ?.let { listOf(normalize(it) to region) } ?: emptyList())
    }.toMap()

    fun fullName(value: String): String? {
        val words = value.split(' ').filter(String::isNotEmpty)
        return when {
            words.size < 2 -> "ФИО указывается полностью: фамилия и имя (и отчество)"
            words.any { !nameWord.matches(it) } -> "ФИО указывается полностью, без инициалов и сокращений"
            else -> null
        }
    }

    fun weight(value: String): String? =
        if (weight.matches(value)) null else "Весовая категория указывается как «до 24 кг» или «свыше 24 кг»"

    /** The canonical spelling of a sport qualification, or null when it is not in the list. */
    fun sportQualification(value: String): String? = sportQualifications.firstOrNull { it.equals(value, ignoreCase = true) }

    fun technicalQualification(value: String): String? = if (technicalQualification.matches(value.lowercase())) {
        null
    } else {
        "Пояс указывается как «10 гып» … «1 гып», «1 пум», «1 дан» … «10 дан», с пробелом"
    }

    /** The canonical full region name, or null when [value] is not a full subject name. */
    fun region(value: String): String? = normalizedRegions[normalize(value.removePrefix("г. "))]

    fun federalDistrict(value: String): String? = federalDistricts.firstOrNull { it.equals(value, ignoreCase = true) }

    fun coaches(value: String): String? = if (coaches.matches(value)) {
        null
    } else {
        "Минимум 2 тренера через запятую с пробелом: «Иванов И.И., Петров П.П.» (при одном тренере указать его дважды)"
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .replace(Regex("\\s*[-‐‑‒–—]\\s*"), "-")
        .replace(Regex("\\s+"), " ")
        .trim()
}

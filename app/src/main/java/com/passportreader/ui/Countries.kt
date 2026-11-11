package com.passportreader.ui

import java.util.Locale

/** ICAO 9303 three-letter codes to readable names. */
object Countries {
    private val special = mapOf(
        "D" to "Germany", "GBD" to "United Kingdom (Overseas Territories citizen)",
        "GBN" to "United Kingdom (National Overseas)", "GBO" to "United Kingdom (Overseas citizen)",
        "GBP" to "United Kingdom (Protected person)", "GBS" to "United Kingdom (Subject)",
        "RKS" to "Kosovo", "EUE" to "European Union", "UNO" to "United Nations",
        "UNA" to "United Nations (agency)", "UNK" to "Kosovo (UNMIK)", "XOM" to "Sovereign Military Order of Malta",
        "XXA" to "Stateless", "XXB" to "Refugee", "XXC" to "Refugee (other)", "XXX" to "Unspecified nationality",
        "UTO" to "Utopia (specimen)",
    )
    private val byAlpha3: Map<String, String> by lazy {
        Locale.getISOCountries().mapNotNull { a2 ->
            val l = Locale.Builder().setRegion(a2).build()
            runCatching { l.isO3Country.uppercase(Locale.ROOT) to l.getDisplayCountry(Locale.ENGLISH) }.getOrNull()
        }.toMap()
    }

    fun name(code: String?): String {
        if (code.isNullOrBlank()) return ""
        val c = code.trim('<', ' ').uppercase(Locale.ROOT)
        val n = special[c] ?: byAlpha3[c]
        return if (n != null) "$n ($c)" else c
    }
}

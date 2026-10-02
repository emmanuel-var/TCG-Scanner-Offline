package com.tcgscanner.offline.data.repo

/** RFC 4180 helpers for the CSV export. */
object CsvFormat {
    val HEADER = listOf(
        "game", "set_code", "set_name", "number", "name", "rarity", "variant", "type", "condition",
        "grading_company", "grade", "quantity", "trade_quantity", "unit_value_usd", "total_value_usd",
        "value_basis", "added_at_utc", "card_id"
    )

    /** Quotes when needed and neutralises spreadsheet formula injection (=, +, -, @ at start). */
    fun escape(raw: String): String {
        var v = raw
        if (v.isNotEmpty() && v[0] in "=+-@\t\r" && v.toDoubleOrNull() == null) v = "'$v"
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
}

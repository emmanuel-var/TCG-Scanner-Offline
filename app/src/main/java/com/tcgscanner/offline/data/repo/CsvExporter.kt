package com.tcgscanner.offline.data.repo

import com.tcgscanner.offline.core.CardCondition
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.GradingCompany
import com.tcgscanner.offline.core.Money
import com.tcgscanner.offline.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Free, unlimited CSV export (RFC 4180, UTF-8 with BOM so Excel reads accents and kana correctly). */
class CsvExporter(private val db: AppDatabase) {

    suspend fun export(games: Set<GameId>?, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val rows = db.collection().getAll()
            .filter { games == null || GameId.fromCode(it.item.gameId) in games }
            .map { it.toEntry() }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("﻿")
            w.write(CsvFormat.HEADER.joinToString(",") + "\r\n")
            for (e in rows) {
                val item = e.item
                val card = e.card
                val company = GradingCompany.fromCode(item.gradeCompany)
                val fields = listOf(
                    item.gameId,
                    card?.setCode.orEmpty(),
                    card?.setName.orEmpty(),
                    card?.number.orEmpty(),
                    card?.name ?: item.cardId,
                    card?.rarity.orEmpty(),
                    item.variant,
                    if (company.isGraded) "graded" else "raw",
                    if (company.isGraded) "" else CardCondition.fromCode(item.condition).code,
                    if (company.isGraded) company.code else "",
                    if (company.isGraded) (item.gradeX10 / 10.0).toString() else "",
                    item.quantity.toString(),
                    item.tradeQuantity.toString(),
                    Money.plain(e.unit.usd),
                    Money.plain(e.totalUsd),
                    if (e.unit.estimated) "estimate" else if (e.unit.hasPrice) "market" else "none",
                    iso.format(Date(item.addedAt)),
                    item.cardId
                )
                w.write(fields.joinToString(",") { CsvFormat.escape(it) } + "\r\n")
            }
        }
        rows.size
    }
}

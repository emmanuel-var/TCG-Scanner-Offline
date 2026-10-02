package com.tcgscanner.offline.trade

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/** One line of a Trade Binder as exchanged between two devices. */
@Serializable
data class TradeItem(
    val cardId: String,
    val name: String,
    val setName: String,
    val number: String,
    val variant: String,
    /** "" for raw cards, otherwise e.g. "PSA 10". */
    val grade: String = "",
    val company: String = "none",
    val gradeX10: Int = 0,
    val condition: String = "NM",
    val qty: Int,
    val usd: Double,
    val imageUrl: String? = null
) {
    val key: String get() = "$cardId|$variant|$company|$gradeX10|$condition"
}

@Serializable
data class TradeSel(val key: String, val qty: Int)

/** Wire protocol. Every message is a small JSON document (Nearby limits byte payloads to 32 KB). */
@Serializable
data class WireMessage(
    val type: String,
    val nick: String? = null,
    val game: String? = null,
    val pricesAt: Long? = null,
    val seq: Int = 0,
    val total: Int = 0,
    val items: List<TradeItem> = emptyList(),
    val rev: Int = 0,
    /** Proposal: what the SENDER gives / what the RECEIVER gives. */
    val senderGives: List<TradeSel> = emptyList(),
    val receiverGives: List<TradeSel> = emptyList()
) {
    companion object {
        const val HELLO = "hello"
        const val BINDER = "binder"
        const val PROPOSAL = "proposal"
        const val ACCEPT = "accept"
    }
}

enum class Verdict { FAIR, YOU_GIVE_MORE, YOU_GET_MORE, EMPTY }

data class TradeBalance(val giveUsd: Double, val getUsd: Double) {
    val difference: Double get() = getUsd - giveUsd

    val verdict: Verdict
        get() = when {
            giveUsd == 0.0 && getUsd == 0.0 -> Verdict.EMPTY
            abs(difference) <= max(1.0, 0.05 * max(giveUsd, getUsd)) -> Verdict.FAIR
            difference > 0 -> Verdict.YOU_GET_MORE
            else -> Verdict.YOU_GIVE_MORE
        }
}

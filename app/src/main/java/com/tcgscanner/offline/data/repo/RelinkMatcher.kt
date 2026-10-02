package com.tcgscanner.offline.data.repo

import com.tcgscanner.offline.core.Text

/** What a user row remembers about "its" card, independent of any catalog row. */
data class CardSnapshot(
    val name: String,
    val setCode: String,
    val setName: String,
    val number: String,
    val printTag: String = ""
)

data class RelinkCandidate(
    val id: String,
    val name: String,
    val setCode: String,
    val setName: String,
    val number: String,
    val printTag: String = ""
)

/**
 * Heuristic that re-attaches a user's card to the catalog after the source changed. The collector number
 * must match (it is the most stable property across sources); name, set and print tag then rank the
 * candidates. Ambiguity is never guessed: when two cards score alike the row stays unlinked and keeps showing
 * its snapshot, which is safer than attaching someone's card to the wrong print.
 */
object RelinkMatcher {
    const val MIN_SCORE = 0.80
    const val MIN_GAP = 0.03

    fun score(snap: CardSnapshot, c: RelinkCandidate): Double {
        val number = Text.numberKey(snap.number)
        if (number.isEmpty() || number != Text.numberKey(c.number)) return 0.0

        val nameSim = Text.similarity(Text.nameKey(snap.name), Text.nameKey(c.name)).let { if (snap.name.isBlank()) 0.0 else it }
        val setScore = when {
            snap.setCode.isNotBlank() && com.tcgscanner.offline.core.CardKeys.setKey(snap.setCode) == com.tcgscanner.offline.core.CardKeys.setKey(c.setCode) -> 1.0
            snap.setName.isNotBlank() && c.setName.isNotBlank() -> Text.similarity(Text.nameKey(snap.setName), Text.nameKey(c.setName))
            else -> 0.0
        }
        val tagScore = if (com.tcgscanner.offline.core.CardKeys.tagKey(snap.printTag) == com.tcgscanner.offline.core.CardKeys.tagKey(c.printTag)) 1.0 else 0.0
        return 0.45 + 0.30 * nameSim + 0.20 * setScore + 0.05 * tagScore
    }

    /** The id of the single clearly-best candidate, or null when nothing matches well enough or it is ambiguous. */
    fun best(snap: CardSnapshot, candidates: List<RelinkCandidate>): String? {
        val scored = candidates.map { it to score(snap, it) }.filter { it.second >= MIN_SCORE }.sortedByDescending { it.second }
        val top = scored.firstOrNull() ?: return null
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && top.second - runnerUp.second < MIN_GAP) return null
        return top.first.id
    }
}

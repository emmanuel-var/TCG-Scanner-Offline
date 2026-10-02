package com.tcgscanner.offline.core

/**
 * Stable identity of a card print, independent of whichever source delivered it.
 *
 * `pokemon:base1:4`  = game + set + collector number. Network ids (UUIDs, product ids, URLs) change when the
 * user switches the catalog URL; this key does not, so collection rows and decks stay attached to the card.
 * [printTag] separates prints that share set + number (Yu-Gi-Oh! rarities, One Piece alternate arts).
 */
object CardKeys {
    fun of(game: GameId, setCode: String, number: String, printTag: String = ""): String =
        buildString {
            append(game.code).append(':')
            append(setKey(setCode)).append(':')
            append(Text.numberKey(number))
            val tag = tagKey(printTag)
            if (tag.isNotEmpty()) append(':').append(tag)
        }

    /** Set codes compare case-insensitively and ignore surrounding blanks / separators. */
    fun setKey(setCode: String): String = setCode.trim().lowercase().replace(Regex("\\s+"), "")

    fun tagKey(tag: String): String = tag.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * The stable key for one downloaded print, made unique within a single sync run. A source that lists two
     * different prints under the same set + number without a tag gets deterministic suffixes (`dup~2`, `dup~3`…),
     * so the second never overwrites the first. Returns (key, printTag actually used).
     */
    fun unique(game: GameId, setCode: String, number: String, printTag: String, seen: MutableMap<String, Int>): Pair<String, String> {
        val base = of(game, setCode, number, printTag)
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        if (n == 1) return base to printTag
        val tag = printTag.ifBlank { "dup" } + "~" + n
        return of(game, setCode, number, tag) to tag
    }
}

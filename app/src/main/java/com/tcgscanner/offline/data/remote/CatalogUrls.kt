package com.tcgscanner.offline.data.remote

import com.tcgscanner.offline.core.GameId

/**
 * Default, credential-free catalog location for each game: open REST endpoints or static JSON dumps
 * maintained by the community. The user can replace any of them in Settings (see [UrlNormalizer]).
 *
 * Community-hosted files can move or disappear; that is exactly why every URL is overridable and why
 * most games also have a secondary public source (see Games.kt).
 */
object CatalogUrls {
    val defaults: Map<GameId, String> = mapOf(
        GameId.POKEMON to "https://api.pokemontcg.io/v2/cards",
        GameId.MTG to "https://api.scryfall.com/bulk-data/default-cards",
        GameId.YGO to "https://db.ygoprodeck.com/api/v7/cardinfo.php",
        GameId.LORCANA to "https://api.lorcana-api.com/cards/all",
        GameId.ONE_PIECE to "https://raw.githubusercontent.com/optcg-community/optcg-data/main/cards.json",
        GameId.DIGIMON to "https://digimoncard.io/api-public/search.php?series=Digimon%20Card%20Game",
        GameId.POKEMON_JP to "https://api.pokemontcg.io/v2/cards?q=language:japanese",
        GameId.DBS_FW to "https://raw.githubusercontent.com/limitless-community/dbs-fw-data/main/cards.json",
        GameId.GUNDAM to "https://raw.githubusercontent.com/bandai-tcg-community/gundam-db/main/cards.json",
        GameId.RIFTBOUND to "https://raw.githubusercontent.com/riftbound-tts/mod-data/main/database.json"
    )

    fun default(game: GameId): String = defaults.getValue(game)
}

/** Cleans up what users paste into the "database URL" field. */
object UrlNormalizer {
    private val githubBlob = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/(.+)$")

    /**
     * Trims, and turns a GitHub "blob" page link into its raw file link, so pasting the URL from the
     * browser address bar just works. Anything else is returned unchanged.
     */
    fun normalize(raw: String): String {
        val url = raw.trim()
        val m = githubBlob.matchEntire(url) ?: return url
        val (owner, repo, rest) = m.destructured
        return "https://raw.githubusercontent.com/$owner/$repo/$rest"
    }

    /** True for an https URL with a host; the app never fetches catalogs over plain HTTP. */
    fun isValid(raw: String): Boolean {
        val url = normalize(raw)
        return url.startsWith("https://") && url.length > "https://x".length &&
            url.removePrefix("https://").substringBefore('/').let { it.isNotBlank() && !it.contains(' ') }
    }
}

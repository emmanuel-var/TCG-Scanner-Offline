package com.tcgscanner.offline.data.remote

import com.tcgscanner.offline.core.GameId

/**
 * Default, credential-free catalog location per game. Games WITHOUT an entry (Gundam, Riftbound, Fusion World)
 * have no stable public source yet: the app asks the user to import a JSON file or paste a community URL
 * in Settings instead of shipping a link that may not exist.
 *
 * [unverified] lists defaults whose exact path I could not confirm; they are tried first but a failure just
 * falls through to the secondary source (see Games.kt) and is reported in Settings.
 */
object CatalogUrls {
    val defaults: Map<GameId, String> = mapOf(
        GameId.POKEMON to "https://api.pokemontcg.io/v2/cards",
        GameId.POKEMON_JP to "https://api.pokemontcg.io/v2/cards?q=language:japanese",
        GameId.MTG to "https://api.scryfall.com/bulk-data/default-cards",
        GameId.YGO to "https://db.ygoprodeck.com/api/v7/cardinfo.php",
        GameId.DIGIMON to "https://digimoncard.io/api-public/search.php?series=Digimon%20Card%20Game",
        GameId.LORCANA to "https://api.lorcana-api.com/cards/all",
        // vegapull-records mirrors the vegapull scraper output: packs.json lists the packs and every pack has a
        // sibling cards_<packId>.json. The parser follows that index (CatalogFormat.VEGAPULL_PACKS).
        GameId.ONE_PIECE to "https://raw.githubusercontent.com/Coko7/vegapull-records/main/data/english/packs.json"
    )

    val unverified: Set<GameId> = setOf(GameId.ONE_PIECE)

    /** null = no built-in source; the user must import a file or paste a URL. */
    fun default(game: GameId): String? = defaults[game]

    fun requiresUserSource(game: GameId): Boolean = game !in defaults
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

package com.tcgscanner.offline.data.remote

import com.tcgscanner.offline.core.GameId

/**
 * Default, credential-free catalog locations per game: open REST endpoints or static JSON dumps maintained by the
 * community. Every one can be replaced by the user in Settings. A game may also have a [backups] URL that is tried
 * automatically when the first one fails.
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
        GameId.ONE_PIECE to "https://raw.githubusercontent.com/Coko7/vegapull-records/main/data/english/packs.json",
        GameId.DBS_FW to "https://raw.githubusercontent.com/dragogodev/cgs/master/Dragon%20Ball%20Super%20Fusion%20World/cgs.json"
    )

    /** Second source tried when [defaults] fails (see SourceId.CATALOG_URL_BACKUP). No game has one right now. */
    val backups: Map<GameId, String> = emptyMap()

    /** Defaults whose path or schema could not be verified when they were added. */
    val unverified: Set<GameId> = setOf(GameId.ONE_PIECE, GameId.DBS_FW)

    /** null = no built-in source; the user must import a file or paste a URL. */
    fun default(game: GameId): String? = defaults[game]

    fun backup(game: GameId): String? = backups[game]

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

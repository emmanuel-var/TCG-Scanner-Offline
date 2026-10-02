package com.tcgscanner.offline.ui.nav

import android.net.Uri

object Routes {
    const val HUB = "hub"
    const val DASHBOARD = "dashboard"
    const val COLLECTION = "collection"
    const val SCAN = "scan"
    const val CATALOG = "catalog"
    const val BINDER = "binder"
    const val SETTINGS = "settings"
    const val DECKS = "decks"
    const val DECK = "deck/{id}"
    const val VALUABLE = "valuable"
    const val SET = "set/{code}"
    const val TRADE = "trade"
    const val ABOUT = "about"

    fun deck(id: Long) = "deck/$id"
    fun set(code: String) = "set/${Uri.encode(code)}"

    val tabs = listOf(DASHBOARD, COLLECTION, SCAN, CATALOG, BINDER)
}

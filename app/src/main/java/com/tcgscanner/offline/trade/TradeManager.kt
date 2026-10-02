package com.tcgscanner.offline.trade

import android.content.Context
import android.os.Build
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.tcgscanner.offline.core.CardCondition
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GameId
import com.tcgscanner.offline.core.GradingCompany
import com.tcgscanner.offline.data.db.AppDatabase
import com.tcgscanner.offline.data.db.CollectionItemEntity
import com.tcgscanner.offline.data.prefs.SettingsStore
import com.tcgscanner.offline.data.repo.AddSpec
import com.tcgscanner.offline.data.repo.CollectionRepository
import com.tcgscanner.offline.data.repo.Valuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class TradePhase { IDLE, SEARCHING, CONNECTING, CONNECTED, DONE, ERROR }

data class Peer(val endpointId: String, val name: String)
data class PendingAuth(val endpointId: String, val name: String, val digits: String)

data class TradeState(
    val phase: TradePhase = TradePhase.IDLE,
    val game: GameId? = null,
    val peers: List<Peer> = emptyList(),
    val pendingAuth: PendingAuth? = null,
    val remoteNick: String = "",
    val remotePricesAt: Long? = null,
    val myPricesAt: Long? = null,
    val myItems: List<TradeItem> = emptyList(),
    val theirItems: List<TradeItem> = emptyList(),
    /** Items I give (keys of [myItems]) and items I receive (keys of [theirItems]). */
    val iGive: Map<String, Int> = emptyMap(),
    val iGet: Map<String, Int> = emptyMap(),
    val rev: Int = 0,
    val acceptedByMe: Boolean = false,
    val acceptedByThem: Boolean = false,
    val applied: Boolean = false,
    val error: String? = null
) {
    val balance: TradeBalance
        get() = TradeBalance(
            giveUsd = iGive.entries.sumOf { (k, q) -> (myItems.firstOrNull { it.key == k }?.usd ?: 0.0) * q },
            getUsd = iGet.entries.sumOf { (k, q) -> (theirItems.firstOrNull { it.key == k }?.usd ?: 0.0) * q }
        )
}

/**
 * Offline device-to-device trading over Google Nearby Connections (Bluetooth / BLE / Wi-Fi Direct /
 * local Wi-Fi chosen automatically, no internet and no server). Only the Trade Binder is shared, only after
 * both users confirm the pairing code, and the remote cards are re-priced with THIS device's local prices.
 */
class TradeManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val scope: CoroutineScope
) {
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(TradeState())
    val state: StateFlow<TradeState> = _state.asStateFlow()

    private var endpointId: String? = null
    private val incoming = ArrayList<TradeItem>()

    // ---- lifecycle ---------------------------------------------------------------------------

    fun start(game: GameId) {
        stop()
        scope.launch {
            val mine = loadMyBinder(game)
            val nick = settings.current().nickname.ifBlank { Build.MODEL ?: "Trader" }
            _state.value = TradeState(
                phase = TradePhase.SEARCHING, game = game, myItems = mine,
                myPricesAt = db.cards().observeSyncStatesOnce().firstOrNull { it.gameId == game.code }?.lastSyncAt
            )
            client.startAdvertising(nick, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener { fail(it.message) }
            client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener { fail(it.message) }
        }
    }

    fun stop() {
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
        endpointId = null
        incoming.clear()
        _state.value = TradeState()
    }

    fun connect(peer: Peer) {
        _state.update { it.copy(phase = TradePhase.CONNECTING) }
        scope.launch {
            val nick = settings.current().nickname.ifBlank { Build.MODEL ?: "Trader" }
            client.requestConnection(nick, peer.endpointId, lifecycle).addOnFailureListener { fail(it.message) }
        }
    }

    fun confirmPairing() {
        val p = _state.value.pendingAuth ?: return
        client.acceptConnection(p.endpointId, payloads).addOnFailureListener { fail(it.message) }
        _state.update { it.copy(pendingAuth = null) }
    }

    fun rejectPairing() {
        val p = _state.value.pendingAuth ?: return
        client.rejectConnection(p.endpointId)
        _state.update { it.copy(pendingAuth = null, phase = TradePhase.SEARCHING) }
    }

    // ---- proposal editing ----------------------------------------------------------------------

    fun setGive(key: String, qty: Int) = edit { s ->
        val max = s.myItems.firstOrNull { it.key == key }?.qty ?: 0
        s.copy(iGive = s.iGive.withQty(key, qty.coerceIn(0, max)))
    }

    fun setGet(key: String, qty: Int) = edit { s ->
        val max = s.theirItems.firstOrNull { it.key == key }?.qty ?: 0
        s.copy(iGet = s.iGet.withQty(key, qty.coerceIn(0, max)))
    }

    private fun Map<String, Int>.withQty(key: String, qty: Int) = if (qty <= 0) this - key else this + (key to qty)

    private fun edit(change: (TradeState) -> TradeState) {
        if (_state.value.phase != TradePhase.CONNECTED) return
        _state.update { change(it).copy(rev = it.rev + 1, acceptedByMe = false, acceptedByThem = false) }
        sendProposal()
    }

    fun accept() {
        val s = _state.value
        if (s.phase != TradePhase.CONNECTED || (s.iGive.isEmpty() && s.iGet.isEmpty())) return
        _state.update { it.copy(acceptedByMe = true) }
        send(WireMessage(WireMessage.ACCEPT, rev = s.rev))
        completeIfBothAccepted()
    }

    private fun completeIfBothAccepted() {
        _state.update { if (it.acceptedByMe && it.acceptedByThem) it.copy(phase = TradePhase.DONE) else it }
    }

    /** Moves the traded cards in the local collection: removes what I gave, adds what I received. */
    suspend fun applyToCollection(collection: CollectionRepository) {
        val s = _state.value
        val game = s.game ?: return
        if (s.phase != TradePhase.DONE || s.applied) return
        val myRows = db.collection().getGame(game.code)
        for ((key, qty) in s.iGive) {
            val item = s.myItems.firstOrNull { it.key == key } ?: continue
            val row = myRows.firstOrNull { r ->
                r.item.cardId == item.cardId && r.item.variant == item.variant && r.item.gradeCompany == item.company &&
                    r.item.gradeX10 == item.gradeX10 && r.item.condition == item.condition
            } ?: continue
            collection.removeCopies(row.item.id, qty)
        }
        for ((key, qty) in s.iGet) {
            val item = s.theirItems.firstOrNull { it.key == key } ?: continue
            if (db.cards().get(item.cardId) == null) continue // unknown card in this catalog
            collection.add(
                game,
                AddSpec(
                    cardId = item.cardId,
                    variant = CardVariant.fromCode(item.variant),
                    condition = CardCondition.fromCode(item.condition),
                    company = GradingCompany.fromCode(item.company),
                    gradeX10 = item.gradeX10,
                    quantity = qty
                )
            )
        }
        _state.update { it.copy(applied = true) }
    }

    // ---- Nearby callbacks ----------------------------------------------------------------------

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            _state.update { s -> if (s.peers.any { it.endpointId == id }) s else s.copy(peers = s.peers + Peer(id, info.endpointName.take(40))) }
        }

        override fun onEndpointLost(id: String) {
            _state.update { s -> s.copy(peers = s.peers.filterNot { it.endpointId == id }) }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            _state.update { it.copy(pendingAuth = PendingAuth(id, info.endpointName.take(40), info.authenticationDigits), remoteNick = info.endpointName.take(40)) }
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                endpointId = id
                runCatching { client.stopAdvertising(); client.stopDiscovery() }
                _state.update { it.copy(phase = TradePhase.CONNECTED, pendingAuth = null) }
                sendHello()
            } else {
                _state.update { it.copy(phase = TradePhase.SEARCHING, pendingAuth = null, error = null) }
            }
        }

        override fun onDisconnected(id: String) {
            if (id == endpointId) {
                endpointId = null
                _state.update { if (it.phase == TradePhase.DONE) it else it.copy(phase = TradePhase.ERROR, error = "DISCONNECTED") }
            }
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            if (bytes.size > MAX_MESSAGE) return
            val msg = runCatching { json.decodeFromString<WireMessage>(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return
            scope.launch { handle(msg) }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) = Unit
    }

    // ---- protocol --------------------------------------------------------------------------------

    private suspend fun handle(msg: WireMessage) {
        when (msg.type) {
            WireMessage.HELLO -> _state.update { it.copy(remoteNick = msg.nick.orEmpty().take(40), remotePricesAt = msg.pricesAt) }
            WireMessage.BINDER -> {
                if (msg.seq == 0) incoming.clear()
                incoming.addAll(msg.items.take(MAX_ITEMS - incoming.size).map { it.sanitized() })
                if (msg.seq + 1 >= msg.total) {
                    val repriced = incoming.map { repriceLocally(it) }
                    incoming.clear()
                    _state.update { it.copy(theirItems = repriced) }
                }
            }
            WireMessage.PROPOSAL -> _state.update { s ->
                if (msg.rev < s.rev) return@update s
                s.copy(
                    rev = msg.rev,
                    // Their "senderGives" is what I get; "receiverGives" is what I give.
                    iGet = msg.senderGives.filter { sel -> s.theirItems.any { it.key == sel.key } }
                        .associate { it.key to it.qty.coerceIn(0, s.theirItems.first { i -> i.key == it.key }.qty) }.filterValues { it > 0 },
                    iGive = msg.receiverGives.filter { sel -> s.myItems.any { it.key == sel.key } }
                        .associate { it.key to it.qty.coerceIn(0, s.myItems.first { i -> i.key == it.key }.qty) }.filterValues { it > 0 },
                    acceptedByMe = false,
                    acceptedByThem = false
                )
            }
            WireMessage.ACCEPT -> {
                _state.update { if (msg.rev == it.rev) it.copy(acceptedByThem = true) else it }
                completeIfBothAccepted()
            }
        }
    }

    private fun TradeItem.sanitized() = copy(
        name = name.take(120), setName = setName.take(80), number = number.take(20), qty = qty.coerceIn(1, 999),
        usd = usd.coerceIn(0.0, 1_000_000.0), imageUrl = imageUrl?.takeIf { it.startsWith("https://") }
    )

    /** Remote prices are only a fallback: when the card exists locally, THIS device's prices decide. */
    private suspend fun repriceLocally(item: TradeItem): TradeItem {
        val local = db.cards().getWithPrices(item.cardId) ?: return item
        val fake = CollectionItemEntity(
            gameId = _state.value.game?.code.orEmpty(), cardId = item.cardId, variant = item.variant, condition = item.condition,
            gradeCompany = item.company, gradeX10 = item.gradeX10, quantity = 1, tradeQuantity = 0,
            manualPriceUsd = null, notes = null, addedAt = 0, updatedAt = 0
        )
        val v = Valuation.unitValue(fake, local.prices, local.graded)
        return if (v.hasPrice) item.copy(usd = v.usd, imageUrl = item.imageUrl ?: local.card.imageUrl) else item
    }

    private suspend fun loadMyBinder(game: GameId): List<TradeItem> =
        db.collection().getGame(game.code).filter { it.item.tradeQuantity > 0 && it.card != null }.map { row ->
            val c = row.card!!
            val unit = com.tcgscanner.offline.data.repo.Valuation.unitValue(row.item, row.prices, row.graded)
            val company = GradingCompany.fromCode(row.item.gradeCompany)
            TradeItem(
                cardId = c.id, name = c.name, setName = c.setName, number = c.number, variant = row.item.variant,
                grade = if (company.isGraded) "${company.label} ${row.item.gradeX10 / 10.0}" else "",
                company = row.item.gradeCompany, gradeX10 = row.item.gradeX10, condition = row.item.condition,
                qty = row.item.tradeQuantity, usd = unit.usd, imageUrl = c.imageUrl
            )
        }

    private fun sendHello() {
        val s = _state.value
        scope.launch {
            val nick = settings.current().nickname.ifBlank { Build.MODEL ?: "Trader" }
            send(WireMessage(WireMessage.HELLO, nick = nick, game = s.game?.code, pricesAt = s.myPricesAt))
            val chunks = s.myItems.chunked(CHUNK).ifEmpty { listOf(emptyList()) }
            chunks.forEachIndexed { i, items -> send(WireMessage(WireMessage.BINDER, seq = i, total = chunks.size, items = items)) }
        }
    }

    private fun sendProposal() {
        val s = _state.value
        send(
            WireMessage(
                WireMessage.PROPOSAL, rev = s.rev,
                senderGives = s.iGive.map { TradeSel(it.key, it.value) },
                receiverGives = s.iGet.map { TradeSel(it.key, it.value) }
            )
        )
    }

    private fun send(msg: WireMessage) {
        val id = endpointId ?: return
        client.sendPayload(id, Payload.fromBytes(json.encodeToString(msg).toByteArray(Charsets.UTF_8)))
    }

    private fun fail(message: String?) {
        _state.update { it.copy(phase = TradePhase.ERROR, error = message ?: "ERROR") }
    }

    companion object {
        private const val SERVICE_ID = "com.tcgscanner.offline.trade"
        private const val CHUNK = 60
        private const val MAX_ITEMS = 2000
        private const val MAX_MESSAGE = 32 * 1024
    }
}

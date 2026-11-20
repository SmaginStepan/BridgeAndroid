package com.an0obis.bridge.ui.online

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.an0obis.bridge.BuildConfig
import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.sayc.SystemConfig
import com.an0obis.bridge.net.ClientMsg
import com.an0obis.bridge.net.ConnState
import com.an0obis.bridge.net.LobbyClient
import com.an0obis.bridge.net.RoomInfo
import com.an0obis.bridge.net.ServerMsg
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * The online lobby (ported from Preferans): the connection to the relay, the list of open games,
 * the room this player sits in, and the game messages between host and guests.
 */
class LobbyViewModel : ViewModel() {

    private val client = LobbyClient(BuildConfig.RELAY_URL)

    var conn by mutableStateOf(ConnState.Disconnected)
        private set
    var rooms by mutableStateOf<List<RoomInfo>>(emptyList())
        private set
    var currentRoom by mutableStateOf<RoomInfo?>(null)
        private set
    var mySeat by mutableStateOf<Int?>(null)
        private set
    var started by mutableStateOf(false)
        private set

    /** Bumped for every freshly started game, so the table gets a new view model. */
    var gameGeneration by mutableStateOf(0)
        private set

    /** Transient server error / event code; the screen maps it to a text. */
    var notice by mutableStateOf<String?>(null)

    var myName by mutableStateOf("")
        private set

    /** Relayed game payloads: the host receives (room seat, data), guests receive data. */
    private val playerActs = MutableSharedFlow<Pair<Int, JsonElement>>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val hostStates = MutableSharedFlow<JsonElement>(
        replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** The guests' acts, for the host's table. */
    val guestActs: Flow<Pair<Int, NetMsg.Act>> = playerActs.mapNotNull { (seat, el) ->
        (decode(el) as? NetMsg.Act)?.let { seat to it }
    }

    /** The host's table states, for a guest's table. */
    val tableStates: Flow<NetMsg.State> = hostStates.mapNotNull { decode(it) as? NetMsg.State }

    /** The room seats whose guests are connected right now. */
    val guestsOnline: Flow<Set<Int>> = snapshotFlow { currentRoom }.filterNotNull().map { room ->
        room.seats.withIndex().filter { (i, s) -> i > 0 && s?.kind == "human" && s.connected }.map { it.index }.toSet()
    }.distinctUntilChanged()

    private fun decode(el: JsonElement): NetMsg? = try {
        onlineJson.decodeFromJsonElement(NetMsg.serializer(), el)
    } catch (e: Exception) {
        Log.w("BridgeNet", "bad game payload", e)
        null
    }

    fun sendToSeat(seat: Int, msg: NetMsg) {
        client.send(ClientMsg.Send(toSeat = seat, data = onlineJson.encodeToJsonElement(NetMsg.serializer(), msg)))
    }

    fun sendToHost(msg: NetMsg) {
        client.send(ClientMsg.Send(data = onlineJson.encodeToJsonElement(NetMsg.serializer(), msg)))
    }

    private var startedOnce = false

    fun start() {
        if (startedOnce) return
        startedOnce = true
        val settings = AppSettings()
        // blank = never customized; the screen offers the localized default
        myName = if (settings.isDefaultPlayerName) "" else settings.playerName

        viewModelScope.launch { client.state.collect { conn = it } }
        viewModelScope.launch { client.messages.collect { onMessage(it) } }
        // keeps the connection and refreshes the list of games
        viewModelScope.launch {
            while (true) {
                when {
                    conn == ConnState.Disconnected -> {
                        val s = AppSettings()
                        client.connect(s.playerId, s.playerName)
                    }
                    conn == ConnState.Connected && currentRoom == null -> client.send(ClientMsg.ListRooms)
                    else -> {}
                }
                delay(4000)
            }
        }
    }

    private fun onMessage(msg: ServerMsg) {
        when (msg) {
            is ServerMsg.Welcome -> client.send(ClientMsg.ListRooms)
            is ServerMsg.Rooms -> rooms = msg.rooms.filter { parseRules(it.rules) != null }
            is ServerMsg.RoomCreated -> mySeat = 0
            is ServerMsg.Joined -> mySeat = msg.seat
            is ServerMsg.RoomState -> currentRoom = msg.room
            is ServerMsg.Started -> {
                // the replay of "started" after a reconnect keeps the running table
                if (!started) {
                    gameGeneration++
                    hostStates.resetReplayCache()
                }
                started = true
            }
            is ServerMsg.Left -> clearRoom()
            is ServerMsg.Kicked -> { clearRoom(); notice = "kicked" }
            is ServerMsg.RoomClosed -> {
                clearRoom()
                notice = if (msg.reason == "host_disconnected") "host_disconnected" else "room_closed"
            }
            is ServerMsg.Error -> {
                // throttling must not interrupt play with a dialog
                if (msg.code == "rate_limited") Log.w("BridgeNet", "rate limited: ${msg.message}")
                else notice = msg.code
            }
            is ServerMsg.HostMsg -> hostStates.tryEmit(msg.data)
            is ServerMsg.PlayerMsg -> playerActs.tryEmit(msg.fromSeat to msg.data)
        }
    }

    private fun clearRoom() {
        currentRoom = null
        mySeat = null
        started = false
    }

    val isHost: Boolean get() = mySeat == 0

    /** Saves a changed name and announces it before creating or joining. */
    private fun ensureName(name: String) {
        val n = name.trim().take(24)
        if (n.isEmpty() || n == myName) return
        val settings = AppSettings()
        settings.playerName = n
        myName = n
        client.send(ClientMsg.Hello(settings.playerId, n))
    }

    fun createRoom(playerName: String, roomName: String, password: String?, ns: SystemConfig, ew: SystemConfig, autoNextSec: Int) {
        ensureName(playerName)
        val rules = onlineJson.encodeToJsonElement(RoomRules.serializer(), RoomRules(game = "bridge", ns = ns, ew = ew, autoNextSec = autoNextSec))
        client.send(
            ClientMsg.CreateRoom(name = roomName, rules = rules, maxSeats = 4, password = password?.takeIf { it.isNotBlank() })
        )
    }

    fun join(roomId: String, password: String?, playerName: String) {
        ensureName(playerName)
        client.send(ClientMsg.Join(roomId, password?.takeIf { it.isNotBlank() }))
    }

    fun leave() {
        client.send(ClientMsg.Leave)
    }

    fun kick(seat: Int) {
        client.send(ClientMsg.Kick(seat))
    }

    /** Fills the first empty seat with a bot. */
    fun addBot() {
        client.send(ClientMsg.AddBot())
    }

    /** Moves the guests around before the game starts (seat 0 stays the host). */
    fun swapSeats(a: Int, b: Int) {
        if (a != b && a > 0 && b > 0) client.send(ClientMsg.SwapSeats(a, b))
    }

    fun startGame() {
        client.send(ClientMsg.Start)
    }

    /** The room's rules, or null for a room that is not a Bridge game. */
    fun parseRules(rules: JsonElement?): RoomRules? = try {
        if (rules == null) null
        else onlineJson.decodeFromJsonElement(RoomRules.serializer(), rules).takeIf { it.game == "bridge" }
    } catch (e: Exception) {
        null
    }

    override fun onCleared() {
        // leaving the online section is deliberate: give up the seat, so the server does not put us
        // back into the old room on the next visit (the reconnect grace is for accidental drops)
        if (currentRoom != null) client.send(ClientMsg.Leave)
        client.disconnect()
    }
}

package com.an0obis.bridge.ui.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.sayc.Meaning
import com.an0obis.bridge.model.sayc.SystemConfig
import com.an0obis.bridge.model.session.Seasons
import com.an0obis.bridge.ui.solo.AdviceUi
import com.an0obis.bridge.ui.solo.PlayerTag
import com.an0obis.bridge.ui.solo.TableController
import com.an0obis.bridge.ui.solo.TableState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The host's table: runs [OnlineHost] on this phone. [guestActs] brings the guests' messages
 * (room seat, act), [guestOnline] the room seats whose guests are connected right now.
 */
class OnlineHostViewModel(
    setup: HostSetup,
    send: (roomSeat: Int, msg: NetMsg) -> Unit,
    guestActs: Flow<Pair<Int, NetMsg.Act>>,
    guestOnline: Flow<Set<Int>>
) : ViewModel(), TableController {

    private val host = OnlineHost(viewModelScope, setup, send)
    private val seating = setup.rules.seating

    override val state: StateFlow<TableState> = host.hostState
    override val advice: StateFlow<AdviceUi> = MutableStateFlow<AdviceUi>(AdviceUi.None).asStateFlow()
    override val ourSystem: SystemConfig = setup.rules.ns
    override val showsAds: Boolean get() = false

    init {
        viewModelScope.launch { guestActs.collect { (seat, act) -> host.onGuest(seat, act) } }
        viewModelScope.launch {
            guestOnline.collect { seats -> for (seat in 1..3) host.setOnline(seat, seat in seats) }
        }
        host.start()
    }

    override fun explain(index: Int): List<Meaning> =
        state.value.let { seating.explain(it.dealer, it.calls, index) }

    override fun onCall(call: Call) = host.move(Seat.SOUTH, call, state.value.step)
    override fun onCard(card: Card) = host.move(Seat.SOUTH, card, state.value.step)
    override fun nextBoard() = host.move(Seat.SOUTH, OnlineHost.NEXT, state.value.step)
    override fun newSession() = host.newSession()
    override fun dismissSummary() = host.closeSummary(Seat.SOUTH)

    // not offered online: the trick pause is the same for everybody, no undo, advice or concession
    override fun takeTrick() {}
    override fun undo() {}
    override fun concede() {}
    override fun requestAdvice() {}
    override fun closeAdvice() {}
    override fun joinLeaderboard(name: String) {}
    override fun declineLeaderboard() {}

    override fun onCleared() {
        host.stop()
    }

    companion object {
        fun factory(
            setup: HostSetup,
            send: (Int, NetMsg) -> Unit,
            guestActs: Flow<Pair<Int, NetMsg.Act>>,
            guestOnline: Flow<Set<Int>>
        ) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                OnlineHostViewModel(setup, send, guestActs, guestOnline) as T
        }
    }
}

/**
 * A guest's table: shows the states the host sends ([hostStates]) and sends this player's moves
 * back. The guest at [roomSeat] sees the table turned so that it sits South (see [turnedFor]).
 */
class OnlineGuestViewModel(
    rules: RoomRules,
    roomSeat: Int,
    private val sendToHost: (NetMsg.Act) -> Unit,
    hostStates: Flow<NetMsg.State>
) : ViewModel(), TableController {

    private val seating = rules.seating.turnedFor(compassOf(roomSeat))

    /** Until the host's first state arrives: an empty table that is clearly an online one. */
    private val _state = MutableStateFlow(TableState(players = List(4) { PlayerTag("", false) }, canNewSession = false))
    override val state: StateFlow<TableState> = _state.asStateFlow()
    override val advice: StateFlow<AdviceUi> = MutableStateFlow<AdviceUi>(AdviceUi.None).asStateFlow()
    override val ourSystem: SystemConfig = seating.ns.config
    override val showsAds: Boolean get() = false

    /** The player closed the session result; it stays closed until they open it again. */
    private var summaryHidden = false

    init {
        viewModelScope.launch {
            hostStates.collect { msg ->
                val table = runCatching { msg.table.toState() }.getOrNull() ?: return@collect
                if (!table.showSummary) summaryHidden = false
                _state.value = if (summaryHidden) table.copy(showSummary = false) else table
            }
        }
        // the host sends the table to a guest who asks (on joining and after a reconnect)
        sendToHost(NetMsg.Act(sync = true))
    }

    override fun explain(index: Int): List<Meaning> =
        state.value.let { seating.explain(it.dealer, it.calls, index) }

    override fun onCall(call: Call) {
        val s = _state.value
        if (!s.humanToAct || call !in s.legalCalls) return
        // until the host answers, the bidding box goes away so a call is never sent twice
        _state.value = s.copy(humanToAct = false, legalCalls = emptySet())
        sendToHost(NetMsg.Act(call = Seasons.encode(call), step = s.step))
    }

    override fun onCard(card: Card) {
        val s = _state.value
        if (!s.humanToAct || card !in s.legalCards) return
        _state.value = s.copy(humanToAct = false, legalCards = emptySet())
        sendToHost(NetMsg.Act(card = Seasons.encode(card), step = s.step))
    }

    override fun nextBoard() {
        val s = _state.value
        if (s.phase != Phase.DONE) return
        if (s.records.size >= s.sessionSize) {
            // the session is over: Next board opens its result again
            summaryHidden = false
            _state.value = s.copy(showSummary = true)
        } else {
            _state.value = s.copy(confirmed = true)
        }
        sendToHost(NetMsg.Act(next = true, step = s.step))
    }

    override fun dismissSummary() {
        summaryHidden = true
        _state.value = _state.value.copy(showSummary = false)
    }

    override fun newSession() {} // the host starts sessions
    override fun takeTrick() {}
    override fun undo() {}
    override fun concede() {}
    override fun requestAdvice() {}
    override fun closeAdvice() {}
    override fun joinLeaderboard(name: String) {}
    override fun declineLeaderboard() {}

    companion object {
        fun factory(rules: RoomRules, roomSeat: Int, sendToHost: (NetMsg.Act) -> Unit, hostStates: Flow<NetMsg.State>) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    OnlineGuestViewModel(rules, roomSeat, sendToHost, hostStates) as T
            }
    }
}

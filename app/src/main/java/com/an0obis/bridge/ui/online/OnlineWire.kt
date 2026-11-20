package com.an0obis.bridge.ui.online

import com.an0obis.bridge.model.BoardResult
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Contract
import com.an0obis.bridge.model.Multiplier
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Vulnerability
import com.an0obis.bridge.model.sayc.Seating
import com.an0obis.bridge.model.sayc.SystemConfig
import com.an0obis.bridge.model.session.Seasons
import com.an0obis.bridge.ui.solo.BoardRecord
import com.an0obis.bridge.ui.solo.Ending
import com.an0obis.bridge.ui.solo.PlayerTag
import com.an0obis.bridge.ui.solo.SeatView
import com.an0obis.bridge.ui.solo.TableState
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/*
 * The online game. The host's phone runs the board (the engine and the bots); the relay server only
 * passes these messages on. Every guest gets the table as it may see it: other hands hidden, and
 * turned so that the guest sits South (like the host). Seat letters are therefore the viewer's own:
 * everybody plays "South", the partner is "North".
 *
 * Room seats and compass seats: room seat 0 is the host, and the seats go round the table
 * clockwise, so seat 0 is South, 1 West, 2 North (the host's partner) and 3 East.
 */

/** The compass seat of room seat [roomSeat]. */
fun compassOf(roomSeat: Int): Int = (Seat.SOUTH + roomSeat) % 4

/** The room seat at compass seat [compass]. */
fun roomSeatOf(compass: Int): Int = (compass - Seat.SOUTH + 4) % 4

/** The room's opaque `rules`: what the host chose when creating the game. */
@Serializable
data class RoomRules(
    /**
     * Marks a Bridge room (the server keeps the games apart; this is a second check). No default:
     * the rules of a Preferans room have no such field and must not read as a Bridge room.
     */
    val game: String,
    /** The systems of North-South (the host and partner) and of East-West. */
    val ns: SystemConfig = SystemConfig.SAYC,
    val ew: SystemConfig = SystemConfig.SAYC,
    /** After a board, Next board is pressed for everyone after this many seconds (0 = off). */
    val autoNextSec: Int = 0
) {
    val seating: Seating get() = Seating.of(ns, ew)
}

@OptIn(ExperimentalSerializationApi::class)
val onlineJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "t"
}

/** Host <-> guest messages, carried in the relay's opaque `data`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("t")
sealed interface NetMsg {
    /** The whole table as this guest sees it. */
    @Serializable
    @SerialName("state")
    data class State(val table: WireTable) : NetMsg

    /** A guest's move or request; one field is set. Calls and cards use the season move texts. */
    @Serializable
    @SerialName("act")
    data class Act(
        val call: String? = null,
        val card: String? = null,
        /** ready for the next board */
        val next: Boolean? = null,
        /** please send me the table again (after joining or reconnecting) */
        val sync: Boolean? = null,
        /** the [WireTable.step] the move was made on; a move for another step is ignored */
        val step: Int? = null
    ) : NetMsg
}

@Serializable
data class WireSeat(val cards: List<String>? = null, val count: Int)

@Serializable
data class WirePlaced(val seat: Int, val card: String)

@Serializable
data class WireContract(val level: Int, val denom: Int, val mult: Int, val declarer: Int)

@Serializable
data class WireResult(val contract: WireContract? = null, val tricks: Int, val ns: Int)

@Serializable
data class WireRecord(val board: Int, val contract: WireContract? = null, val tricks: Int, val ns: Int, val pct: Double)

@Serializable
data class WirePlayer(val name: String, val bot: Boolean = false)

/** [TableState] in a form that travels as JSON. */
@Serializable
data class WireTable(
    val board: Int,
    val size: Int,
    val dealer: Int,
    val vul: Int,
    val phase: Int,
    val calls: List<String>,
    val seats: List<WireSeat>,
    val trick: List<WirePlaced> = emptyList(),
    val turn: Int? = null,
    val toAct: Boolean = false,
    val legalCalls: List<String> = emptyList(),
    val legalCards: List<String> = emptyList(),
    val contract: WireContract? = null,
    val tricksNS: Int = 0,
    val tricksEW: Int = 0,
    val result: WireResult? = null,
    val ending: Int = 0,
    val pct: Double? = null,
    val records: List<WireRecord> = emptyList(),
    val summary: Boolean = false,
    val trickDone: Boolean = false,
    val collectTo: Int? = null,
    val players: List<WirePlayer> = emptyList(),
    val waitingFor: List<String> = emptyList(),
    val confirmed: Boolean = false,
    val step: Int = 0
)

private fun Contract.wire() = WireContract(level, denom, multiplier.ordinal, declarer)
private fun WireContract.model() = Contract(level, denom, Multiplier.entries[mult], declarer)
private fun card(text: String) = Seasons.decode(text) as Card
private fun call(text: String) = Seasons.decode(text) as Call

fun TableState.toWire(): WireTable = WireTable(
    board = boardNumber,
    size = sessionSize,
    dealer = dealer,
    vul = vulnerability.ordinal,
    phase = phase.ordinal,
    calls = calls.map { Seasons.encode(it) },
    seats = seats.map { s -> WireSeat(s.cards?.map { Seasons.encode(it) }, s.count) },
    trick = trick.map { (seat, c) -> WirePlaced(seat, Seasons.encode(c)) },
    turn = turnSeat,
    toAct = humanToAct,
    legalCalls = legalCalls.map { Seasons.encode(it) },
    legalCards = legalCards.map { Seasons.encode(it) },
    contract = contract?.wire(),
    tricksNS = tricksNorthSouth,
    tricksEW = tricksEastWest,
    result = result?.let { WireResult(it.contract?.wire(), it.declarerTricks, it.northSouthScore) },
    ending = ending.ordinal,
    pct = percent,
    records = records.map { WireRecord(it.board, it.contract?.wire(), it.declarerTricks, it.northSouthScore, it.percent) },
    summary = showSummary,
    trickDone = trickDone,
    collectTo = collectTo,
    players = players?.map { WirePlayer(it.name, it.bot) } ?: emptyList(),
    waitingFor = waitingFor,
    confirmed = confirmed,
    step = step
)

/** The guest's table; [canNewSession] is false for guests (the host starts sessions). */
fun WireTable.toState(): TableState = TableState(
    boardNumber = board,
    sessionSize = size,
    dealer = dealer,
    vulnerability = Vulnerability.entries[vul],
    phase = Phase.entries[phase],
    calls = calls.map { call(it) },
    seats = seats.map { s -> SeatView(s.cards?.map { card(it) }, s.count) },
    trick = trick.map { it.seat to card(it.card) },
    turnSeat = turn,
    humanToAct = toAct,
    legalCalls = legalCalls.map { call(it) }.toSet(),
    legalCards = legalCards.map { card(it) }.toSet(),
    contract = contract?.model(),
    tricksNorthSouth = tricksNS,
    tricksEastWest = tricksEW,
    result = result?.let { BoardResult(it.contract?.model(), it.tricks, it.ns) },
    ending = Ending.entries[ending],
    percent = pct,
    records = records.map { BoardRecord(it.board, it.contract?.model(), it.tricks, it.ns, it.pct) },
    showSummary = summary,
    trickDone = trickDone,
    collectTo = collectTo,
    players = players.map { PlayerTag(it.name, it.bot) },
    waitingFor = waitingFor,
    confirmed = confirmed,
    canNewSession = false,
    step = step
)

/** How far [viewer]'s table is turned: a compass seat moves to (seat + turn) % 4, the viewer to South. */
fun turnFor(viewer: Int): Int = (Seat.SOUTH - viewer + 4) % 4

/** The systems as [viewer] sees them: turned by an odd number of seats, the two sides swap. */
fun Seating.turnedFor(viewer: Int): Seating = if (turnFor(viewer) % 2 == 1) Seating(ew, ns) else this

/**
 * The table turned so that [viewer] (a compass seat) sits South. Sides swap when the turn is odd:
 * the vulnerability, the trick counts, the scores (North-South's score is East-West's with the
 * sign changed) and the matchpoints (East-West's percentage is 100 minus North-South's).
 */
fun TableState.turnedFor(viewer: Int): TableState {
    val r = turnFor(viewer)
    if (r == 0) return this
    val odd = r % 2 == 1
    fun d(seat: Int) = (seat + r) % 4
    fun <T> seatList(list: List<T>): List<T> = List(list.size) { i -> list[(i - r + 4) % 4] }
    fun Contract.turned() = copy(declarer = d(declarer))
    fun score(ns: Int) = if (odd) -ns else ns
    fun pct(p: Double) = if (odd) 100.0 - p else p
    return copy(
        dealer = d(dealer),
        vulnerability = if (!odd) vulnerability else when (vulnerability) {
            Vulnerability.NS -> Vulnerability.EW
            Vulnerability.EW -> Vulnerability.NS
            else -> vulnerability
        },
        seats = seatList(seats),
        trick = trick.map { (seat, c) -> d(seat) to c },
        turnSeat = turnSeat?.let { d(it) },
        contract = contract?.turned(),
        tricksNorthSouth = if (odd) tricksEastWest else tricksNorthSouth,
        tricksEastWest = if (odd) tricksNorthSouth else tricksEastWest,
        result = result?.let { BoardResult(it.contract?.turned(), it.declarerTricks, score(it.northSouthScore)) },
        percent = percent?.let { pct(it) },
        records = records.map { it.copy(contract = it.contract?.turned(), northSouthScore = score(it.northSouthScore), percent = pct(it.percent)) },
        collectTo = collectTo?.let { d(it) },
        players = players?.let { seatList(it) }
    )
}

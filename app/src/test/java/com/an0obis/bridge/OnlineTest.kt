package com.an0obis.bridge

import com.an0obis.bridge.model.BoardResult
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Contract
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.Multiplier
import com.an0obis.bridge.model.Phase
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Seat
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.Vulnerability
import com.an0obis.bridge.model.sayc.SystemConfig
import com.an0obis.bridge.model.session.Seasons
import com.an0obis.bridge.ui.online.HostSetup
import com.an0obis.bridge.ui.online.HostTiming
import com.an0obis.bridge.ui.online.NetMsg
import com.an0obis.bridge.ui.online.OnlineHost
import com.an0obis.bridge.ui.online.RoomRules
import com.an0obis.bridge.ui.online.SeatKind
import com.an0obis.bridge.ui.online.compassOf
import com.an0obis.bridge.ui.online.onlineJson
import com.an0obis.bridge.ui.online.roomSeatOf
import com.an0obis.bridge.ui.online.toState
import com.an0obis.bridge.ui.online.toWire
import com.an0obis.bridge.ui.online.turnedFor
import com.an0obis.bridge.ui.solo.BoardRecord
import com.an0obis.bridge.ui.solo.Ending
import com.an0obis.bridge.ui.solo.PlayerTag
import com.an0obis.bridge.ui.solo.SeatView
import com.an0obis.bridge.ui.solo.TableState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineTest {

    private val hands = Deck.deal(SeasonRandom.forPurpose("online", "deal", 1))

    /** A table in the middle of the play, with everything set that travels. */
    private fun sample(): TableState = TableState(
        boardNumber = 5,
        dealer = Seat.NORTH,
        vulnerability = Vulnerability.NS,
        phase = Phase.PLAY,
        calls = listOf(Call.Bid(1, Suit.HEARTS), Call.Pass, Call.Bid(4, Suit.HEARTS), Call.Dbl, Call.Pass, Call.Pass, Call.Redbl, Call.Pass, Call.Pass, Call.Pass),
        seats = List(4) { s -> SeatView(if (s == Seat.SOUTH || s == Seat.NORTH) hands[s].cards else null, 13) },
        trick = listOf(Seat.EAST to Card(12, Suit.SPADES), Seat.SOUTH to Card(14, Suit.SPADES)),
        turnSeat = Seat.WEST,
        humanToAct = true,
        legalCalls = setOf(Call.Pass, Call.Bid(2, Suit.NO_TRUMP)),
        legalCards = setOf(Card(2, Suit.CLUBS)),
        contract = Contract(4, Suit.HEARTS, Multiplier.REDOUBLED, Seat.NORTH),
        tricksNorthSouth = 3,
        tricksEastWest = 1,
        result = BoardResult(Contract(4, Suit.HEARTS, Multiplier.REDOUBLED, Seat.NORTH), 11, 1080),
        ending = Ending.CLAIMED,
        percent = 62.5,
        records = listOf(BoardRecord(4, Contract(3, Suit.NO_TRUMP, Multiplier.NONE, Seat.EAST), 8, 50, 75.0)),
        trickDone = true,
        collectTo = Seat.SOUTH,
        players = listOf(PlayerTag("Ann", false), PlayerTag("Bot", true), PlayerTag("Me", false), PlayerTag("Ivan", false)),
        waitingFor = listOf("Ivan"),
        confirmed = true,
        step = 17
    )

    @Test
    fun theTableTravelsAsJsonUnchanged() {
        val state = sample()
        val text = onlineJson.encodeToString(NetMsg.serializer(), NetMsg.State(state.toWire()))
        val back = (onlineJson.decodeFromString(NetMsg.serializer(), text) as NetMsg.State).table.toState()
        assertEquals(state.copy(canNewSession = false), back)
    }

    @Test
    fun roomSeatsGoClockwiseFromTheHostInTheSouth() {
        assertEquals(listOf(Seat.SOUTH, Seat.WEST, Seat.NORTH, Seat.EAST), (0..3).map { compassOf(it) })
        for (r in 0..3) assertEquals(r, roomSeatOf(compassOf(r)))
    }

    @Test
    fun everyViewerSitsSouthAndTheSidesSwapForEastWest() {
        val real = sample()
        for (viewer in 0..3) {
            val t = real.turnedFor(viewer)
            // the viewer's own seat is drawn South, the partner North
            assertEquals(real.seats[viewer], t.seats[Seat.SOUTH])
            assertEquals(real.seats[Seat.partner(viewer)], t.seats[Seat.NORTH])
            assertEquals(real.players!![viewer], t.players!![Seat.SOUTH])
            val sameSide = viewer % 2 == 0
            assertEquals(if (sameSide) Vulnerability.NS else Vulnerability.EW, t.vulnerability)
            assertEquals(if (sameSide) 1080 else -1080, t.result!!.northSouthScore)
            assertEquals(if (sameSide) 62.5 else 37.5, t.percent!!, 1e-9)
            assertEquals(if (sameSide) 3 else 1, t.tricksNorthSouth)
            // the declarer is still a partner of the same player
            assertEquals(Seat.sameSide(viewer, Seat.NORTH), Seat.sameSide(Seat.SOUTH, t.contract!!.declarer))
        }
        assertEquals(real, real.turnedFor(Seat.SOUTH))
    }

    @Test
    fun aTurnedTableExplainsEveryCallLikeTheRealOne() {
        val rules = RoomRules(game = "bridge", ns = SystemConfig.ACOL, ew = SystemConfig.PRECISION)
        val real = sample()
        for (viewer in 0..3) {
            val t = real.turnedFor(viewer)
            val seating = rules.seating.turnedFor(viewer)
            for (i in real.calls.indices) {
                assertEquals(
                    rules.seating.explain(real.dealer, real.calls, i).map { it.ruleId },
                    seating.explain(t.dealer, t.calls, i).map { it.ruleId }
                )
            }
        }
    }

    @Test
    fun aPreferansRoomIsNotABridgeRoom() {
        val pref = onlineJson.parseToJsonElement("""{"gameRules":{"gameType":"Sochy"},"limit":10,"autoConfirmSec":0}""")
        assertTrue(runCatching { onlineJson.decodeFromJsonElement(RoomRules.serializer(), pref) }.isFailure)
    }

    /**
     * A whole board with the host (South), a guest (West, answering over the "network") and two
     * bots: everybody only ever sees what they may see, and Next board from both starts board 2.
     */
    @Test
    fun aBoardIsPlayedWithAGuestAndTheNextOneStarts() = runBlocking {
        val setup = HostSetup(
            names = listOf("Host", "Guest", "Bot N", "Bot E"),
            kinds = listOf(SeatKind.LOCAL, SeatKind.REMOTE, SeatKind.BOT, SeatKind.BOT),
            rules = RoomRules(game = "bridge", ns = SystemConfig.SAYC, ew = SystemConfig.TWO_OVER_ONE)
        )
        var host: OnlineHost? = null
        val guestSeen = mutableListOf<TableState>()
        var guestNexts = 0
        val timing = HostTiming(0, 0, 0, 0, 0, fieldTables = 2)
        host = OnlineHost(this, setup, { roomSeat, msg ->
            assertEquals(1, roomSeat) // only the guest gets messages, never the bots' seats
            val s = ((msg as NetMsg.State).table).toState()
            guestSeen += s
            // the guest answers like a person would: the first legal move, then Next board
            val h = host!!
            when {
                s.humanToAct && s.legalCalls.isNotEmpty() -> h.onGuest(1, NetMsg.Act(call = Seasons.encode(s.legalCalls.first()), step = s.step))
                s.humanToAct && s.legalCards.isNotEmpty() -> h.onGuest(1, NetMsg.Act(card = Seasons.encode(s.legalCards.first()), step = s.step))
                s.phase == Phase.DONE && s.percent != null && !s.confirmed && guestNexts++ == 0 -> h.onGuest(1, NetMsg.Act(next = true, step = s.step))
            }
        }, timing)
        val h = host!!
        h.start()
        val local = launch {
            h.hostState.collect { s ->
                when {
                    s.boardNumber > 1 -> {}
                    s.humanToAct && s.legalCalls.isNotEmpty() -> h.move(Seat.SOUTH, s.legalCalls.first(), s.step)
                    s.humanToAct && s.legalCards.isNotEmpty() -> h.move(Seat.SOUTH, s.legalCards.first(), s.step)
                    s.phase == Phase.DONE && s.percent != null && !s.confirmed -> h.move(Seat.SOUTH, OnlineHost.NEXT, s.step)
                }
            }
        }
        val next = withTimeout(120_000) { h.hostState.first { it.boardNumber == 2 } }
        local.cancel()
        h.stop()

        assertEquals(Phase.AUCTION, next.phase)
        assertEquals(1, next.records.size)
        val finished = guestSeen.last { it.boardNumber == 1 && it.phase == Phase.DONE }
        assertNotNull(finished.result)
        // the guest (East-West) gets the mirror image of the host's matchpoints
        assertEquals(100.0 - next.records[0].percent, finished.percent!!, 1e-9)
        for (s in guestSeen.filter { it.phase != Phase.DONE }) {
            assertNotNull(s.seats[Seat.SOUTH].cards) // the guest's own hand, drawn South
            val visible = (0..3).count { s.seats[it].cards != null }
            assertTrue(visible <= 2) // its own hand and at most the dummy
            if (s.contract == null) assertEquals(1, visible)
        }
        assertFalse(guestSeen.any { it.canNewSession })
        assertNull(next.result)
    }
}

package com.an0obis.bridge

import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Deck
import com.an0obis.bridge.model.SeasonRandom
import com.an0obis.bridge.model.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsTest {

    @Test
    fun dealGivesFourHandsOfThirteenWithAllFiftyTwoCardsAnd40Points() {
        val hands = Deck.deal(SeasonRandom.forPurpose("00ff", "deal", 0))
        assertEquals(4, hands.size)
        hands.forEach { assertEquals(13, it.cards.size) }
        assertEquals(52, hands.flatMap { it.cards }.toSet().size)
        assertEquals(40, hands.sumOf { it.points })
    }

    @Test
    fun sameSeedGivesSameDeal() {
        val a = Deck.deal(SeasonRandom.forPurpose("00ff", "deal", 3)).map { it.cards }
        val b = Deck.deal(SeasonRandom.forPurpose("00ff", "deal", 3)).map { it.cards }
        val c = Deck.deal(SeasonRandom.forPurpose("00ff", "deal", 4)).map { it.cards }
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun trickWinnerRules() {
        val aceClubs = Card(14, Suit.CLUBS)
        val twoHearts = Card(2, Suit.HEARTS)
        // trump beats the led suit, led suit beats a discard
        assertTrue(twoHearts.beats(aceClubs, trump = Suit.HEARTS, ledSuit = Suit.CLUBS))
        assertFalse(twoHearts.beats(aceClubs, trump = Suit.NO_TRUMP, ledSuit = Suit.CLUBS))
        assertTrue(Card(3, Suit.CLUBS).beats(Card(2, Suit.CLUBS), Suit.NO_TRUMP, Suit.CLUBS))
    }
}

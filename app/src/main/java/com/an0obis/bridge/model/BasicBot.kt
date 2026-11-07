package com.an0obis.bridge.model

/**
 * A deliberately simple placeholder bot used to exercise the whole game loop.
 * It is NOT a bidding system: it opens its longest suit with 12+ points, raises its
 * partner once or twice with 6+ points, and otherwise passes. It plays random legal cards.
 * Strong bots replace it later. Randomness comes from [rnd] so season games are repeatable.
 */
class BasicBot(private val rnd: DealRandom = DefaultRandom) : Bot {

    override fun chooseCall(view: BotView): Call {
        val hand = view.actingHand
        val points = hand.points
        val auction = view.auction
        val last = auction.lastBid

        if (last == null) {
            if (points >= 12) {
                // longest suit, the higher suit on ties
                val suit = (Suit.SPADES downTo Suit.CLUBS).maxByOrNull { hand.suitCards(it).size }!!
                return Call.Bid(1, suit)
            }
            return Call.Pass
        }
        val partner = Seat.partner(view.actingSeat)
        if (auction.lastBidder == partner && points >= 6 && last.level < 3) {
            val raise = Call.Bid(last.level + 1, last.denom)
            if (raise in view.legalCalls) return raise
        }
        return Call.Pass
    }

    override fun chooseCard(view: BotView): Card = view.legalCards[rnd.nextInt(view.legalCards.size)]
}

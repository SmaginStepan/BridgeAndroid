package com.an0obis.bridge.ui.solo

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.an0obis.bridge.model.Card
import com.an0obis.bridge.model.Suit

/** The Preferans card art (assets/cards/<rank><suit>.png, 296x392), shared with the Preferans game. */
object CardArt {
    private val cache = HashMap<String, ImageBitmap>()

    /** Height / width of every card image. */
    const val ASPECT = 392f / 296f

    private fun decode(context: Context, name: String, sampleSize: Int): ImageBitmap {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.assets.open(name).use { BitmapFactory.decodeStream(it, null, options)!!.asImageBitmap() }
    }

    /** [card] == null gives the card back. */
    fun card(context: Context, card: Card?): ImageBitmap {
        val name = if (card == null) "0" else "${card.rank}${"cdhs"[card.suit]}"
        // every face is 296x392 and drawn much smaller on a phone, but a tablet shows it big: load full size
        return cache.getOrPut(name) { decode(context, "cards/$name.png", 1) }
    }

    fun cloth(context: Context): ImageBitmap = cache.getOrPut("greencloth") { decode(context, "cards/greencloth.png", 1) }
}

private val RedOnTable = Color(0xFFFF8A80)

fun suitSymbol(suit: Int): String = when (suit) {
    Suit.CLUBS -> "♣"
    Suit.DIAMONDS -> "♦"
    Suit.HEARTS -> "♥"
    Suit.SPADES -> "♠"
    else -> "NT"
}

/** Red for hearts and diamonds, black otherwise (for use on light backgrounds). */
fun suitColor(suit: Int): Color =
    if (suit == Suit.DIAMONDS || suit == Suit.HEARTS) Color(0xFFC62828) else Color(0xFF111111)

/** A readable suit colour on the green table. */
fun suitColorOnTable(suit: Int): Color =
    if (suit == Suit.DIAMONDS || suit == Suit.HEARTS) RedOnTable else Color.White

fun rankText(rank: Int): String = when (rank) {
    14 -> "A"
    13 -> "K"
    12 -> "Q"
    11 -> "J"
    else -> rank.toString()
}

/**
 * A card image [width] wide. If [onClick] is given the card is tappable; when [enabled] is false
 * it is darkened and ignores taps.
 */
@Composable
fun CardFace(
    card: Card,
    width: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current.applicationContext
    val image = remember(card) { CardArt.card(context, card) }
    Image(
        bitmap = image,
        contentDescription = card.toString(),
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.Medium,
        colorFilter = if (enabled) null else ColorFilter.tint(Color(0x99000000), BlendMode.SrcAtop),
        modifier = modifier
            .size(width, width * CardArt.ASPECT)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
    )
}

@Composable
fun CardBack(width: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val image = remember { CardArt.card(context, null) }
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.None, // the back is a small pixel pattern: keep it crisp
        modifier = modifier.size(width, width * CardArt.ASPECT)
    )
}

private val SameSuitGap = 3.dp
private val SuitGroupGap = 9.dp

/**
 * A hand as separate (non-overlapping) cards in one or two centred rows, spades first, with a wider
 * gap between suits. Cards shrink to fit narrow screens. [legal] == null shows the cards without any
 * interaction; otherwise only the cards in [legal] are tappable and the rest are darkened.
 */
@Composable
fun HandGrid(
    cards: List<Card>,
    legal: Set<Card>?,
    onCard: (Card) -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 42.dp
) {
    if (cards.isEmpty()) return
    val perRow = if (cards.size > 7) (cards.size + 1) / 2 else cards.size
    val rows = cards.chunked(perRow)

    fun gapBefore(row: List<Card>, i: Int): Dp =
        if (i == 0) 0.dp else if (row[i].suit == row[i - 1].suit) SameSuitGap else SuitGroupGap

    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // shrink the cards if the widest row does not fit
        var width = cardWidth
        for (row in rows) {
            val gaps = row.indices.fold(0.dp) { acc, i -> acc + gapBefore(row, i) }
            val fit = (maxWidth - gaps) / row.size
            if (fit < width) width = fit
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (row in rows) {
                Row {
                    row.forEachIndexed { i, card ->
                        if (i > 0) Spacer(Modifier.width(gapBefore(row, i)))
                        if (legal == null) CardFace(card, width)
                        else CardFace(card, width, enabled = card in legal, onClick = { onCard(card) })
                    }
                }
            }
        }
    }
}

/** A face-up hand as four text lines (one per suit), for the side seats. */
@Composable
fun SuitLines(cards: List<Card>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        for (suit in Suit.SPADES downTo Suit.CLUBS) {
            val ranks = cards.filter { it.suit == suit }.sortedByDescending { it.rank }
                .joinToString(" ") { rankText(it.rank) }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = suitColorOnTable(suit))) { append(suitSymbol(suit)) }
                    withStyle(SpanStyle(color = Color.White)) { append(" $ranks") }
                },
                fontSize = 13.sp,
                lineHeight = 17.sp
            )
        }
    }
}

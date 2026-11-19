package com.an0obis.bridge.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.an0obis.bridge.R
import com.an0obis.bridge.model.Call
import com.an0obis.bridge.model.Suit
import com.an0obis.bridge.model.advice.BiddingGuide
import com.an0obis.bridge.model.sayc.BiddingSystem
import com.an0obis.bridge.model.sayc.Meaning
import com.an0obis.bridge.model.sayc.SystemConfig
import com.an0obis.bridge.ui.AccentGold
import com.an0obis.bridge.ui.solo.suitColorOnTable
import com.an0obis.bridge.ui.solo.suitSymbol

/** A string resource looked up by name (`sys_sayc`, `guide_opening_b`…), or null if there is none. */
@Composable
private fun namedString(name: String): String? {
    val context = LocalContext.current
    val id = context.resources.getIdentifier(name, "string", context.packageName)
    return if (id != 0) context.getString(id) else null
}

/** The name of a system: a preset's from the resources, the player's own as they typed it. */
@Composable
fun systemName(config: SystemConfig): String =
    if (config.isPreset) namedString("sys_${config.id}") ?: config.id
    else config.name.ifBlank { stringResource(R.string.sb_name_default) }

/** One line about a system, for the settings. */
@Composable
fun systemDescription(config: SystemConfig): String =
    if (config.isPreset) namedString("sys_${config.id}_d") ?: "" else stringResource(R.string.sys_custom_d)

/** The text for a bidding-system meaning, looked up by rule id (`sayc_<id>` string resources). */
@Composable
fun meaningText(ruleId: String): String = namedString("sayc_$ruleId") ?: stringResource(R.string.sayc_natural)

/** "12–21 points · 5+ ♥ · balanced hand" (empty when nothing is promised). */
@Composable
fun rangesText(hcp: IntRange?, needs: List<com.an0obis.bridge.model.sayc.Need>, balanced: Boolean?): String {
    val parts = mutableListOf<String>()
    if (hcp != null && (hcp.first > 0 || hcp.last < 40)) {
        val range = when {
            hcp.last >= 40 -> "${hcp.first}+"
            hcp.first == 0 -> "0–${hcp.last}"
            hcp.first == hcp.last -> "${hcp.first}"
            else -> "${hcp.first}–${hcp.last}"
        }
        parts.add(stringResource(R.string.sayc_pts, range))
    }
    for (n in needs) {
        val symbol = suitSymbol(n.suit)
        parts.add(
            when {
                n.min == 0 -> "≤${n.max} $symbol"
                n.min == n.max -> "${n.min} $symbol"
                n.max >= 13 -> "${n.min}+ $symbol"
                else -> "${n.min}–${n.max} $symbol"
            }
        )
    }
    if (balanced == true) parts.add(stringResource(R.string.sayc_balanced))
    return parts.joinToString(" · ")
}

@Composable
fun rangesText(m: Meaning): String = rangesText(m.hcp, m.needs, m.balanced)

/** A call with its suit symbol coloured for a dark background: "1♥", "2NT", "Pass", "X". */
@Composable
fun callLabel(call: Call): AnnotatedString = when (call) {
    is Call.Pass -> AnnotatedString(stringResource(R.string.bid_pass))
    is Call.Dbl -> AnnotatedString("X")
    is Call.Redbl -> AnnotatedString("XX")
    is Call.Bid -> buildAnnotatedString {
        append(call.level.toString())
        if (call.denom == Suit.NO_TRUMP) append(suitSymbol(call.denom))
        else withStyle(SpanStyle(color = suitColorOnTable(call.denom))) { append(suitSymbol(call.denom)); append('︎') }
    }
}

/** [text] with hearts and diamonds in red (the light red that reads on a dark dialog). */
fun coloredSuits(text: String): AnnotatedString = buildAnnotatedString {
    for (ch in text) {
        when (ch) {
            // U+FE0E asks for the plain text glyph, which takes the colour (the emoji glyph is a fixed grey or red)
            '♥', '♦' -> withStyle(SpanStyle(color = suitColorOnTable(Suit.HEARTS))) { append(ch); append('︎') }
            '♣', '♠' -> withStyle(SpanStyle(color = suitColorOnTable(Suit.SPADES))) { append(ch); append('︎') }
            else -> append(ch)
        }
    }
}

/**
 * The calls of [system] for one guide [section]: each meaning with the calls that carry it and the
 * range of this system. Used by the guide and by the builder's preview.
 */
@Composable
fun SystemTable(system: BiddingSystem, section: String, modifier: Modifier = Modifier) {
    val lines = remember(system, section) { BiddingGuide.table(system, section) }
    SystemTable(lines, modifier)
}

/** Lines of a system table: the calls, the meaning and its range. */
@Composable
fun SystemTable(lines: List<BiddingGuide.Line>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Column(modifier = modifier) {
        for (line in lines) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    buildAnnotatedString {
                        // two calls per line, so a long list never runs into the text
                        line.calls.take(6).forEachIndexed { i, c ->
                            if (i > 0) append(if (i % 2 == 0) "\n" else " ")
                            append(callLabel(c))
                        }
                        if (line.calls.size > 6) append(" …")
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(96.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(coloredSuits(meaningText(line.ruleId)), fontSize = 14.sp, lineHeight = 19.sp)
                    val ranges = rangesText(line.hcp, emptyList(), line.balanced)
                    if (ranges.isNotEmpty()) Text(ranges, fontSize = 13.sp, color = AccentGold)
                }
            }
        }
    }
}

/**
 * The bidding guide, full screen: the sections that apply to [config], each opening and closing
 * when tapped, with the system's own calls under the text. The section for the player's current
 * situation ([initial]) is open and scrolled into view.
 */
@Composable
fun BiddingGuideDialog(initial: String, config: SystemConfig, onClose: () -> Unit) {
    val system = remember(config) { BiddingSystem.of(config) }
    val ids = remember(config) { BiddingGuide.sectionsFor(config) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            var open by remember { mutableStateOf(setOf(initial)) }
            val list = rememberLazyListState()
            // item 0 is the intro text; wait for the first layout, then bring the section to the top
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(200)
                val at = ids.indexOf(initial)
                if (at >= 0) list.animateScrollToItem(at + 1)
            }
            Column(modifier = Modifier.systemBarsPadding().padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.guide_title),
                        fontSize = 26.sp,
                        fontFamily = FontFamily.Serif,
                        color = AccentGold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClose) { Text(stringResource(R.string.guide_close)) }
                }
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    item {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(stringResource(R.string.guide_system, systemName(config)), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.guide_intro), fontSize = 14.sp, color = Color(0xFF999999))
                        }
                    }
                    items(ids) { id ->
                        val isOpen = id in open
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { open = if (isOpen) open - id else open + id }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    coloredSuits(namedString("guide_${id}_t") ?: id),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOpen) AccentGold else Color.Unspecified,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(if (isOpen) "▾" else "▸", fontSize = 18.sp, color = AccentGold)
                            }
                            if (isOpen) {
                                Text(
                                    coloredSuits(namedString("guide_${id}_b") ?: ""),
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                if (BiddingGuide.table(system, id).isNotEmpty()) {
                                    Text(stringResource(R.string.guide_table), fontSize = 14.sp, color = AccentGold)
                                    SystemTable(system, id, modifier = Modifier.padding(bottom = 12.dp))
                                }
                            }
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                        }
                    }
                }
            }
        }
    }
}

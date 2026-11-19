package com.an0obis.bridge.ui.system

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.an0obis.bridge.R
import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.advice.BiddingGuide
import com.an0obis.bridge.model.sayc.BiddingSystem
import com.an0obis.bridge.model.sayc.ClubStyle
import com.an0obis.bridge.model.sayc.NtResponses
import com.an0obis.bridge.model.sayc.SystemCatalog
import com.an0obis.bridge.model.sayc.SystemConfig
import com.an0obis.bridge.model.sayc.TwoDiamonds
import com.an0obis.bridge.model.sayc.TwoMajors
import com.an0obis.bridge.ui.AccentGold

private val NT_RANGES = listOf(12 to 14, 13 to 15, 14 to 16, 15 to 17, 16 to 18)
private val TWO_NT_RANGES = listOf(20 to 21, 20 to 22, 21 to 22, 22 to 23, 22 to 24)

@Composable
private fun Heading(text: String) {
    Text(coloredSuits(text), fontSize = 20.sp, fontFamily = FontFamily.Serif, color = AccentGold, modifier = Modifier.padding(top = 20.dp, bottom = 2.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(
            coloredSuits(label),
            fontSize = 16.sp,
            color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp)
    ) {
        Text(coloredSuits(label), fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * The bidding-system builder: a convention card of options. Starting from a preset (or one of the
 * player's systems, [editId]), the player changes options and saves the result as their own
 * system. The preview shows the openings the options give, from the same rule table the bots use.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemBuilderScreen(editId: String?, onDone: () -> Unit) {
    val defaultName = stringResource(R.string.sb_name_default)
    val editing = remember(editId) { editId?.let { id -> SystemCatalog.custom().firstOrNull { it.id == id } } }
    var draft by remember { mutableStateOf(editing ?: SystemConfig.SAYC.copy(id = "", name = defaultName)) }
    var askDelete by remember { mutableStateOf(false) }

    val made = draft.normalized()
    val adjusted = made != draft
    val system = remember(made) { BiddingSystem.of(made.copy(id = "preview", name = "")) }
    val strongClub = made.club != ClubStyle.NATURAL

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            stringResource(if (editing != null) R.string.sb_title_edit else R.string.sb_title_new),
            fontSize = 30.sp,
            fontFamily = FontFamily.Serif,
            color = AccentGold
        )
        Text(stringResource(R.string.sb_intro), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))

        Heading(stringResource(R.string.sb_start))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in SystemConfig.PRESETS) {
                FilterChip(
                    selected = false,
                    onClick = { draft = preset.copy(id = draft.id, name = draft.name) },
                    label = { Text(systemName(preset)) }
                )
            }
        }

        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it.take(30)) },
            label = { Text(stringResource(R.string.sb_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        )

        Heading(stringResource(R.string.sb_club))
        Choice(stringResource(R.string.sb_club_natural), made.club == ClubStyle.NATURAL) { draft = draft.copy(club = ClubStyle.NATURAL) }
        Choice(stringResource(R.string.sb_club_strong), made.club == ClubStyle.STRONG) { draft = draft.copy(club = ClubStyle.STRONG) }
        Choice(stringResource(R.string.sb_club_polish), made.club == ClubStyle.POLISH) { draft = draft.copy(club = ClubStyle.POLISH) }

        Heading(stringResource(R.string.sb_majors))
        Choice(stringResource(R.string.sb_majors_5), made.majorLength == 5) { draft = draft.copy(majorLength = 5) }
        Choice(stringResource(R.string.sb_majors_4), made.majorLength == 4) { draft = draft.copy(majorLength = 4) }

        Heading(stringResource(R.string.sb_openmin))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in 10..13) {
                FilterChip(
                    selected = made.openMin == p,
                    onClick = { draft = draft.copy(openMin = p) },
                    label = { Text(stringResource(R.string.sb_points, p)) }
                )
            }
        }

        Heading(stringResource(R.string.sb_nt))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((lo, hi) in NT_RANGES) {
                FilterChip(
                    selected = made.ntMin == lo && made.ntMax == hi,
                    onClick = { draft = draft.copy(ntMin = lo, ntMax = hi) },
                    label = { Text("$lo–$hi") }
                )
            }
        }

        Heading(stringResource(R.string.sb_2nt))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((lo, hi) in TWO_NT_RANGES) {
                FilterChip(
                    selected = made.twoNtMin == lo && made.twoNtMax == hi,
                    onClick = { draft = draft.copy(twoNtMin = lo, twoNtMax = hi) },
                    label = { Text("$lo–$hi") }
                )
            }
        }

        Heading(stringResource(R.string.sb_2d))
        Choice(stringResource(R.string.sb_2d_weak), made.twoDiamonds == TwoDiamonds.WEAK) { draft = draft.copy(twoDiamonds = TwoDiamonds.WEAK) }
        Choice(stringResource(R.string.sb_2d_multi), made.twoDiamonds == TwoDiamonds.MULTI) { draft = draft.copy(twoDiamonds = TwoDiamonds.MULTI) }
        Choice(stringResource(R.string.sb_2d_short), made.twoDiamonds == TwoDiamonds.SHORT, enabled = strongClub) {
            draft = draft.copy(twoDiamonds = TwoDiamonds.SHORT)
        }

        Heading(stringResource(R.string.sb_2m))
        val multi = made.twoDiamonds == TwoDiamonds.MULTI
        Choice(stringResource(R.string.sb_2m_weak), made.twoMajors == TwoMajors.WEAK, enabled = !multi) { draft = draft.copy(twoMajors = TwoMajors.WEAK) }
        Choice(stringResource(R.string.sb_2m_polish), made.twoMajors == TwoMajors.POLISH) { draft = draft.copy(twoMajors = TwoMajors.POLISH) }

        Heading(stringResource(R.string.sb_nt_answers))
        Toggle(stringResource(R.string.sb_stayman), made.stayman) { draft = draft.copy(stayman = it) }
        Toggle(stringResource(R.string.sb_transfers), made.transfers) { draft = draft.copy(transfers = it) }

        Heading(stringResource(R.string.sb_answers))
        Toggle(stringResource(R.string.sb_two_over_one), made.twoOverOne) { draft = draft.copy(twoOverOne = it) }
        Toggle(stringResource(R.string.sb_jacoby), made.jacoby2nt) { draft = draft.copy(jacoby2nt = it) }
        Text(stringResource(R.string.sb_nt_resp), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
        Choice(stringResource(R.string.sb_nt_resp_standard), made.ntResponses == NtResponses.STANDARD) {
            draft = draft.copy(ntResponses = NtResponses.STANDARD)
        }
        Choice(stringResource(R.string.sb_nt_resp_limit), made.ntResponses == NtResponses.LIMIT) {
            draft = draft.copy(ntResponses = NtResponses.LIMIT)
        }

        Heading(stringResource(R.string.sb_card))
        SystemTable(remember(system) { BiddingGuide.openings(system) })
        if (adjusted) {
            Text(stringResource(R.string.sb_adjusted), fontSize = 13.sp, color = AccentGold, modifier = Modifier.padding(top = 8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Button(
                onClick = {
                    val saved = SystemCatalog.save(made.copy(name = made.name.trim().ifBlank { defaultName }))
                    // a new system is what the player wants to play: make it theirs at once
                    if (editing == null) AppSettings().biddingSystem = saved.id
                    onDone()
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.sb_save)) }
            TextButton(onClick = onDone) { Text(stringResource(R.string.sb_cancel)) }
        }
        if (editing != null) {
            TextButton(onClick = { askDelete = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.sb_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (askDelete && editing != null) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            text = { Text(stringResource(R.string.sb_delete_q, systemName(editing))) },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    SystemCatalog.delete(editing.id)
                    onDone()
                }) { Text(stringResource(R.string.sb_delete_yes)) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.sb_cancel)) } }
        )
    }
}

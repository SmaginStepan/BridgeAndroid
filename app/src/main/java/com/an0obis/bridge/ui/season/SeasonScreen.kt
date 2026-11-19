package com.an0obis.bridge.ui.season

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.an0obis.bridge.R
import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.GlobalScores
import com.an0obis.bridge.model.sayc.SystemCatalog
import com.an0obis.bridge.model.session.Seasons
import com.an0obis.bridge.ui.AccentGold
import com.an0obis.bridge.ui.solo.LeaderboardJoinDialog
import com.an0obis.bridge.ui.system.systemName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** "+12.3" / "−4.5" / "0": points over average in tenths, signed. */
fun pointsText(total10: Long): String {
    val text = String.format(Locale.US, "%.1f", kotlin.math.abs(total10) / 10.0).removeSuffix(".0")
    return when {
        total10 > 0 -> "+$text"
        total10 < 0 -> "−$text"
        else -> "0"
    }
}

/**
 * The monthly season: the systems at the table, the player's run, the season board and the Play
 * button. The run itself works offline; the network is used to learn the season once, to post the
 * total and to show the board.
 */
@Composable
fun SeasonScreen(onPlay: () -> Unit, onToMenu: () -> Unit) {
    var state by remember { mutableStateOf(Seasons.load()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var joining by remember { mutableStateOf(false) }
    val settings = remember { AppSettings() }
    var choice by remember { mutableIntStateOf(settings.leaderboardChoice) }
    val myId = remember { settings.playerId }

    LaunchedEffect(reload) {
        loading = true
        withContext(Dispatchers.IO) {
            val s = Seasons.refresh()
            if (s != null) {
                Seasons.submitPending(s)
                Seasons.refreshBoard(s)
            }
        }
        state = Seasons.load()
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.menu_season),
            fontSize = 40.sp,
            fontFamily = FontFamily.Serif,
            color = AccentGold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val s = state
        if (s == null) {
            Text(
                text = stringResource(if (loading) R.string.season_loading else R.string.season_need_network),
                fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            if (!loading) Button(onClick = { reload++ }) { Text(stringResource(R.string.season_retry)) }
        } else {
            val over = Seasons.isOver(s)
            val days = Seasons.daysLeft(s)
            Text(
                text = "${s.seasonId} · " + when {
                    over -> stringResource(R.string.season_ended)
                    days <= 1 -> stringResource(R.string.season_last_day)
                    else -> stringResource(R.string.season_days_left_fmt, days)
                },
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val ours = remember { SystemCatalog.find(settings.biddingSystem) }
            val theirs = remember(s.seed) { Seasons.opponents(s) }
            Text(stringResource(R.string.season_system_ours, systemName(ours)), fontSize = 16.sp)
            Text(stringResource(R.string.season_system_theirs, systemName(theirs)), fontSize = 16.sp)

            Text(
                text = stringResource(R.string.season_progress_fmt, s.boardsPlayed, Seasons.sessionNumber(s)),
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = stringResource(R.string.season_total_fmt, pointsText(s.total10)),
                fontSize = 20.sp,
                color = AccentGold,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Text(
                text = stringResource(R.string.season_note),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (over) {
                Text(stringResource(R.string.season_over), fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
            } else {
                Button(onClick = onPlay) {
                    // a run in progress continues where it was left
                    val resuming = s.boardsPlayed > 0 || s.currentMoves.isNotEmpty()
                    Text(stringResource(if (resuming) R.string.menu_continue else R.string.season_play))
                }
            }

            Text(
                text = stringResource(R.string.season_board_title),
                fontSize = 18.sp,
                color = AccentGold,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            if (choice != AppSettings.POST) {
                // the season board is opt-in, like the Top players list
                Text(
                    stringResource(R.string.season_not_shown),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
                TextButton(onClick = { joining = true }) { Text(stringResource(R.string.season_join)) }
            }
            if (s.board.isEmpty()) {
                Text(
                    text = stringResource(R.string.season_board_empty),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            for ((i, row) in s.board.withIndex()) {
                val mine = row.playerId == myId
                val color = if (mine) Color(0xFFFFEB3B) else Color.Gray
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("${i + 1}.", fontSize = 18.sp, color = color, modifier = Modifier.padding(end = 8.dp))
                    Text(row.name, fontSize = 18.sp, color = color, modifier = Modifier.weight(1f))
                    Text(pointsText(row.score10), fontSize = 18.sp, color = color, textAlign = TextAlign.Right)
                }
            }
        }

        OutlinedButton(onClick = onToMenu, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.hs_to_menu))
        }
    }

    if (joining) {
        LeaderboardJoinDialog(
            onJoin = { name ->
                settings.playerName = name.trim()
                GlobalScores.setLeaderboardChoice(AppSettings.POST)
                choice = AppSettings.POST
                joining = false
                reload++ // posts the total and fetches the board
            },
            onDecline = { joining = false }
        )
    }
}

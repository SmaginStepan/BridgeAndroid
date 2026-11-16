package com.an0obis.bridge.ui.misc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.an0obis.bridge.R
import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.GlobalScores
import com.an0obis.bridge.ui.AccentGold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The global "Top players" list: best session averages, plus the player's own opt-in and name. */
@Composable
fun TopPlayersScreen(onBack: () -> Unit) {
    val settings = remember { AppSettings() }
    val myId = remember { settings.playerId }
    var rows by remember { mutableStateOf(GlobalScores.cached()) }
    var offline by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf(settings.leaderboardChoice == AppSettings.POST) }
    var name by remember { mutableStateOf(if (settings.isDefaultPlayerName) "" else settings.playerName) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val fresh = withContext(Dispatchers.IO) { GlobalScores.sync() }
        offline = fresh == null
        rows = fresh ?: GlobalScores.cached()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            stringResource(R.string.top_title),
            fontSize = 34.sp,
            fontFamily = FontFamily.Serif,
            color = AccentGold
        )
        Text(
            stringResource(R.string.top_sub),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (rows.isEmpty()) {
            Text(stringResource(if (offline) R.string.top_offline else R.string.top_empty), fontSize = 16.sp)
        } else {
            rows.forEachIndexed { i, r ->
                val mine = r.playerId == myId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (mine) Modifier.background(AccentGold.copy(alpha = 0.18f), RoundedCornerShape(8.dp)) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${i + 1}", modifier = Modifier.weight(0.12f), fontSize = 18.sp)
                    Text(
                        r.name,
                        modifier = Modifier.weight(0.6f),
                        fontSize = 18.sp,
                        maxLines = 1,
                        fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        "%.1f%%".format(r.percent),
                        modifier = Modifier.weight(0.28f),
                        textAlign = TextAlign.End,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (offline) {
                Text(
                    stringResource(R.string.top_offline_cached),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.top_post_switch), fontSize = 17.sp)
                Text(
                    stringResource(R.string.top_post_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
            Switch(
                checked = joined,
                onCheckedChange = { on ->
                    joined = on
                    settings.leaderboardChoice = if (on) AppSettings.POST else AppSettings.DECLINED
                    if (!on) GlobalScores.clearPending()
                }
            )
        }
        if (joined) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(20); saved = false },
                singleLine = true,
                label = { Text(stringResource(R.string.top_your_name)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = { settings.playerName = name.trim(); saved = true }
                ) { Text(stringResource(R.string.top_save_name)) }
                if (saved) Text(stringResource(R.string.top_saved), modifier = Modifier.padding(start = 12.dp), fontSize = 14.sp)
            }
        }

        Text(
            stringResource(R.string.top_delete_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 20.dp)
        )
        TextButton(onClick = onBack) { Text(stringResource(R.string.close)) }
    }
}

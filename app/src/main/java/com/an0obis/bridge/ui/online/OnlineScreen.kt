package com.an0obis.bridge.ui.online

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.an0obis.bridge.R
import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.sayc.SystemCatalog
import com.an0obis.bridge.model.sayc.SystemConfig
import com.an0obis.bridge.net.ConnState
import com.an0obis.bridge.net.RoomInfo
import com.an0obis.bridge.ui.AccentGold
import com.an0obis.bridge.ui.solo.SoloScreen
import com.an0obis.bridge.ui.system.systemName

@Composable
private fun noticeText(code: String): String = when (code) {
    "kicked" -> stringResource(R.string.mp_err_kicked)
    "room_closed" -> stringResource(R.string.mp_err_room_closed)
    "host_disconnected" -> stringResource(R.string.mp_err_host_lost)
    "bad_password" -> stringResource(R.string.mp_err_bad_password)
    "room_full" -> stringResource(R.string.mp_err_room_full)
    "playing" -> stringResource(R.string.mp_err_playing)
    "no_room" -> stringResource(R.string.mp_err_no_room)
    else -> stringResource(R.string.mp_err_generic, code)
}

/** The compass name of room seat [seat] (0 = the host, South; then West, North, East). */
@Composable
private fun seatName(seat: Int): String = stringResource(
    when (seat) {
        0 -> R.string.mp_seat_south
        1 -> R.string.mp_seat_west
        2 -> R.string.mp_seat_north
        else -> R.string.mp_seat_east
    }
)

@Composable
private fun rulesSummary(vm: LobbyViewModel, room: RoomInfo): String {
    val rules = vm.parseRules(room.rules) ?: return ""
    return stringResource(R.string.mp_rules_fmt, systemName(rules.ns), systemName(rules.ew))
}

/** A small banner while a player is offline (the server keeps the seat for a while). */
@Composable
private fun ReconnectBanner(room: RoomInfo) {
    val offline = room.seats.filterNotNull().filter { it.kind == "human" && !it.connected }.map { it.name }
    if (offline.isEmpty()) return
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = stringResource(R.string.mp_reconnecting_fmt, offline.joinToString(", ")),
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier
                .padding(top = 30.dp)
                .background(Color(0xAA7A2020), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** The online game: the list of games, the room before the start, then the table. */
@Composable
fun OnlineScreen(onBack: () -> Unit) {
    val vm: LobbyViewModel = viewModel()
    LaunchedEffect(Unit) { vm.start() }
    var askLeave by remember { mutableStateOf(false) }

    val room = vm.currentRoom
    when {
        room == null -> {
            BackHandler(onBack = onBack)
            LobbyView(vm)
        }
        vm.started -> {
            // leaving a running game needs a confirmation: for the host it ends the game for everybody
            BackHandler { askLeave = true }
            Box {
                if (vm.isHost) HostTable(vm, room) { askLeave = true } else GuestTable(vm, room) { askLeave = true }
                ReconnectBanner(room)
            }
        }
        else -> {
            BackHandler { vm.leave() }
            RoomView(vm, room)
        }
    }

    if (askLeave && room != null && vm.started) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(stringResource(R.string.mp_leave_title)) },
            text = { Text(stringResource(if (vm.isHost) R.string.mp_leave_host else R.string.mp_leave_guest)) },
            confirmButton = {
                TextButton(onClick = { askLeave = false; vm.leave() }) { Text(stringResource(R.string.mp_leave)) }
            },
            dismissButton = { TextButton(onClick = { askLeave = false }) { Text(stringResource(R.string.solo_no)) } }
        )
    }

    vm.notice?.let { code ->
        AlertDialog(
            onDismissRequest = { vm.notice = null },
            text = { Text(noticeText(code)) },
            confirmButton = { Button(onClick = { vm.notice = null }) { Text(stringResource(R.string.close)) } }
        )
    }
}

/** The host plays on this phone: the table runs the board for everybody. */
@Composable
private fun HostTable(lobby: LobbyViewModel, room: RoomInfo, onLeave: () -> Unit) {
    val rules = lobby.parseRules(room.rules) ?: return
    val generation = lobby.gameGeneration
    val setup = remember(room.id, generation) {
        HostSetup(
            names = (0 until 4).map { room.seats.getOrNull(it)?.name ?: "?" },
            kinds = (0 until 4).map { i ->
                when {
                    i == 0 -> SeatKind.LOCAL
                    room.seats.getOrNull(i)?.kind == "bot" -> SeatKind.BOT
                    else -> SeatKind.REMOTE
                }
            },
            rules = rules
        )
    }
    val vm: OnlineHostViewModel = viewModel(
        key = "online-host-${room.id}-$generation",
        factory = OnlineHostViewModel.factory(setup, lobby::sendToSeat, lobby.guestActs, lobby.guestsOnline)
    )
    SoloScreen(onBack = onLeave, adviceAllowed = false, vm = vm)
}

/** A guest's table shows what the host sends. */
@Composable
private fun GuestTable(lobby: LobbyViewModel, room: RoomInfo, onLeave: () -> Unit) {
    val rules = lobby.parseRules(room.rules) ?: return
    val seat = lobby.mySeat ?: return
    val vm: OnlineGuestViewModel = viewModel(
        key = "online-guest-${room.id}-${lobby.gameGeneration}",
        factory = OnlineGuestViewModel.factory(rules, seat, { lobby.sendToHost(it) }, lobby.tableStates)
    )
    SoloScreen(onBack = onLeave, adviceAllowed = false, vm = vm)
}

@Composable
private fun LobbyView(vm: LobbyViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var joinFor by remember { mutableStateOf<RoomInfo?>(null) }
    val faded = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = stringResource(R.string.mp_title),
            fontSize = 40.sp,
            color = AccentGold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        when (vm.conn) {
            ConnState.Connecting, ConnState.Disconnected -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text(stringResource(if (vm.conn == ConnState.Connecting) R.string.mp_connecting else R.string.mp_disconnected))
                }
            }
            ConnState.Connected -> {
                Button(
                    onClick = { showCreate = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) { Text(stringResource(R.string.mp_create)) }

                if (vm.rooms.isEmpty()) {
                    Text(stringResource(R.string.mp_no_rooms), color = faded, modifier = Modifier.padding(top = 16.dp))
                } else {
                    LazyColumn {
                        items(vm.rooms, key = { it.id }) { r ->
                            val occupied = r.seats.count { it != null }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = r.phase == "open") { joinFor = r }
                                    .padding(vertical = 10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = (if (r.hasPassword) "🔒 " else "") + r.name,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = stringResource(R.string.mp_players_fmt, occupied, r.maxSeats),
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.mp_host_fmt, r.hostName) + "  ·  " + rulesSummary(vm, r),
                                    fontSize = 13.sp,
                                    color = faded
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateRoomDialog(
            defaultPlayerName = vm.myName.ifBlank { stringResource(R.string.default_player_name) },
            onDismiss = { showCreate = false },
            onCreate = { playerName, name, password, ns, ew, autoNext ->
                vm.createRoom(playerName, name, password, ns, ew, autoNext)
                showCreate = false
            }
        )
    }

    joinFor?.let { r ->
        val fallbackName = stringResource(R.string.default_player_name)
        var playerName by remember(r.id) { mutableStateOf(vm.myName.ifBlank { fallbackName }) }
        var pwd by remember(r.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { joinFor = null },
            title = { Text(r.name, fontSize = 17.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rulesSummary(vm, r), fontSize = 14.sp, color = faded)
                    OutlinedTextField(
                        value = playerName,
                        onValueChange = { playerName = it.take(24) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.mp_your_name)) }
                    )
                    if (r.hasPassword) {
                        Text(stringResource(R.string.mp_password_title), fontSize = 14.sp)
                        OutlinedTextField(
                            value = pwd,
                            onValueChange = { pwd = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.mp_password_label)) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(enabled = playerName.isNotBlank(), onClick = { vm.join(r.id, pwd, playerName); joinFor = null }) {
                    Text(stringResource(R.string.mp_join))
                }
            },
            dismissButton = { TextButton(onClick = { joinFor = null }) { Text(stringResource(R.string.close)) } }
        )
    }
}

/** Picks a bidding system from the presets and the player's own systems. */
@Composable
private fun SystemPicker(label: String, selected: SystemConfig, options: List<SystemConfig>, onPick: (SystemConfig) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 14.sp)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(systemName(selected), maxLines = 1)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (c in options) {
                    DropdownMenuItem(text = { Text(systemName(c)) }, onClick = { onPick(c); open = false })
                }
            }
        }
    }
}

@Composable
private fun CreateRoomDialog(
    defaultPlayerName: String,
    onDismiss: () -> Unit,
    onCreate: (playerName: String, name: String, password: String?, ns: SystemConfig, ew: SystemConfig, autoNextSec: Int) -> Unit
) {
    val settings = remember { AppSettings() }
    val options = remember { SystemCatalog.all().map { it.normalized() } }
    var playerName by remember { mutableStateOf(defaultPlayerName) }
    var name by remember { mutableStateOf(defaultPlayerName) }
    var password by remember { mutableStateOf("") }
    // the systems from the settings: the player's own for North-South, the opponents' for East-West
    var ns by remember { mutableStateOf(SystemCatalog.find(settings.biddingSystem).normalized()) }
    var ew by remember { mutableStateOf(SystemCatalog.find(settings.opponentSystem).normalized()) }
    var autoNext by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mp_create), fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = playerName,
                    onValueChange = { playerName = it.take(24) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.mp_your_name)) }
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.mp_room_name)) }
                )
                SystemPicker(stringResource(R.string.mp_ns_system), ns, options) { ns = it }
                SystemPicker(stringResource(R.string.mp_ew_system), ew, options) { ew = it }
                // after a board, Next board is pressed for everybody after a pause
                Text(stringResource(R.string.mp_auto_confirm), fontSize = 14.sp)
                for (pair in listOf(listOf(0, 5), listOf(10, 30))) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        for (v in pair) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).selectable(selected = autoNext == v, onClick = { autoNext = v })
                            ) {
                                RadioButton(selected = autoNext == v, onClick = { autoNext = v })
                                Text(
                                    if (v == 0) stringResource(R.string.mp_auto_off) else stringResource(R.string.mp_auto_sec_fmt, v),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(32) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.mp_password_optional)) }
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && playerName.isNotBlank(),
                onClick = { onCreate(playerName.trim(), name.trim(), password, ns, ew, autoNext) }
            ) { Text(stringResource(R.string.mp_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun RoomView(vm: LobbyViewModel, room: RoomInfo) {
    val faded = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text(text = room.name, fontSize = 32.sp, color = AccentGold)
        // the code friends type to find the game, tap to copy
        val clipboard = LocalClipboardManager.current
        var copied by remember(room.id) { mutableStateOf(false) }
        Text(
            text = room.id,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = TextUnit(6f, TextUnitType.Sp),
            color = AccentGold,
            modifier = Modifier.padding(top = 4.dp).clickable {
                clipboard.setText(AnnotatedString(room.id))
                copied = true
            }
        )
        Text(
            text = stringResource(if (copied) R.string.mp_code_copied else R.string.mp_tap_to_copy),
            fontSize = 12.sp,
            color = faded
        )
        Text(
            text = rulesSummary(vm, room),
            fontSize = 14.sp,
            color = faded,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
        )

        for (i in 0 until room.maxSeats) {
            val seat = room.seats.getOrNull(i)
            val movable = vm.isHost && i > 0 && seat != null
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = seatName(i),
                    fontSize = 15.sp,
                    color = if (i % 2 == 0) AccentGold else faded,
                    modifier = Modifier.width(76.dp)
                )
                Text(
                    text = when {
                        seat == null -> "—"
                        else -> buildString {
                            append(seat.name)
                            if (i == vm.mySeat) append(" " + stringResource(R.string.mp_you))
                            if (seat.kind == "bot") append(" · " + stringResource(R.string.mp_bot))
                            if (seat.kind == "human" && !seat.connected) append(" · " + stringResource(R.string.mp_offline))
                        }
                    },
                    fontSize = 19.sp,
                    color = if (seat == null) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                if (movable) {
                    IconButton(onClick = { vm.swapSeats(i, i - 1) }, enabled = i > 1) {
                        Text("▲", fontSize = 15.sp, color = if (i > 1) MaterialTheme.colorScheme.primary else faded.copy(alpha = 0.25f))
                    }
                    IconButton(onClick = { vm.swapSeats(i, i + 1) }, enabled = i < room.maxSeats - 1) {
                        Text("▼", fontSize = 15.sp, color = if (i < room.maxSeats - 1) MaterialTheme.colorScheme.primary else faded.copy(alpha = 0.25f))
                    }
                    TextButton(onClick = { vm.kick(i) }) { Text(stringResource(R.string.mp_kick), fontSize = 13.sp) }
                }
            }
        }
        Text(stringResource(R.string.mp_pairs_hint), fontSize = 13.sp, color = faded, modifier = Modifier.padding(top = 8.dp))

        if (!vm.isHost) {
            Text(
                text = stringResource(R.string.mp_waiting_host),
                color = faded,
                modifier = Modifier.padding(vertical = 20.dp)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            if (vm.isHost) {
                val full = room.seats.count { it != null } == room.maxSeats && room.seats.all { it == null || it.connected }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.addBot() },
                        enabled = room.seats.count { it != null } < room.maxSeats,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.mp_add_bot), maxLines = 1) }
                    Button(onClick = { vm.startGame() }, enabled = full, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.mp_start), maxLines = 1)
                    }
                }
            }
            OutlinedButton(onClick = { vm.leave() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.mp_leave))
            }
        }
    }
}

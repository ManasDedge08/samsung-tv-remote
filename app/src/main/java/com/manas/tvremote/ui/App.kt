package com.manas.tvremote.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.manas.tvremote.Conn
import com.manas.tvremote.Keys
import com.manas.tvremote.RemoteViewModel
import com.manas.tvremote.TvInfo

private val Side = 20.dp

@Composable
fun App(vm: RemoteViewModel) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.background,
            surface = Palette.surface,
            surfaceContainer = Palette.fill,
            primary = Palette.blue,
            onSurface = Palette.label,
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Palette.background)
                .safeDrawingPadding()
        ) {
            if (vm.paired) RemoteScreen(vm) else SetupScreen(vm)
        }
    }
}

// ---------------------------------------------------------------- Remote

@Composable
private fun RemoteScreen(vm: RemoteViewModel) {
    val conn by vm.tv.conn.collectAsState()

    Column(Modifier.fillMaxSize().padding(top = 4.dp, bottom = 16.dp)) {
        Header(vm, conn, Modifier.padding(horizontal = Side))
        Spacer(Modifier.height(16.dp))

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Side), contentAlignment = Alignment.Center) {
            if (vm.useDpad) {
                Dpad(onKey = vm::key)
            } else {
                Trackpad(
                    Modifier.fillMaxSize(),
                    showHint = !vm.padLearned,
                    onMove = { vm.key(it); vm.onPadStep() },
                    onTap = { vm.key(Keys.OK) },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Controls(vm, Modifier.padding(horizontal = Side))

        if (vm.apps.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            AppTiles(vm.apps, vm::launch, sidePadding = Side)
        }
    }

    if (vm.keyboardOpen) KeyboardSheet(onSend = vm::sendText, onDismiss = { vm.keyboardOpen = false })
    if (vm.voiceOpen) VoiceSheet(vm)
}

/** Keyboard on the left, the TV's name in the middle (tap for its menu), power on the right. */
@Composable
private fun Header(vm: RemoteViewModel, conn: Conn, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    // Status shows only when something isn't normal. A failed power probe reads as "off", so a
    // live socket wins over it; standby is real either way.
    val status: String? = when {
        vm.power == "waking" -> "Turning On…"
        conn == Conn.NeedsApproval -> "Select Allow on Your TV"
        vm.power == "standby" -> "Standby"
        vm.power == "off" && conn != Conn.Connected -> "Off"
        conn == Conn.Connected -> null
        conn == Conn.Unreachable -> "Not Connected"
        conn == Conn.Idle -> null
        else -> "Connecting…"
    }

    Row(modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        // Balances the power key so the TV name sits in the true centre.
        Spacer(Modifier.width(44.dp))

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { menu = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.tvName, style = Type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Palette.secondary, modifier = Modifier.size(20.dp))
                }
                if (status != null) Text(status, style = Type.footnote, maxLines = 1)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Palette.fill, shape = RoundedCornerShape(14.dp)) {
                MenuItem("Input Source") { menu = false; vm.key(Keys.SOURCE) }
                MenuItem("TV Settings") { menu = false; vm.key(Keys.MENU) }
                MenuItem(if (vm.useDpad) "Use Touch Surface" else "Use D-Pad") { menu = false; vm.toggleDpad() }
                HorizontalDivider(color = Palette.separator)
                MenuItem("Forget This TV", destructive = true) { menu = false; confirmForget = true }
            }
        }

        Key("Power", Modifier.size(44.dp), fireOnRelease = true, onPress = vm::togglePower) {
            if (vm.powerBusy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Palette.secondary, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.PowerSettingsNew, null, tint = Palette.label, modifier = Modifier.size(22.dp))
            }
        }
    }

    if (confirmForget) {
        ForgetDialog(vm.tvName, onForget = { confirmForget = false; vm.forget() }, onCancel = { confirmForget = false })
    }
}

@Composable
private fun ForgetDialog(name: String, onForget: () -> Unit, onCancel: () -> Unit) = AlertDialog(
    onDismissRequest = onCancel,
    containerColor = Palette.fill,
    title = { Text("Forget This TV?", style = Type.headline) },
    text = { Text("You'll need to select Allow on $name again to use this remote.", style = Type.subhead) },
    confirmButton = { TextButton(onClick = onForget) { Text("Forget", style = Type.body.copy(color = Palette.red)) } },
    dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", style = Type.body.copy(color = Palette.blue)) } },
)

@Composable
private fun MenuItem(text: String, destructive: Boolean = false, onClick: () -> Unit) =
    DropdownMenuItem(
        text = { Text(text, style = Type.body.copy(color = if (destructive) Palette.red else Palette.label)) },
        onClick = onClick,
    )

/**
 * Laid out like the Siri Remote: two columns of round keys (Back and Home, Play/Pause and Mute,
 * Voice and Keyboard) beside full-height channel and volume rockers.
 */
@Composable
private fun Controls(vm: RemoteViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.openVoice() else vm.voicePermissionDenied()
    }
    val onMic = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.openVoice()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val round = Modifier.size(62.dp)

    Row(modifier.fillMaxWidth().height(226.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            IconKey(Icons.AutoMirrored.Rounded.ArrowBackIos, "Back", round, iconSize = 20) { vm.key(Keys.BACK) }
            Key("Play or Pause", round, onPress = vm::playPause) { PlayPauseGlyph(Modifier.size(width = 24.dp, height = 14.dp)) }
            // Works like the mic button on the TV's remote: hold, speak, let go. A quick tap also
            // works and stops listening when you stop talking.
            IconKey(Icons.Rounded.Mic, "Voice", round, iconSize = 24, onRelease = vm::releaseMic, onPress = onMic)
        }
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            IconKey(Icons.Rounded.Home, "Home", round, iconSize = 24) { vm.key(Keys.HOME) }
            IconKey(Icons.AutoMirrored.Rounded.VolumeOff, "Mute", round, iconSize = 22) { vm.key(Keys.MUTE) }
            IconKey(Icons.Rounded.Keyboard, "Keyboard", round, iconSize = 24) { vm.keyboardOpen = true }
        }
        Rocker(
            "Channel", Icons.Rounded.KeyboardArrowUp, Icons.Rounded.KeyboardArrowDown,
            onUp = { vm.key(Keys.CH_UP) }, onDown = { vm.key(Keys.CH_DOWN) },
        ) {
            Text("CH", style = Type.caption)
        }
        Rocker(
            "Volume", Icons.Rounded.Add, Icons.Rounded.Remove,
            onUp = { vm.key(Keys.VOL_UP) }, onDown = { vm.key(Keys.VOL_DOWN) },
        ) {
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Palette.secondary, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyboardSheet(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Palette.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = Side).padding(bottom = 20.dp).imePadding()) {
            Text("Keyboard", style = Type.headline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            InlineField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Text",
                action = "Send",
                imeAction = ImeAction.Send,
                modifier = Modifier.focusRequester(focus),
                onAction = { onSend(text) },
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
            Spacer(Modifier.height(8.dp))
            Text(
                "Type here to fill the TV's on-screen keyboard. YouTube and Netflix use their own keyboards and won't receive this. To search YouTube, hold the mic and say what you're looking for.",
                style = Type.footnote,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * Voice sheet: the phone listens, shows what it hears, then the remote acts on it. The mic pulses
 * with your voice while listening and turns into a checkmark when the command is done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSheet(vm: RemoteViewModel) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pulse by animateFloatAsState(
        if (vm.listening) 1f + vm.voiceLevel * 0.35f else 1f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "pulse",
    )
    val error = vm.voiceError
    val done = vm.voiceDone
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = vm::closeVoice,
        sheetState = sheet,
        containerColor = Palette.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Side).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                when {
                    vm.heard.isNotEmpty() -> "\u201C${vm.heard}\u201D"
                    vm.listening -> "Listening…"
                    else -> " "
                },
                style = Type.largeTitle.copy(fontSize = 22.sp, lineHeight = 28.sp, color = if (vm.heard.isEmpty()) Palette.secondary else Palette.label),
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                if (vm.listening) {
                    Box(
                        Modifier
                            .size(76.dp)
                            .graphicsLayer { scaleX = pulse * 1.3f; scaleY = pulse * 1.3f }
                            .clip(CircleShape)
                            .background(Palette.blue.copy(alpha = 0.22f))
                    )
                }
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(if (vm.listening || done != null || vm.voiceWorking != null) Palette.blue else Palette.fill)
                        .clickable(
                            enabled = !vm.listening && done == null && vm.voiceWorking == null && !vm.micDenied,
                            onClickLabel = "Try Again",
                        ) { vm.listen() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (vm.voiceWorking != null) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = Palette.label, strokeWidth = 2.5.dp)
                    } else {
                        Icon(
                            if (done != null) Icons.Rounded.Check else Icons.Rounded.Mic,
                            contentDescription = if (vm.listening) "Listening" else null,
                            tint = Palette.label,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                when {
                    done != null -> done
                    error != null -> error
                    vm.voiceWorking != null -> vm.voiceWorking!!
                    vm.listening && vm.holdingMic -> "Let go when you're done."
                    vm.listening -> "Try \u201COpen Netflix,\u201D \u201CVolume Up 5\u201D or \u201CMute.\u201D"
                    else -> ""
                },
                style = Type.footnote,
                textAlign = TextAlign.Center,
                minLines = 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(12.dp))
            when {
                vm.listening -> TextButton(onClick = vm::finishVoice) { Text("Done", style = Type.body.copy(color = Palette.blue)) }
                vm.voiceWorking != null -> TextButton(onClick = vm::closeVoice) { Text("Stop", style = Type.body.copy(color = Palette.blue)) }
                done == null -> Row {
                    TextButton(onClick = vm::closeVoice) { Text("Close", style = Type.body.copy(color = Palette.blue)) }
                    Spacer(Modifier.width(12.dp))
                    if (vm.micDenied) {
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            )
                            vm.closeVoice()
                        }) { Text("Open Settings", style = Type.body.copy(color = Palette.blue)) }
                    } else {
                        TextButton(onClick = vm::listen) { Text("Try Again", style = Type.body.copy(color = Palette.blue)) }
                    }
                }
                else -> Spacer(Modifier.height(48.dp))
            }
        }
    }
}

/** iOS-style filled field with the action as blue text inside it. */
@Composable
private fun InlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    action: String,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    busy: Boolean = false,
    onAction: () -> Unit,
) {
    val ready = value.isNotBlank() && !busy
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Type.body,
        placeholder = { Text(placeholder, style = Type.body.copy(color = Palette.tertiary)) },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { if (ready) onAction() }),
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Palette.fill,
            unfocusedContainerColor = Palette.fill,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            cursorColor = Palette.blue,
            focusedTextColor = Palette.label,
            unfocusedTextColor = Palette.label,
        ),
        trailingIcon = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(end = 16.dp).size(18.dp), color = Palette.secondary, strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onAction, enabled = ready) {
                    Text(action, style = Type.body.copy(color = if (ready) Palette.blue else Palette.tertiary))
                }
            }
        },
    )
}

// ---------------------------------------------------------------- Setup

@Composable
private fun SetupScreen(vm: RemoteViewModel) {
    var ip by rememberSaveable { mutableStateOf("") }
    val pairing = vm.pairingWith
    if (pairing != null) {
        PairingScreen(pairing, vm::cancelPairing)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = Side),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Icon(Icons.Rounded.Tv, null, tint = Palette.label, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Choose Your TV", style = Type.largeTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Make sure this phone and your TV are on the same Wi-Fi network.",
            style = Type.subhead,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(32.dp))

        Group {
            when {
                vm.found.isNotEmpty() -> vm.found.forEachIndexed { i, tv ->
                    if (i > 0) RowDivider()
                    TvRow(tv) { vm.pair(tv) }
                }
                vm.scanning -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Palette.secondary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Searching…", style = Type.body.copy(color = Palette.secondary))
                }
                else -> Column(Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No TVs Found", style = Type.headline)
                    Spacer(Modifier.height(4.dp))
                    Text("Check that the TV is on, or enter its IP address below.", style = Type.footnote, textAlign = TextAlign.Center)
                }
            }
        }
        if (!vm.scanning) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = vm::scan) { Text("Search Again", style = Type.body.copy(color = Palette.blue)) }
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(20.dp))
        InlineField(
            value = ip,
            onValueChange = { ip = it },
            placeholder = "IP Address",
            action = "Connect",
            imeAction = ImeAction.Go,
            keyboardType = KeyboardType.Uri,
            busy = vm.checkingIp,
            onAction = { vm.pairManual(ip) },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Find it on the TV in Settings under Network Status.",
            style = Type.footnote,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
        vm.setupError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = Type.footnote.copy(color = Palette.red), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PairingScreen(tv: TvInfo, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Tv, null, tint = Palette.label, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("Check Your TV", style = Type.largeTitle.copy(fontSize = 28.sp, lineHeight = 34.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Select Allow on ${tv.name}. The request expires after 30 seconds.",
            style = Type.subhead,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        CircularProgressIndicator(Modifier.size(22.dp), color = Palette.secondary, strokeWidth = 2.dp)
        Spacer(Modifier.height(28.dp))
        TextButton(onClick = onCancel) { Text("Cancel", style = Type.body.copy(color = Palette.blue)) }
    }
}

/** iOS inset grouped list section. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surface)) { content() }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.separator, thickness = 0.5.dp)

@Composable
private fun TvRow(tv: TvInfo, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tv.name, style = Type.body)
            Text("${tv.model} · ${tv.ip}", style = Type.footnote)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.tertiary)
    }
}

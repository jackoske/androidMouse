package dev.phantom.androidmouse.ui

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phantom.androidmouse.DiscoveredServer
import dev.phantom.androidmouse.HidConstants
import dev.phantom.androidmouse.HidViewModel
import dev.phantom.androidmouse.NetworkHidManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// ── Top-level screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: HidViewModel) {
    val netState by vm.netState.collectAsState()
    val isConnected = netState == NetworkHidManager.State.CONNECTED
    val discoveredServers by vm.discovery.servers.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var keyboardActive by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // Start mDNS discovery when disconnected
    LaunchedEffect(netState) {
        if (netState == NetworkHidManager.State.DISCONNECTED) {
            vm.discovery.startDiscovery()
        } else {
            vm.discovery.stopDiscovery()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIndicator(netState)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when (netState) {
                                NetworkHidManager.State.CONNECTED    -> "Connected"
                                NetworkHidManager.State.CONNECTING   -> "Connecting..."
                                NetworkHidManager.State.DISCONNECTED -> "Android Mouse"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(
                            Icons.Default.Settings, "Settings",
                            tint = if (showSettings) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── Connection ─────────────────────────────────────────────────
            ConnectionPanel(
                state        = netState,
                savedHost    = vm.savedHost,
                savedPort    = vm.savedPort,
                discovered   = discoveredServers,
                onConnect    = { host, port -> vm.connectNetwork(host, port) },
                onDisconnect = { vm.disconnectNetwork() },
            )

            // ── Settings (collapsible) ─────────────────────────────────────
            AnimatedVisibility(
                visible = showSettings,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                SettingsPanel(vm)
            }

            // ── Tab bar ─────────────────────────────────────────────────────
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {},
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Touchpad", style = MaterialTheme.typography.labelMedium) },
                    icon = { Icon(Icons.Default.TouchApp, null, Modifier.size(18.dp)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Air Mouse", style = MaterialTheme.typography.labelMedium) },
                    icon = { Icon(Icons.Default.PhoneAndroid, null, Modifier.size(18.dp)) },
                )
            }

            when (selectedTab) {
                0 -> {
                    // ── Touchpad ───────────────────────────────────────────
                    Touchpad(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        enabled  = isConnected,
                        onMove   = { dx, dy -> vm.moveMouse(dx, dy) },
                        onScroll = { dy -> vm.scroll(dy) },
                        onTap    = { fingers ->
                            when (fingers) {
                                1 -> { vm.clickDown(HidConstants.BTN_LEFT); vm.clickUp() }
                                2 -> { vm.clickDown(HidConstants.BTN_RIGHT); vm.clickUp() }
                                3 -> { vm.clickDown(HidConstants.BTN_MIDDLE); vm.clickUp() }
                            }
                        },
                    )

                    // ── Click buttons ──────────────────────────────────────
                    ClickButtons(isConnected, onDown = { vm.clickDown(it) }, onUp = { vm.clickUp() })

                    // ── Modifier keys ──────────────────────────────────────
                    ModifierKeysRow(vm, isConnected)

                    // ── Keyboard toggle + hidden capture ───────────────────
                    HiddenKeyboard(vm, isConnected, keyboardActive)

                    FilledTonalButton(
                        onClick  = { keyboardActive = !keyboardActive },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled  = isConnected,
                        shape    = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Keyboard, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (keyboardActive) "Hide Keyboard" else "Show Keyboard",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                1 -> {
                    // ── Air Mouse tab ──────────────────────────────────────
                    AirMouseTab(
                        vm = vm,
                        enabled = isConnected,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

// ── Connection panel ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionPanel(
    state: NetworkHidManager.State,
    savedHost: String,
    savedPort: String,
    discovered: List<DiscoveredServer>,
    onConnect: (String, Int) -> Unit,
    onDisconnect: () -> Unit,
) {
    var host by remember { mutableStateOf(savedHost) }
    var port by remember { mutableStateOf(savedPort) }

    // QR scanner launcher
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { contents ->
            val parts = contents.trim().split(":")
            if (parts.isNotEmpty()) {
                host = parts[0]
                if (parts.size >= 2) {
                    parts[1].toIntOrNull()?.let { port = it.toString() }
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan the QR code from the server")
                setBeepEnabled(false)
                setOrientationLocked(true)
            }
            scanLauncher.launch(options)
        }
    }

    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(16.dp),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            // Header row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                Icon(
                    Icons.Default.Wifi, null,
                    tint = when (state) {
                        NetworkHidManager.State.CONNECTED -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Network Connection",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (state == NetworkHidManager.State.DISCONNECTED) {
                    FilledTonalIconButton(
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.QrCodeScanner, "Scan QR", Modifier.size(18.dp))
                    }
                }
            }

            // ── Discovered servers ─────────────────────────────────────────
            if (state == NetworkHidManager.State.DISCONNECTED && discovered.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    discovered.forEach { server ->
                        AssistChip(
                            onClick = { onConnect(server.host, server.port) },
                            label = {
                                Text(
                                    server.name.removePrefix("AndroidMouse (").removeSuffix(")"),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            leadingIcon = {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp),
                        )
                    }
                }
            }

            // Input row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    modifier = Modifier.weight(1f).height(56.dp),
                    label = { Text("IP address", style = MaterialTheme.typography.labelSmall) },
                    placeholder = { Text("192.168.x.x", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    enabled = state == NetworkHidManager.State.DISCONNECTED,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it },
                    modifier = Modifier.width(80.dp).height(56.dp),
                    label = { Text("Port", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (state == NetworkHidManager.State.DISCONNECTED && host.isNotBlank())
                            onConnect(host.trim(), port.toIntOrNull() ?: 9393)
                    }),
                    enabled = state == NetworkHidManager.State.DISCONNECTED,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                when (state) {
                    NetworkHidManager.State.DISCONNECTED ->
                        FilledTonalButton(
                            onClick = { onConnect(host.trim(), port.toIntOrNull() ?: 9393) },
                            enabled = host.isNotBlank(),
                            modifier = Modifier.height(56.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("Connect", style = MaterialTheme.typography.labelMedium) }

                    NetworkHidManager.State.CONNECTING ->
                        CircularProgressIndicator(
                            Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.tertiary,
                        )

                    NetworkHidManager.State.CONNECTED ->
                        FilledTonalIconButton(
                            onClick = onDisconnect,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Default.Close, "Disconnect", Modifier.size(18.dp))
                        }
                }
            }
        }
    }
}

// ── Settings panel ────────────────────────────────────────────────────────────

@Composable
private fun SettingsPanel(vm: HidViewModel) {
    val sensitivity  by vm.sensitivity.collectAsState()
    val invertScroll by vm.invertScroll.collectAsState()

    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
            RoundedCornerShape(16.dp),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sensitivity", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        "%.1fx".format(sensitivity),
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Slider(
                value = sensitivity,
                onValueChange = { vm.setSensitivity(it) },
                valueRange = 0.5f..5.0f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Invert scroll", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = invertScroll,
                    onCheckedChange = { vm.setInvertScroll(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
    }
}

// ── Touchpad ──────────────────────────────────────────────────────────────────

private const val TAP_TIMEOUT_MS = 250L
private const val TAP_SLOP_PX   = 30f

@Composable
private fun Touchpad(
    modifier: Modifier,
    enabled: Boolean,
    onMove: (Float, Float) -> Unit,
    onScroll: (Float) -> Unit,
    onTap: (Int) -> Unit,
) {
    val borderColor = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                      else MaterialTheme.colorScheme.outlineVariant
    val bgTop = if (enabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val bgBottom = if (enabled) MaterialTheme.colorScheme.surface
                   else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)

    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .then(if (enabled) Modifier.touchpadInput(onMove, onScroll, onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (!enabled) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Wifi, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Connect to start",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun Modifier.touchpadInput(
    onMove: (Float, Float) -> Unit,
    onScroll: (Float) -> Unit,
    onTap: (Int) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val t0 = System.currentTimeMillis()
        var maxFingers = 1
        var totalDx = 0f; var totalDy = 0f

        while (true) {
            val ev = awaitPointerEvent()
            val active = ev.changes.filter { it.pressed }
            if (active.isEmpty()) break
            if (active.size > maxFingers) maxFingers = active.size

            when (active.size) {
                1 -> {
                    val p = active[0]
                    val dx = p.position.x - p.previousPosition.x
                    val dy = p.position.y - p.previousPosition.y
                    totalDx += dx; totalDy += dy
                    if (dx != 0f || dy != 0f) onMove(dx, dy)
                }
                else -> {
                    val avgDy = active.map { it.position.y - it.previousPosition.y }.average().toFloat()
                    totalDy += avgDy
                    if (avgDy != 0f) onScroll(avgDy)
                }
            }
            ev.changes.forEach { if (it.positionChanged()) it.consume() }
        }

        val elapsed = System.currentTimeMillis() - t0
        val moved = kotlin.math.sqrt(totalDx * totalDx + totalDy * totalDy)
        if (elapsed < TAP_TIMEOUT_MS && moved < TAP_SLOP_PX) onTap(maxFingers)
    }
}

// ── Click buttons ─────────────────────────────────────────────────────────────

@Composable
private fun ClickButtons(enabled: Boolean, onDown: (Int) -> Unit, onUp: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MouseButton("L", HidConstants.BTN_LEFT,   enabled, onDown, onUp, Modifier.weight(2f))
        MouseButton("M", HidConstants.BTN_MIDDLE, enabled, onDown, onUp, Modifier.weight(1f))
        MouseButton("R", HidConstants.BTN_RIGHT,  enabled, onDown, onUp, Modifier.weight(2f))
    }
}

@Composable
private fun MouseButton(
    label: String, mask: Int, enabled: Boolean,
    onDown: (Int) -> Unit, onUp: () -> Unit, modifier: Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier.fillMaxHeight().clip(shape).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(); pressed = true; onDown(mask)
                waitForUpOrCancellation(); pressed = false; onUp()
            }
        },
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            pressed  -> MaterialTheme.colorScheme.primary
            else     -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = shape,
        tonalElevation = if (pressed) 0.dp else 2.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    pressed  -> MaterialTheme.colorScheme.onPrimary
                    else     -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// ── Modifier keys ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModifierKeysRow(vm: HidViewModel, enabled: Boolean) {
    val mods by vm.activeModifiers.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ModKey("Ctrl",  HidConstants.MOD_LEFT_CTRL,  mods, vm, enabled, Modifier.weight(1f))
        ModKey("Alt",   HidConstants.MOD_LEFT_ALT,   mods, vm, enabled, Modifier.weight(1f))
        ModKey("Shift", HidConstants.MOD_LEFT_SHIFT, mods, vm, enabled, Modifier.weight(1f))
        ModKey("Super", HidConstants.MOD_LEFT_GUI,   mods, vm, enabled, Modifier.weight(1f))
        ModKey("Tab",   -1,                          mods, vm, enabled, Modifier.weight(1f))
        ModKey("Esc",   -2,                          mods, vm, enabled, Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModKey(
    label: String, mod: Int, activeMods: Int,
    vm: HidViewModel, enabled: Boolean, modifier: Modifier,
) {
    val isToggle = mod > 0
    val isActive = isToggle && (activeMods and mod != 0)

    FilterChip(
        selected = isActive,
        onClick = {
            when (mod) {
                -1 -> vm.pressTab()
                -2 -> vm.pressEscape()
                else -> vm.toggleModifier(mod)
            }
        },
        label = {
            Text(
                label,
                fontSize = 11.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        enabled = enabled,
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

// ── Hidden keyboard capture ───────────────────────────────────────────────────

@Composable
private fun HiddenKeyboard(vm: HidViewModel, enabled: Boolean, active: Boolean) {
    val focusRequester = remember { FocusRequester() }
    val kbController = LocalSoftwareKeyboardController.current

    var tfv by remember { mutableStateOf(TextFieldValue(" ", TextRange(1))) }

    LaunchedEffect(active) {
        if (active && enabled) {
            focusRequester.requestFocus()
            kbController?.show()
        } else {
            kbController?.hide()
        }
    }

    if (active && enabled) {
        BasicTextField(
            value = tfv,
            onValueChange = { new ->
                val oldLen = tfv.text.length
                val newLen = new.text.length
                when {
                    newLen > oldLen -> {
                        val added = new.text.substring(oldLen)
                        added.forEach { c ->
                            if (c == '\n') vm.pressEnter()
                            else vm.typeChar(c)
                        }
                    }
                    newLen < oldLen -> {
                        repeat(oldLen - newLen) { vm.pressBackspace() }
                    }
                }
                tfv = TextFieldValue(" ", TextRange(1))
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
            cursorBrush = SolidColor(Color.Transparent),
        )
    }
}

// ── Air Mouse tab ───────────────────────────────────────────────────────────

@Composable
private fun AirMouseTab(vm: HidViewModel, enabled: Boolean, modifier: Modifier) {
    val airActive by vm.airMouseActive.collectAsState()
    val airAvailable by vm.airMouseAvailable.collectAsState()

    // Stop air mouse when leaving the tab or disconnecting
    DisposableEffect(enabled) {
        onDispose { vm.stopAirMouse() }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── Control buttons row ─────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Start / Stop button
            Button(
                onClick = { if (airActive) vm.stopAirMouse() else vm.startAirMouse() },
                modifier = Modifier.weight(1f).height(56.dp),
                enabled = enabled && airAvailable,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (airActive) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    if (airActive) "Stop" else "Start",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Recenter button
            FilledTonalButton(
                onClick = { vm.recenterAirMouse() },
                modifier = Modifier.weight(1f).height(56.dp),
                enabled = enabled && airActive,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "Recenter",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (!airAvailable) {
            Text(
                "Gyroscope sensor not available on this device",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // ── Status area ─────────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .weight(0.3f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (airActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
                .border(
                    1.dp,
                    if (airActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(16.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    !enabled -> "Connect to start"
                    !airAvailable -> "No gyroscope"
                    airActive -> "Move your phone to control the cursor"
                    else -> "Tap Start to begin"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (airActive) 0.8f else 0.4f
                ),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }

        // ── Big click buttons ───────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().weight(0.7f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BigMouseButton("L", HidConstants.BTN_LEFT, enabled, { vm.clickDown(it) }, { vm.clickUp() }, Modifier.weight(2f))
            BigMouseButton("M", HidConstants.BTN_MIDDLE, enabled, { vm.clickDown(it) }, { vm.clickUp() }, Modifier.weight(1f))
            BigMouseButton("R", HidConstants.BTN_RIGHT, enabled, { vm.clickDown(it) }, { vm.clickUp() }, Modifier.weight(2f))
        }
    }
}

@Composable
private fun BigMouseButton(
    label: String, mask: Int, enabled: Boolean,
    onDown: (Int) -> Unit, onUp: () -> Unit, modifier: Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier.fillMaxHeight().clip(shape).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(); pressed = true; onDown(mask)
                waitForUpOrCancellation(); pressed = false; onUp()
            }
        },
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            pressed  -> MaterialTheme.colorScheme.primary
            else     -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = shape,
        tonalElevation = if (pressed) 0.dp else 4.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    pressed  -> MaterialTheme.colorScheme.onPrimary
                    else     -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// ── Status indicator ─────────────────────────────────────────────────────────

@Composable
private fun StatusIndicator(state: NetworkHidManager.State) {
    val color = when (state) {
        NetworkHidManager.State.CONNECTED    -> MaterialTheme.colorScheme.primary
        NetworkHidManager.State.CONNECTING   -> MaterialTheme.colorScheme.tertiary
        NetworkHidManager.State.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (state == NetworkHidManager.State.CONNECTED) {
                    Modifier.border(2.dp, color.copy(alpha = 0.3f), CircleShape)
                } else Modifier
            )
    )
}

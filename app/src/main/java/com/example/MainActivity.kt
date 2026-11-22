package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.tui.TerminalKeyEvent
import com.example.ui.TerminalViewModel
import com.example.ui.UiEffect
import com.example.ui.components.ExtraKeysBar
import com.example.ui.components.FileManagerSheet
import com.example.ui.components.PackageManagerSheet
import com.example.ui.components.SettingsSheet
import com.example.ui.components.ShortcutsHelpDialog
import com.example.ui.components.TermuxDrawerContent
import com.example.ui.components.TerminalCanvas
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TerminalSchemes
import com.example.ui.theme.TermuxCppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TermuxCppTheme {
                TermuxAppRoot(activity = this)
            }
        }
    }
}

@Composable
fun TermuxAppRoot(
    activity: ComponentActivity,
    vm: TerminalViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val prefs by vm.preferences.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val activeSessionId by vm.activeSessionId.collectAsState()
    val installedIds by vm.installedPackages.collectAsState()
    val installingIds by vm.installingPackageIds.collectAsState()
    val ctrlActive by vm.ctrlActive.collectAsState()
    val altActive by vm.altActive.collectAsState()

    val scheme = remember(prefs.themeId) { TerminalSchemes.byId(prefs.themeId) }
    val activeSession = remember(sessions, activeSessionId) { vm.activeSession() }

    var showPackageSheet by remember { mutableStateOf(false) }
    var showFileSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }

    val safImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            var fileName = "imported_file"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    fileName = cursor.getString(idx)
                }
            }
            vm.importSafUriToHome(uri, fileName)
        }
    }

    LaunchedEffect(Unit) {
        vm.effects.collect { eff ->
            when (eff) {
                is UiEffect.OpenUrl -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(eff.url))
                    runCatching { context.startActivity(intent) }
                }
                UiEffect.LaunchSafImport -> safImportLauncher.launch(arrayOf("*/*"))
            }
        }
    }

    DisposableEffect(prefs.keepScreenOn) {
        if (prefs.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            TermuxDrawerContent(
                sessions = sessions,
                activeSessionId = activeSessionId,
                installedCount = installedIds.size,
                onSelectSession = { id ->
                    vm.selectSession(id)
                    scope.launch { drawerState.close() }
                },
                onNewSession = {
                    vm.createNewSession()
                    scope.launch { drawerState.close() }
                },
                onRenameSession = vm::renameSession,
                onCloseSession = vm::closeSession,
                onOpenPackages = {
                    scope.launch { drawerState.close() }
                    showPackageSheet = true
                },
                onOpenFiles = {
                    scope.launch { drawerState.close() }
                    showFileSheet = true
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    showSettingsSheet = true
                },
                onOpenHelp = {
                    scope.launch { drawerState.close() }
                    showHelpDialog = true
                },
                onToggleKeyboard = {
                    scope.launch { drawerState.close() }
                    keyboardController?.show()
                },
                onQuickRunCppDemo = {
                    scope.launch { drawerState.close() }
                    vm.runCommandInActiveSession("pkg install clang && g++ ~/examples/hello.cpp -o ~/hello && ~/hello")
                },
                onQuickRunPythonDemo = {
                    scope.launch { drawerState.close() }
                    vm.runCommandInActiveSession("pkg install python && python3 ~/examples/fibonacci.py")
                }
            )
        }
    ) {
        Scaffold(
            containerColor = scheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { _ ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.background)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                if (activeSession != null) {
                    val sessionName by activeSession.sessionName.collectAsState()
                    val cwdDisplay by activeSession.cwdDisplay.collectAsState()
                    val isRunning by activeSession.isRunningCommand.collectAsState()
                    val snapshot by activeSession.terminal.snapshot.collectAsState()

                    // Slim Termux Session Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(scheme.extraKeysBg)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.size(40.dp).testTag("open_drawer_btn")
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "Menu sessions",
                                    tint = scheme.foreground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { scope.launch { drawerState.open() } }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isRunning) scheme.ansiColors[6] else scheme.cursor)
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    text = "$sessionName • $cwdDisplay",
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = scheme.foreground.copy(alpha = 0.88f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { showPackageSheet = true },
                                modifier = Modifier.size(40.dp).testTag("top_packages_btn")
                            ) {
                                Icon(
                                    Icons.Default.Inventory2,
                                    contentDescription = "Paquets pkg",
                                    tint = scheme.cursor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { vm.pasteClipboardToTerminal() },
                                modifier = Modifier.size(40.dp).testTag("top_paste_btn")
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = "Coller",
                                    tint = scheme.foreground.copy(alpha = 0.82f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Box {
                                IconButton(
                                    onClick = { showTopMenu = true },
                                    modifier = Modifier.size(40.dp).testTag("top_more_btn")
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "Plus d'options",
                                        tint = scheme.foreground.copy(alpha = 0.82f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showTopMenu,
                                    onDismissRequest = { showTopMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Copier tout le terminal") },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                        onClick = {
                                            showTopMenu = false
                                            vm.copyTerminalToClipboard()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Coller presse-papiers") },
                                        leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                                        onClick = {
                                            showTopMenu = false
                                            vm.pasteClipboardToTerminal()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Envoyer Ctrl+C") },
                                        onClick = {
                                            showTopMenu = false
                                            vm.sendSpecialKey(TerminalKeyEvent.Ctrl('c'))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Paramètres") },
                                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        onClick = {
                                            showTopMenu = false
                                            showSettingsSheet = true
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Main Terminal Viewport
                    TerminalCanvas(
                        snapshot = snapshot,
                        scheme = scheme,
                        fontSizeSp = prefs.fontSizeSp,
                        cursorStyle = prefs.cursorStyle,
                        onResizeGrid = { cols, rows -> activeSession.terminal.resize(cols, rows) },
                        onCharInput = { ch -> vm.sendTextOrChar(ch) },
                        onSpecialKey = { ev -> vm.sendSpecialKey(ev) },
                        onFontSizeChange = { next -> vm.updateFontSize(next) },
                        onOpenContextMenu = { showTopMenu = true },
                        modifier = Modifier.weight(1f)
                    )

                    // Termux Extra Keys Bar
                    if (prefs.showExtraKeys) {
                        ExtraKeysBar(
                            scheme = scheme,
                            ctrlActive = ctrlActive,
                            altActive = altActive,
                            showCodingRow = prefs.showCodingSymbolsRow,
                            vibrateOnKey = prefs.vibrateOnKey,
                            onToggleCtrl = vm::toggleCtrl,
                            onToggleAlt = vm::toggleAlt,
                            onToggleCodingRow = { vm.updateShowCodingRow(!prefs.showCodingSymbolsRow) },
                            onSpecialKey = vm::sendSpecialKey,
                            onChar = vm::sendTextOrChar
                        )
                    }
                }
            }
        }
    }

    if (showPackageSheet) {
        PackageManagerSheet(
            catalog = vm.packageRepo.catalog,
            installedIds = installedIds,
            installingIds = installingIds,
            onInstall = vm::installPackageFromSheet,
            onRemove = vm::removePackageFromSheet,
            onDismiss = { showPackageSheet = false }
        )
    }

    if (showFileSheet) {
        FileManagerSheet(
            env = vm.env,
            onRunInTerminal = vm::runCommandInActiveSession,
            onLaunchSafImport = { safImportLauncher.launch(arrayOf("*/*")) },
            onDismiss = { showFileSheet = false }
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            prefs = prefs,
            onSelectTheme = vm::updateTheme,
            onFontSizeChange = vm::updateFontSize,
            onCursorStyleChange = vm::updateCursorStyle,
            onShowExtraKeysChange = vm::updateShowExtraKeys,
            onVibrateChange = vm::updateVibrate,
            onKeepScreenOnChange = vm::updateKeepScreenOn,
            onDismiss = { showSettingsSheet = false }
        )
    }

    if (showHelpDialog) {
        ShortcutsHelpDialog(onDismiss = { showHelpDialog = false })
    }
}

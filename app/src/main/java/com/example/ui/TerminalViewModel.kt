package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.fs.TermuxEnvironment
import com.example.core.packages.PackageRepository
import com.example.core.packages.TermuxPackage
import com.example.core.shell.ShellSession
import com.example.core.tools.AndroidBridge
import com.example.core.tui.TerminalKeyEvent
import com.example.data.SettingsRepository
import com.example.data.TerminalPreferences
import com.example.ui.theme.CursorStyle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

sealed interface UiEffect {
    data class OpenUrl(val url: String) : UiEffect
    data object LaunchSafImport : UiEffect
}

data class SessionTabInfo(
    val id: String,
    val name: String,
    val cwd: String,
    val isRunning: Boolean
)

class TerminalViewModel(app: Application) : AndroidViewModel(app), AndroidBridge {

    val env = TermuxEnvironment(app)
    val packageRepo = PackageRepository(env)
    val settingsRepo = SettingsRepository(app)

    val preferences: StateFlow<TerminalPreferences> = settingsRepo.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalPreferences())

    val installedPackages: StateFlow<Set<String>> = packageRepo.installedIds

    private val sessionCounter = AtomicInteger(1)
    private val sessionsMap = linkedMapOf<String, ShellSession>()

    private val _sessions = MutableStateFlow<List<ShellSession>>(emptyList())
    val sessions: StateFlow<List<ShellSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow("")
    val activeSessionId: StateFlow<String> = _activeSessionId.asStateFlow()

    private val _ctrlActive = MutableStateFlow(false)
    val ctrlActive: StateFlow<Boolean> = _ctrlActive.asStateFlow()

    private val _altActive = MutableStateFlow(false)
    val altActive: StateFlow<Boolean> = _altActive.asStateFlow()

    private val _effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private val _installingPackageIds = MutableStateFlow<Set<String>>(emptySet())
    val installingPackageIds: StateFlow<Set<String>> = _installingPackageIds.asStateFlow()

    init {
        createNewSession()
    }

    fun activeSession(): ShellSession? = sessionsMap[_activeSessionId.value] ?: _sessions.value.firstOrNull()

    fun createNewSession(initialCommand: String? = null): ShellSession {
        val num = sessionCounter.getAndIncrement()
        val id = "session_$num"
        val session = ShellSession(
            id = id,
            initialName = "Session $num",
            context = getApplication(),
            env = env,
            packageRepo = packageRepo,
            bridge = this,
            scope = viewModelScope,
            sessionNamesProvider = { _sessions.value.map { it.sessionName.value } },
            onCloseRequested = { closeId -> closeSession(closeId) }
        )
        sessionsMap[id] = session
        _sessions.value = sessionsMap.values.toList()
        _activeSessionId.value = id
        if (!initialCommand.isNullOrBlank()) {
            session.submitCommandProgrammatically(initialCommand)
        }
        return session
    }

    fun selectSession(id: String) {
        if (sessionsMap.containsKey(id)) {
            _activeSessionId.value = id
        }
    }

    fun renameSession(id: String, newName: String) {
        sessionsMap[id]?.rename(newName)
    }

    fun closeSession(id: String) {
        val target = sessionsMap.remove(id)
        target?.dispose()
        if (sessionsMap.isEmpty()) {
            createNewSession()
            return
        }
        _sessions.value = sessionsMap.values.toList()
        if (_activeSessionId.value == id) {
            _activeSessionId.value = _sessions.value.first().id
        }
    }

    fun toggleCtrl() {
        _ctrlActive.value = !_ctrlActive.value
    }

    fun toggleAlt() {
        _altActive.value = !_altActive.value
    }

    fun sendTextOrChar(ch: Char) {
        val session = activeSession() ?: return
        if (_ctrlActive.value) {
            _ctrlActive.value = false
            session.handleKey(TerminalKeyEvent.Ctrl(ch))
        } else {
            if (_altActive.value) _altActive.value = false
            session.handleKey(TerminalKeyEvent.CharInput(ch))
        }
    }

    fun sendSpecialKey(event: TerminalKeyEvent) {
        val session = activeSession() ?: return
        if (event is TerminalKeyEvent.CharInput && _ctrlActive.value) {
            _ctrlActive.value = false
            session.handleKey(TerminalKeyEvent.Ctrl(event.ch))
            return
        }
        if (_altActive.value) _altActive.value = false
        session.handleKey(event)
    }

    fun runCommandInActiveSession(command: String) {
        activeSession()?.submitCommandProgrammatically(command)
    }

    fun installPackageFromSheet(pkg: TermuxPackage) {
        if (pkg.id in _installingPackageIds.value) return
        viewModelScope.launch {
            _installingPackageIds.value = _installingPackageIds.value + pkg.id
            try {
                val session = activeSession()
                packageRepo.installPackage(pkg.id) { line ->
                    session?.terminal?.write(line.replace("\n", "\r\n"))
                }
            } finally {
                _installingPackageIds.value = _installingPackageIds.value - pkg.id
            }
        }
    }

    fun removePackageFromSheet(pkg: TermuxPackage) {
        viewModelScope.launch {
            val session = activeSession()
            packageRepo.removePackage(pkg.id) { line ->
                session?.terminal?.write(line.replace("\n", "\r\n"))
            }
        }
    }

    fun copyTerminalToClipboard() {
        val text = activeSession()?.terminal?.getPlainText() ?: return
        setClipboard(text)
        showToast("Contenu du terminal copié")
    }

    fun pasteClipboardToTerminal() {
        val text = getClipboard()
        if (text.isNotEmpty()) {
            activeSession()?.pasteText(text)
        }
    }

    fun importSafUriToHome(uri: Uri, fileName: String) {
        viewModelScope.launch {
            try {
                val resolver = getApplication<Application>().contentResolver
                val destDir = File(env.homeDir, "storage/shared").apply { mkdirs() }
                val target = File(destDir, fileName.ifBlank { "imported_${System.currentTimeMillis()}" })
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                showToast("Importé vers ~/storage/shared/${target.name}")
            } catch (e: Exception) {
                showToast("Erreur d'import: ${e.message}")
            }
        }
    }

    fun updateTheme(id: String) = viewModelScope.launch { settingsRepo.setThemeId(id) }
    fun updateFontSize(sizeSp: Float) = viewModelScope.launch { settingsRepo.setFontSize(sizeSp) }
    fun updateCursorStyle(style: CursorStyle) = viewModelScope.launch { settingsRepo.setCursorStyle(style) }
    fun updateShowExtraKeys(show: Boolean) = viewModelScope.launch { settingsRepo.setShowExtraKeys(show) }
    fun updateShowCodingRow(show: Boolean) = viewModelScope.launch { settingsRepo.setShowCodingRow(show) }
    fun updateVibrate(enabled: Boolean) = viewModelScope.launch { settingsRepo.setVibrateOnKey(enabled) }
    fun updateKeepScreenOn(enabled: Boolean) = viewModelScope.launch { settingsRepo.setKeepScreenOn(enabled) }

    override fun showToast(message: String) {
        viewModelScope.launch {
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun getClipboard(): String {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.coerceToText(getApplication())?.toString() ?: ""
    }

    override fun setClipboard(text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Termux++", text))
    }

    override fun openUrl(url: String) {
        _effects.tryEmit(UiEffect.OpenUrl(url))
    }

    override fun openStorageSetup() {
        _effects.tryEmit(UiEffect.LaunchSafImport)
    }

    override fun onCleared() {
        super.onCleared()
        sessionsMap.values.forEach { it.dispose() }
    }
}

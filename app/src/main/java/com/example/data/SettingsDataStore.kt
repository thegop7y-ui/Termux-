package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.CursorStyle
import com.example.ui.theme.TerminalSchemes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "termux_settings")

data class TerminalPreferences(
    val themeId: String = TerminalSchemes.TermuxClassic.id,
    val fontSizeSp: Float = 13.5f,
    val cursorStyle: CursorStyle = CursorStyle.BLOCK,
    val showExtraKeys: Boolean = true,
    val showCodingSymbolsRow: Boolean = false,
    val vibrateOnKey: Boolean = true,
    val keepScreenOn: Boolean = false
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val THEME_ID = stringPreferencesKey("theme_id")
        val FONT_SIZE = floatPreferencesKey("font_size_sp")
        val CURSOR_STYLE = stringPreferencesKey("cursor_style")
        val SHOW_EXTRA_KEYS = booleanPreferencesKey("show_extra_keys")
        val SHOW_CODING_ROW = booleanPreferencesKey("show_coding_row")
        val VIBRATE_ON_KEY = booleanPreferencesKey("vibrate_on_key")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }

    val preferences: Flow<TerminalPreferences> = context.dataStore.data.map { prefs ->
        TerminalPreferences(
            themeId = prefs[Keys.THEME_ID] ?: TerminalSchemes.TermuxClassic.id,
            fontSizeSp = (prefs[Keys.FONT_SIZE] ?: 13.5f).coerceIn(9f, 24f),
            cursorStyle = prefs[Keys.CURSOR_STYLE]?.let { name ->
                CursorStyle.entries.find { it.name == name }
            } ?: CursorStyle.BLOCK,
            showExtraKeys = prefs[Keys.SHOW_EXTRA_KEYS] ?: true,
            showCodingSymbolsRow = prefs[Keys.SHOW_CODING_ROW] ?: false,
            vibrateOnKey = prefs[Keys.VIBRATE_ON_KEY] ?: true,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: false
        )
    }

    suspend fun setThemeId(id: String) {
        context.dataStore.edit { it[Keys.THEME_ID] = id }
    }

    suspend fun setFontSize(sizeSp: Float) {
        context.dataStore.edit { it[Keys.FONT_SIZE] = sizeSp.coerceIn(9f, 24f) }
    }

    suspend fun setCursorStyle(style: CursorStyle) {
        context.dataStore.edit { it[Keys.CURSOR_STYLE] = style.name }
    }

    suspend fun setShowExtraKeys(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_EXTRA_KEYS] = show }
    }

    suspend fun setShowCodingRow(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_CODING_ROW] = show }
    }

    suspend fun setVibrateOnKey(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATE_ON_KEY] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }
}

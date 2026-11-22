package com.example.ui.theme

import androidx.compose.ui.graphics.Color

enum class CursorStyle(val label: String) {
    BLOCK("Bloc"),
    BAR("Barre"),
    UNDERLINE("Souligné")
}

data class TerminalColorScheme(
    val id: String,
    val name: String,
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val selection: Color,
    val extraKeysBg: Color,
    val extraKeyCap: Color,
    val extraKeyActive: Color,
    val ansiColors: List<Color>
)

object TerminalSchemes {
    val TermuxClassic = TerminalColorScheme(
        id = "termux_classic",
        name = "Termux Classic",
        background = Color(0xFF0B0E11),
        foreground = Color(0xFFE6EDF3),
        cursor = Color(0xFF00E676),
        selection = Color(0x4400E676),
        extraKeysBg = Color(0xFF14191F),
        extraKeyCap = Color(0xFF212933),
        extraKeyActive = Color(0xFF00E676),
        ansiColors = listOf(
            Color(0xFF1C2128), // 0 black
            Color(0xFFF87171), // 1 red
            Color(0xFF00E676), // 2 green
            Color(0xFFFBBF24), // 3 yellow
            Color(0xFF60A5FA), // 4 blue
            Color(0xFFC084FC), // 5 magenta
            Color(0xFF38BDF8), // 6 cyan
            Color(0xFFE6EDF3), // 7 white
            Color(0xFF6E7681), // 8 bright black
            Color(0xFFFCA5A5), // 9 bright red
            Color(0xFF69F0AE), // 10 bright green
            Color(0xFFFDE047), // 11 bright yellow
            Color(0xFF93C5FD), // 12 bright blue
            Color(0xFFD8B4FE), // 13 bright magenta
            Color(0xFF7DD3FC), // 14 bright cyan
            Color(0xFFFFFFFF)  // 15 bright white
        )
    )

    val Dracula = TerminalColorScheme(
        id = "dracula",
        name = "Dracula",
        background = Color(0xFF282A36),
        foreground = Color(0xFFF8F8F2),
        cursor = Color(0xFF50FA7B),
        selection = Color(0x5544475A),
        extraKeysBg = Color(0xFF21222C),
        extraKeyCap = Color(0xFF343746),
        extraKeyActive = Color(0xFFBD93F9),
        ansiColors = listOf(
            Color(0xFF21222C), Color(0xFFFF5555), Color(0xFF50FA7B), Color(0xFFF1FA8C),
            Color(0xFFBD93F9), Color(0xFFFF79C6), Color(0xFF8BE9FD), Color(0xFFF8F8F2),
            Color(0xFF6272A4), Color(0xFFFF6E6E), Color(0xFF69FF94), Color(0xFFFFFFA5),
            Color(0xFFD6ACFF), Color(0xFFFF92DF), Color(0xFFA4FFFF), Color(0xFFFFFFFF)
        )
    )

    val Nord = TerminalColorScheme(
        id = "nord",
        name = "Nord",
        background = Color(0xFF2E3440),
        foreground = Color(0xFFD8DEE9),
        cursor = Color(0xFF88C0D0),
        selection = Color(0x554C566A),
        extraKeysBg = Color(0xFF242933),
        extraKeyCap = Color(0xFF3B4252),
        extraKeyActive = Color(0xFF88C0D0),
        ansiColors = listOf(
            Color(0xFF3B4252), Color(0xFFBF616A), Color(0xFFA3BE8C), Color(0xFFEBCB8B),
            Color(0xFF81A1C1), Color(0xFFB48EAD), Color(0xFF88C0D0), Color(0xFFE5E9F0),
            Color(0xFF4C566A), Color(0xFFD08770), Color(0xFFA3BE8C), Color(0xFFEBCB8B),
            Color(0xFF81A1C1), Color(0xFFB48EAD), Color(0xFF8FBCBB), Color(0xFFECEFF4)
        )
    )

    val Monokai = TerminalColorScheme(
        id = "monokai",
        name = "Monokai Pro",
        background = Color(0xFF272822),
        foreground = Color(0xFFF8F8F2),
        cursor = Color(0xFFA6E22E),
        selection = Color(0x5549483E),
        extraKeysBg = Color(0xFF1E1F1C),
        extraKeyCap = Color(0xFF383A32),
        extraKeyActive = Color(0xFFA6E22E),
        ansiColors = listOf(
            Color(0xFF272822), Color(0xFFF92672), Color(0xFFA6E22E), Color(0xFFF4BF75),
            Color(0xFF66D9EF), Color(0xFFAE81FF), Color(0xFFA1EFE4), Color(0xFFF8F8F2),
            Color(0xFF75715E), Color(0xFFF92672), Color(0xFFA6E22E), Color(0xFFE6DB74),
            Color(0xFF66D9EF), Color(0xFFAE81FF), Color(0xFFA1EFE4), Color(0xFFF9F8F5)
        )
    )

    val SolarizedDark = TerminalColorScheme(
        id = "solarized_dark",
        name = "Solarized Dark",
        background = Color(0xFF002B36),
        foreground = Color(0xFF839496),
        cursor = Color(0xFF2AA198),
        selection = Color(0x55073642),
        extraKeysBg = Color(0xFF00212B),
        extraKeyCap = Color(0xFF073642),
        extraKeyActive = Color(0xFF2AA198),
        ansiColors = listOf(
            Color(0xFF073642), Color(0xFFDC322F), Color(0xFF859900), Color(0xFFB58900),
            Color(0xFF268BD2), Color(0xFFD33682), Color(0xFF2AA198), Color(0xFFEEE8D5),
            Color(0xFF586E75), Color(0xFFCB4B16), Color(0xFF859900), Color(0xFFB58900),
            Color(0xFF268BD2), Color(0xFF6C71C4), Color(0xFF2AA198), Color(0xFFFDF6E3)
        )
    )

    val GruvboxDark = TerminalColorScheme(
        id = "gruvbox",
        name = "Gruvbox Dark",
        background = Color(0xFF1D2021),
        foreground = Color(0xFFEBDBB2),
        cursor = Color(0xFFB8BB26),
        selection = Color(0x55504945),
        extraKeysBg = Color(0xFF161819),
        extraKeyCap = Color(0xFF32302F),
        extraKeyActive = Color(0xFFFABD2F),
        ansiColors = listOf(
            Color(0xFF282828), Color(0xFFCC241D), Color(0xFF98971A), Color(0xFFD79921),
            Color(0xFF458588), Color(0xFFB16286), Color(0xFF689D6A), Color(0xFFA89984),
            Color(0xFF928374), Color(0xFFFB4934), Color(0xFFB8BB26), Color(0xFFFABD2F),
            Color(0xFF83A598), Color(0xFFD3869B), Color(0xFF8EC07C), Color(0xFFEBDBB2)
        )
    )

    val all = listOf(TermuxClassic, Dracula, Nord, Monokai, SolarizedDark, GruvboxDark)

    fun byId(id: String): TerminalColorScheme = all.find { it.id == id } ?: TermuxClassic
}

package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.fs.TermuxEnvironment
import com.example.core.packages.TermuxPackage
import com.example.data.TerminalPreferences
import com.example.ui.theme.CursorStyle
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TerminalSchemes
import com.example.ui.theme.TermuxBorder
import com.example.ui.theme.TermuxCyan
import com.example.ui.theme.TermuxEmerald
import com.example.ui.theme.TermuxRed
import com.example.ui.theme.TermuxSurface
import com.example.ui.theme.TermuxSurfaceElevated
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageManagerSheet(
    catalog: List<TermuxPackage>,
    installedIds: Set<String>,
    installingIds: Set<String>,
    onInstall: (TermuxPackage) -> Unit,
    onRemove: (TermuxPackage) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Tous") }
    val categories = remember(catalog) {
        listOf("Tous") + catalog.map { it.category }.distinct()
    }
    val filtered = remember(catalog, query, selectedCategory) {
        catalog.filter { pkg ->
            val catOk = selectedCategory == "Tous" || pkg.category == selectedCategory
            val q = query.trim().lowercase()
            val qOk = q.isEmpty() || pkg.id.contains(q) || pkg.description.lowercase().contains(q) ||
                pkg.binaries.any { it.contains(q) }
            catOk && qOk
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TermuxSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .padding(horizontal = 16.dp)
        ) {
            Text("Gestionnaire de paquets (pkg)", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${installedIds.size} installés sur ${catalog.size} paquets disponibles",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Rechercher python, clang, git, curl...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pkg_search_input")
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { pkg ->
                    val isInstalled = pkg.id in installedIds
                    val isBusy = pkg.id in installingIds
                    Surface(
                        color = TermuxSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, TermuxBorder, RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = pkg.id,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isInstalled) TermuxEmerald else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "v${pkg.version} • ${pkg.sizeKb} kB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = pkg.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "cmd: ${pkg.binaries.take(5).joinToString(", ")}",
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TermuxCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            when {
                                isBusy -> CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                                isInstalled && pkg.essential -> Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Essentiel installé",
                                    tint = TermuxEmerald
                                )
                                isInstalled -> OutlinedButton(
                                    onClick = { onRemove(pkg) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TermuxRed),
                                    modifier = Modifier.testTag("pkg_remove_${pkg.id}")
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Supprimer", modifier = Modifier.size(16.dp))
                                }
                                else -> Button(
                                    onClick = { onInstall(pkg) },
                                    modifier = Modifier.testTag("pkg_install_${pkg.id}")
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Installer", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Installer")
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerSheet(
    env: TermuxEnvironment,
    onRunInTerminal: (String) -> Unit,
    onLaunchSafImport: () -> Unit,
    onDismiss: () -> Unit
) {
    var currentDir by remember { mutableStateOf(env.homeDir) }
    var refreshTick by remember { mutableStateOf(0) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("main.cpp") }

    val entries = remember(currentDir, refreshTick) {
        currentDir.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .orEmpty()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TermuxSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentDir.canonicalPath != env.homeDir.canonicalPath && currentDir.parentFile != null) {
                        IconButton(onClick = { currentDir = currentDir.parentFile!! }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Dossier parent")
                        }
                    }
                    Column {
                        Text("Explorateur de fichiers", style = MaterialTheme.typography.titleMedium)
                        Text(
                            env.toDisplayPath(currentDir),
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = TermuxCyan
                        )
                    }
                }
                Row {
                    IconButton(onClick = onLaunchSafImport, modifier = Modifier.testTag("file_import_saf")) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Importer depuis Android", tint = TermuxCyan)
                    }
                    IconButton(onClick = { showNewFileDialog = true }, modifier = Modifier.testTag("file_new_btn")) {
                        Icon(Icons.Default.Add, contentDescription = "Nouveau fichier", tint = TermuxEmerald)
                    }
                }
            }
            HorizontalDivider(color = TermuxBorder, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(entries, key = { it.absolutePath }) { f ->
                    Surface(
                        color = TermuxSurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (f.isDirectory) currentDir = f
                                else {
                                    onRunInTerminal("nano ${f.absolutePath}")
                                    onDismiss()
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (f.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                tint = if (f.isDirectory) TermuxCyan else TermuxEmerald
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, fontFamily = JetBrainsMonoFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (f.isFile) {
                                    Text("${f.length()} octets", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (f.isFile && (f.name.endsWith(".cpp") || f.name.endsWith(".py") || f.name.endsWith(".sh"))) {
                                IconButton(onClick = {
                                    val cmd = when {
                                        f.name.endsWith(".cpp") -> "pkg install clang && g++ \"${f.absolutePath}\" -o \"${currentDir.absolutePath}/app\" && \"${currentDir.absolutePath}/app\""
                                        f.name.endsWith(".py") -> "pkg install python && python3 \"${f.absolutePath}\""
                                        else -> "bash \"${f.absolutePath}\""
                                    }
                                    onRunInTerminal(cmd)
                                    onDismiss()
                                }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Exécuter", tint = TermuxEmerald)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Créer un fichier") },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("Nom (ex: test.cpp, script.py)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val created = File(currentDir, newFileName.trim().ifEmpty { "note.txt" })
                    val template = when {
                        created.name.endsWith(".cpp") -> "#include <iostream>\nusing namespace std;\n\nint main() {\n    cout << \"Hello C++!\" << endl;\n    return 0;\n}\n"
                        created.name.endsWith(".py") -> "print('Hello Python!')\n"
                        else -> ""
                    }
                    created.writeText(template)
                    showNewFileDialog = false
                    refreshTick++
                    onRunInTerminal("nano \"${created.absolutePath}\"")
                    onDismiss()
                }) { Text("Créer & éditer") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) { Text("Annuler") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    prefs: TerminalPreferences,
    onSelectTheme: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onCursorStyleChange: (CursorStyle) -> Unit,
    onShowExtraKeysChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TermuxSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Apparence & Terminal", style = MaterialTheme.typography.headlineSmall)

            Text("Thème de couleurs", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TerminalSchemes.all.forEach { scheme ->
                    val selected = prefs.themeId == scheme.id
                    Surface(
                        color = scheme.background,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) TermuxEmerald else TermuxBorder,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectTheme(scheme.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(scheme.cursor)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(scheme.name, color = scheme.foreground, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            Column {
                Text("Taille de police : ${prefs.fontSizeSp.roundToInt()} sp", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = prefs.fontSizeSp,
                    onValueChange = onFontSizeChange,
                    valueRange = 10f..22f
                )
            }

            Column {
                Text("Style du curseur", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CursorStyle.entries.forEach { style ->
                        FilterChip(
                            selected = prefs.cursorStyle == style,
                            onClick = { onCursorStyleChange(style) },
                            label = { Text(style.label) }
                        )
                    }
                }
            }

            SettingToggleRow("Afficher la barre Extra Keys", prefs.showExtraKeys, onShowExtraKeysChange)
            SettingToggleRow("Retour haptique sur les touches", prefs.vibrateOnKey, onVibrateChange)
            SettingToggleRow("Garder l'écran allumé", prefs.keepScreenOn, onKeepScreenOnChange)
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun SettingToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun ShortcutsHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aide & Raccourcis Termux++") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Paquets :", fontWeight = FontWeight.Bold, color = TermuxEmerald)
                Text("pkg search <mot>  |  pkg install python clang git curl", fontFamily = JetBrainsMonoFamily, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text("C++20 :", fontWeight = FontWeight.Bold, color = TermuxCyan)
                Text("g++ ~/examples/hello.cpp -o hello && ./hello", fontFamily = JetBrainsMonoFamily, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text("Python 3 :", fontWeight = FontWeight.Bold, color = TermuxEmerald)
                Text("python3 ~/examples/fibonacci.py  |  python3 (REPL)", fontFamily = JetBrainsMonoFamily, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text("Raccourcis clavier :", fontWeight = FontWeight.Bold)
                Text("• CTRL + C : Interrompre la commande\n• CTRL + L : Effacer l'écran\n• TAB : Auto-complétion\n• Pincer l'écran : Zoomer la police\n• Appui long : Copier / Coller", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    )
}

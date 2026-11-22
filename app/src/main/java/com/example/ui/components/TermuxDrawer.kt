package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.shell.ShellSession
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TermuxBorder
import com.example.ui.theme.TermuxCyan
import com.example.ui.theme.TermuxEmerald
import com.example.ui.theme.TermuxSurface
import com.example.ui.theme.TermuxSurfaceElevated

@Composable
fun TermuxDrawerContent(
    sessions: List<ShellSession>,
    activeSessionId: String,
    installedCount: Int,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onCloseSession: (String) -> Unit,
    onOpenPackages: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onQuickRunCppDemo: () -> Unit,
    onQuickRunPythonDemo: () -> Unit
) {
    var renamingSession by remember { mutableStateOf<ShellSession?>(null) }
    var renameText by remember { mutableStateOf("") }

    ModalDrawerSheet(
        drawerContainerColor = TermuxSurface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(304.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Hero Drawer Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF0F1D17), TermuxSurface)
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(TermuxEmerald.copy(alpha = 0.16f))
                                .border(1.dp, TermuxEmerald.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ">_",
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                color = TermuxEmerald
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Termux++",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "$installedCount paquets • C++20 & Python 3",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = TermuxBorder)

            // Sessions header + New Session button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "SESSIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onNewSession,
                    modifier = Modifier.testTag("drawer_new_session")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Nouvelle session", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Nouvelle")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    val isActive = session.id == activeSessionId
                    val name by session.sessionName.collectAsState()
                    val cwd by session.cwdDisplay.collectAsState()
                    val running by session.isRunningCommand.collectAsState()

                    Surface(
                        color = if (isActive) TermuxEmerald.copy(alpha = 0.14f) else TermuxSurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = if (isActive) TermuxEmerald.copy(alpha = 0.55f) else TermuxBorder,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectSession(session.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (running) TermuxCyan else if (isActive) TermuxEmerald else Color.Gray)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = cwd,
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    renamingSession = session
                                    renameText = name
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Renommer", modifier = Modifier.size(16.dp))
                            }
                            if (sessions.size > 1) {
                                IconButton(
                                    onClick = { onCloseSession(session.id) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Fermer session", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = TermuxBorder)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "OUTILS & PAQUETS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                item {
                    DrawerActionItem(
                        icon = Icons.Default.Inventory2,
                        title = "Gestionnaire pkg",
                        subtitle = "Installer Python, C++, Git, Curl...",
                        accent = TermuxEmerald,
                        modifier = Modifier.testTag("drawer_packages"),
                        onClick = onOpenPackages
                    )
                }
                item {
                    DrawerActionItem(
                        icon = Icons.Default.FolderOpen,
                        title = "Fichiers \$HOME & SAF",
                        subtitle = "Parcourir, créer ou importer",
                        accent = TermuxCyan,
                        modifier = Modifier.testTag("drawer_files"),
                        onClick = onOpenFiles
                    )
                }
                item {
                    DrawerActionItem(
                        icon = Icons.Default.Code,
                        title = "Démo C++ (hello.cpp)",
                        subtitle = "Installe clang & compile ~/examples/hello.cpp",
                        accent = TermuxCyan,
                        modifier = Modifier.testTag("drawer_demo_cpp"),
                        onClick = onQuickRunCppDemo
                    )
                }
                item {
                    DrawerActionItem(
                        icon = Icons.Default.Terminal,
                        title = "Démo Python 3",
                        subtitle = "Installe python & exécute fibonacci.py",
                        accent = TermuxEmerald,
                        modifier = Modifier.testTag("drawer_demo_py"),
                        onClick = onQuickRunPythonDemo
                    )
                }
                item {
                    DrawerActionItem(
                        icon = Icons.Default.Settings,
                        title = "Apparence & Paramètres",
                        subtitle = "Thèmes, police, curseur, touches",
                        accent = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("drawer_settings"),
                        onClick = onOpenSettings
                    )
                }
                item {
                    DrawerActionItem(
                        icon = Icons.AutoMirrored.Filled.HelpOutline,
                        title = "Aide & Commandes",
                        subtitle = "Raccourcis clavier & syntaxe",
                        accent = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("drawer_help"),
                        onClick = onOpenHelp
                    )
                }
            }

            HorizontalDivider(color = TermuxBorder)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onToggleKeyboard,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TermuxSurfaceElevated,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.weight(1f).testTag("drawer_keyboard_btn")
                ) {
                    Icon(Icons.Default.Keyboard, contentDescription = "Clavier", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clavier")
                }
            }
        }
    }

    renamingSession?.let { target ->
        AlertDialog(
            onDismissRequest = { renamingSession = null },
            title = { Text("Renommer la session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Nom") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameSession(target.id, renameText)
                    renamingSession = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { renamingSession = null }) { Text("Annuler") }
            }
        )
    }
}

@Composable
private fun DrawerActionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

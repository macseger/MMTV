package com.example.mmtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mmtv.api.SessionManager

@Composable
fun SettingsScreen(sessionManager: SessionManager, viewModel: MediaViewModel, onLogout: () -> Unit) {
    val loginInfo = sessionManager.getLogin()
    val uiState = viewModel.uiState
    val firstButtonFocusRequester = remember { FocusRequester() }
    var currentBufferMs by remember { mutableIntStateOf(sessionManager.getBufferSize()) }

    LaunchedEffect(Unit) {
        firstButtonFocusRequester.requestFocus()
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 64.dp, bottom = 64.dp, start = 48.dp, end = 48.dp)
        ) {
            item {
                Text(
                    "INSTÄLLNINGAR",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraLight,
                        letterSpacing = 8.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(48.dp))
            }

            item { SectionHeader("Uppspelning") }

            item {
                val bufferOptions = listOf(1000, 3000, 5000, 10000, 15000)
                SettingsAction(
                    title = "Buffertstorlek",
                    subtitle = "Längre buffert minskar avbrott vid instabilt nät",
                    icon = Icons.Default.Timer,
                    value = "${currentBufferMs / 1000} sekunder",
                    modifier = Modifier.focusRequester(firstButtonFocusRequester),
                    onClick = {
                        val nextIndex = (bufferOptions.indexOf(currentBufferMs) + 1) % bufferOptions.size
                        currentBufferMs = bufferOptions[nextIndex]
                        sessionManager.setBufferSize(currentBufferMs)
                    }
                )
            }

            item { SectionHeader("Innehåll") }

            item {
                SettingsAction(
                    title = "Uppdatera bibliotek",
                    subtitle = "Hämta de senaste filmerna och kanalerna",
                    icon = Icons.Default.Refresh,
                    onClick = { viewModel.refreshDataManually() }
                )
            }

            item {
                SettingsAction(
                    title = "Visa alla kategorier",
                    subtitle = "Återställ dolda kanalgrupper",
                    icon = Icons.Default.Visibility,
                    onClick = { viewModel.showAllCategories() }
                )
            }

            item {
                SettingsAction(
                    title = "Ta bort favoriter",
                    subtitle = "Rensa alla dina sparade favoriter",
                    icon = Icons.Default.DeleteForever,
                    onClick = { viewModel.clearAllFavorites() }
                )
            }

            item {
                SettingsAction(
                    title = "Töm historiken",
                    subtitle = "Rensa listan över senast sedda",
                    icon = Icons.Default.History,
                    onClick = { viewModel.clearHistory() }
                )
            }

            item { SectionHeader("System") }

            item {
                val username = loginInfo?.second ?: "Okänd"
                SettingsAction(
                    title = "Logga ut $username",
                    subtitle = "Byt användare eller server (${loginInfo?.first ?: ""})",
                    icon = Icons.AutoMirrored.Filled.Logout,
                    isDestructive = true,
                    onClick = {
                        sessionManager.logout()
                        onLogout()
                    }
                )
            }
        }

        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(modifier = Modifier.padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(24.dp))
                        Text("Uppdaterar spellista...", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(top = 24.dp, bottom = 12.dp, start = 8.dp)
    )
}

@Composable
fun SettingsToggle(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    focusRequester: FocusRequester = FocusRequester(),
    onToggle: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    Surface(
        onClick = onToggle,
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { hasFocus = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp),
        color = if (hasFocus) Color.White.copy(alpha = 0.15f) else Color.Transparent,
        border = if (hasFocus) androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.5f)) else null,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = if (hasFocus) Color.White else Color.Gray, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            )
        }
    }
}

@Composable
fun SettingsAction(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String,
    icon: ImageVector,
    value: String? = null,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .onFocusChanged { hasFocus = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp),
        color = if (hasFocus) {
            if (isDestructive) Color.Red.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.15f)
        } else Color.Transparent,
        border = if (hasFocus) {
            androidx.compose.foundation.BorderStroke(2.dp, if (isDestructive) Color.Red.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.5f))
        } else null,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, 
                null, 
                tint = when {
                    isDestructive -> Color.Red
                    hasFocus -> Color.White
                    else -> Color.Gray
                }, 
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (isDestructive) Color.Red else Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            if (value != null) {
                Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            if (isDestructive) {
                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = Color.Red.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
            }
        }
    }
}

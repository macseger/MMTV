package com.example.mmtv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mmtv.api.SessionManager

@Composable
fun SettingsScreen(sessionManager: SessionManager, viewModel: MediaViewModel, onLogout: () -> Unit) {
    val loginInfo = sessionManager.getLogin()
    val firstButtonFocusRequester = remember { FocusRequester() }
    var autoPlay by remember { mutableStateOf(sessionManager.getAutoPlayNext()) }

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

            item { SectionHeader("Innehåll") }

            item {
                SettingsAction(
                    title = "Uppdatera bibliotek",
                    subtitle = "Hämta de senaste filmerna, kanalerna & picons",
                    icon = Icons.Default.Refresh,
                    modifier = Modifier.focusRequester(firstButtonFocusRequester),
                    onClick = { viewModel.refreshDataManually() }
                )
            }

            item {
                SettingsAction(
                    title = "Optimera biblioteket",
                    subtitle = "Hämta och synka rich-metadata (bilder/beskrivningar)",
                    icon = Icons.Default.AutoFixHigh,
                    onClick = { viewModel.performOptimization() }
                )
            }

            item {
                SettingsAction(
                    title = "Ta bort favoriter",
                    subtitle = "Rensa alla dina sparade favoriter",
                    icon = Icons.Default.DeleteForever,
                    isDestructive = true,
                    onClick = { viewModel.clearAllFavorites() }
                )
            }

            item {
                SettingsAction(
                    title = "Töm historiken",
                    subtitle = "Rensa listan över senast sedda",
                    icon = Icons.Default.History,
                    isDestructive = true,
                    onClick = { viewModel.clearHistory() }
                )
            }

            item { SectionHeader("Uppspelning") }

            item {
                SettingsAction(
                    title = "Spela nästa avsnitt automatiskt",
                    subtitle = "Föreslå och starta nästa avsnitt i serier",
                    icon = if (autoPlay) Icons.Default.PlayCircleFilled else Icons.Default.PlayCircleOutline,
                    value = if (autoPlay) "PÅ" else "AV",
                    onClick = {
                        autoPlay = !autoPlay
                        sessionManager.setAutoPlayNext(autoPlay)
                    }
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

            item { SectionHeader("Om appen") }

            item {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    color = Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Denna applikation är helt gratis, reklamfri och fri att använda för privat bruk.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Jag tar dock gärna emot donationer om du uppskattar mitt arbete.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Email, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("marcus.segerljung@gmail.com", style = MaterialTheme.typography.bodySmall, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Favorite, null, tint = Color.Red, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Swish: 0790-16 15 14", style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Vi har tagit bort den stora isLoading-boxen härifrån för att undvika dubbla meddelanden.
        // Status visas nu enhetligt via status-pillret uppe till höger som hanteras centralt.
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
    
    val animatedScale by animateFloatAsState(
        targetValue = if (hasFocus) 1.05f else 1f,
        label = "scale"
    )
    
    val animatedBgColor by animateColorAsState(
        targetValue = when {
            hasFocus && isDestructive -> Color.Red.copy(alpha = 0.25f)
            hasFocus -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            else -> Color.White.copy(alpha = 0.05f)
        },
        label = "bgColor"
    )

    val animatedBorderColor by animateColorAsState(
        targetValue = when {
            hasFocus && isDestructive -> Color.Red.copy(alpha = 0.8f)
            hasFocus -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            else -> Color.Transparent
        },
        label = "borderColor"
    )

    Surface(
        modifier = modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .onFocusChanged { hasFocus = it.isFocused }
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 4.dp),
        color = animatedBgColor,
        border = androidx.compose.foundation.BorderStroke(2.dp, animatedBorderColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, 
                null, 
                tint = when {
                    isDestructive -> Color.Red
                    hasFocus -> MaterialTheme.colorScheme.primary
                    else -> Color.Gray
                }, 
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title, 
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), 
                    color = if (isDestructive) Color.Red else Color.White
                )
                Text(
                    subtitle, 
                    style = MaterialTheme.typography.bodySmall, 
                    color = Color.Gray.copy(alpha = 0.8f)
                )
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

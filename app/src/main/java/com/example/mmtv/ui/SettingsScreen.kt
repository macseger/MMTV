package com.example.mmtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.mmtv.api.SessionManager

@Composable
fun SettingsScreen(sessionManager: SessionManager, viewModel: MediaViewModel, onLogout: () -> Unit) {
    val loginInfo = sessionManager.getLogin()
    val uiState = viewModel.uiState
    val firstButtonFocusRequester = remember { FocusRequester() }
    var tunnelingEnabled by remember { mutableStateOf(sessionManager.isTunnelingEnabled()) }

    LaunchedEffect(Unit) {
        firstButtonFocusRequester.requestFocus()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(24.dp)
        ) {
            item {
                Text("Inställningar", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(32.dp))
            }

            item {
                Card(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(0.6f)) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("Inloggad som:", style = MaterialTheme.typography.titleMedium)
                        Text(loginInfo?.second ?: "Okänd", style = MaterialTheme.typography.bodyLarge)
                        Text("Server: ${loginInfo?.first ?: "Okänd"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            item {
                var tunnelFocus by remember { mutableStateOf(false) }
                Surface(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(0.6f)
                        .wrapContentHeight()
                        .focusRequester(firstButtonFocusRequester)
                        .onFocusChanged { tunnelFocus = it.isFocused },
                    onClick = { 
                        tunnelingEnabled = !tunnelingEnabled
                        sessionManager.setTunnelingEnabled(tunnelingEnabled)
                    },
                    color = if (tunnelFocus) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Tunneluppspelning", style = MaterialTheme.typography.titleMedium)
                            Text("Kan fixa microlagg på vissa TV-modeller", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = tunnelingEnabled, onCheckedChange = null)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                var refreshFocus by remember { mutableStateOf(false) }
                Button(
                    onClick = { viewModel.refreshDataManually() },
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(0.6f)
                        .height(56.dp)
                        .onFocusChanged { refreshFocus = it.isFocused },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (refreshFocus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                ) {
                    Text("LADDA OM SPELLISTAN", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                var btnFocus by remember { mutableStateOf(false) }
                Button(
                    onClick = { viewModel.showAllCategories() },
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(0.6f)
                        .height(56.dp)
                        .onFocusChanged { btnFocus = it.isFocused },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (btnFocus) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
                    )
                ) {
                    Text("VISA DOLDA KATEGORIER", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                var logoutFocus by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        sessionManager.logout()
                        onLogout()
                    },
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth(0.6f)
                        .height(56.dp)
                        .onFocusChanged { logoutFocus = it.isFocused },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (logoutFocus) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                ) {
                    Text("LOGGA UT", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Uppdaterar spellista...", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

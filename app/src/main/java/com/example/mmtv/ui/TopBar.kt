package com.example.mmtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TopBar(
    onNavigate: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onLiveTvClick: () -> Unit = { onNavigate("live") },
    modifier: Modifier = Modifier,
    homeFocusRequester: FocusRequester? = null,
    liveTvFocusRequester: FocusRequester? = null
) {
    var focusedItem by remember { mutableStateOf<String?>(null) }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.9f),
                        Color.Black.copy(alpha = 0.5f),
                        Color.Transparent
                    )
                )
            )
            .padding(horizontal = 48.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Navigation Items
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // App Logo or Name
                Text(
                    text = "MMTV",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 2.sp
                    ),
                    modifier = Modifier.padding(end = 16.dp)
                )

                TopBarItem("SÖK", Icons.Default.Search, focusedItem == "search") {
                    focusedItem = "search"
                    onNavigate("search")
                }
                TopBarItem(
                    title = "HEM", 
                    icon = Icons.Default.Home, 
                    isFocusedManually = focusedItem == "home",
                    modifier = if (homeFocusRequester != null) Modifier.focusRequester(homeFocusRequester) else Modifier
                ) {
                    focusedItem = "home"
                    onNavigate("home")
                }
                TopBarItem(
                    title = "LIVE TV", 
                    icon = Icons.Default.LiveTv, 
                    isFocusedManually = focusedItem == "live",
                    modifier = if (liveTvFocusRequester != null) Modifier.focusRequester(liveTvFocusRequester) else Modifier
                ) {
                    focusedItem = "live"
                    onLiveTvClick()
                }
                TopBarItem("PPV TV", Icons.Default.Sports, focusedItem == "ppv") {
                    focusedItem = "ppv"
                    onNavigate("ppv")
                }
                TopBarItem("FILMER", Icons.Default.Movie, focusedItem == "movies") {
                    focusedItem = "movies"
                    onNavigate("movies")
                }
                TopBarItem("SERIER", Icons.Default.Tv, focusedItem == "series") {
                    focusedItem = "series"
                    onNavigate("series")
                }
            }

            // Settings/Profile on the right
            TopBarItem("INSTÄLLNINGAR", Icons.Default.Settings, focusedItem == "settings") {
                focusedItem = "settings"
                onNavigate("settings")
            }
        }
    }
}

@Composable
private fun TopBarItem(
    title: String,
    icon: ImageVector,
    isFocusedManually: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val contentColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f)

    Column(
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .scale(if (isFocused) 1.1f else 1.0f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            modifier = Modifier.size(24.dp),
            tint = contentColor
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                fontSize = 10.sp
            ),
            color = contentColor
        )
        
        // Indicator for active/focused state
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .width(20.dp)
                .height(2.dp)
                .background(if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent)
        )
    }
}

package com.example.mmtv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.delay
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mmtv.R

@Composable
fun SettingsScreen(
    username: String,
    host: String,
    autoPlayEnabled: Boolean,
    useExternalEpg: Boolean,
    useTunneling: Boolean,
    showPlaybackDetails: Boolean,
    onTogglePlaybackDetails: (Boolean) -> Unit,
    isTvMode: Boolean,
    isUpdating: Boolean,
    isSyncCategoryDialogOpen: Boolean,
    isContentLoading: Boolean,
    isCheckingForAppUpdate: Boolean,
    isAppUpToDate: Boolean,
    appUpdateVersion: String?,
    onCheckForUpdate: () -> Unit,
    onStartUpdate: () -> Unit,
    onLogout: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onSelectSyncCategories: () -> Unit,
    onOpenTvFavorites: () -> Unit = {},
    onRefreshTv: () -> Unit,
    onRefreshEpg: () -> Unit,
    onExtractPicons: () -> Unit,
    onOptimizeLibrary: () -> Unit,
    onClearFavorites: () -> Unit,
    onClearHistory: () -> Unit,
    onToggleAutoPlay: (Boolean) -> Unit,
    onToggleExternalEpg: (Boolean) -> Unit,
    onToggleTunneling: (Boolean) -> Unit,
    onToggleTvMode: (Boolean) -> Unit
) {
    val firstButtonFocusRequester = remember { FocusRequester() }
    val categoryFocusRequester = remember { FocusRequester() }
    val settingsListState = rememberLazyListState()
    var needsCategoryFocus by remember { mutableStateOf(false) }
    var restoringCategoryFocus by remember { mutableStateOf(false) }
    LaunchedEffect(isSyncCategoryDialogOpen, isContentLoading) {
        if (isSyncCategoryDialogOpen) {
            needsCategoryFocus = true
        } else if (needsCategoryFocus && !isContentLoading) {
            restoringCategoryFocus = true
            settingsListState.scrollToItem(0)
            withFrameNanos { }
            if (isTvMode) categoryFocusRequester.requestFocus()
            // Consume trailing/repeated remote events while the dialog window closes.
            delay(350)
            needsCategoryFocus = false
            restoringCategoryFocus = false
        }
    }
    
    var showConfirmDialog by remember { mutableStateOf(false) }
    var confirmTitle by remember { mutableIntStateOf(0) }
    var confirmMessage by remember { mutableIntStateOf(0) }
    var onConfirmAction by remember { mutableStateOf({}) }

    fun requestConfirm(titleRes: Int, messageRes: Int, action: () -> Unit) {
        confirmTitle = titleRes
        confirmMessage = messageRes
        onConfirmAction = action
        showConfirmDialog = true
    }

    LaunchedEffect(Unit) {
        if (isTvMode) {
            firstButtonFocusRequester.requestFocus()
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            val repeatedConfirm = key.repeatCount > 0 && key.keyCode in listOf(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            )
            isSyncCategoryDialogOpen || isContentLoading || needsCategoryFocus || restoringCategoryFocus || repeatedConfirm
        }
    ) {
        LazyColumn(
            state = settingsListState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 64.dp, bottom = 64.dp, start = 48.dp, end = 48.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraLight,
                        letterSpacing = 8.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(48.dp))
            }

            item { SectionHeader(stringResource(R.string.section_content)) }
            item {
                SettingsAction(
                    title = "Välj kategorier att synka",
                    modifier = Modifier.focusRequester(categoryFocusRequester),
                    subtitle = "Välj TV, filmer och serier. Bara dina val hämtas vid uppdatering.",
                    icon = Icons.Default.Checklist,
                    isLoading = isUpdating,
                    onClick = onSelectSyncCategories
                )
            }

            item {
                SettingsAction(
                    title = "Skapa TV Favoritlista",
                    subtitle = "Välj snabbt vilka TV-kanaler du vill ha som favoriter ur alla dina kanaler.",
                    icon = Icons.Default.Star,
                    onClick = onOpenTvFavorites
                )
            }

            item {
                SettingsAction(
                    title = stringResource(R.string.sync_only_live),
                    subtitle = stringResource(R.string.sync_only_live_sub),
                    icon = Icons.Default.LiveTv,
                    modifier = Modifier.focusRequester(firstButtonFocusRequester),
                    onClick = onRefreshTv
                )
            }

            item {
                SettingsAction(
                    title = stringResource(R.string.refresh_library),
                    subtitle = stringResource(R.string.refresh_library_sub),
                    icon = Icons.Default.Movie,
                    onClick = onRefreshLibrary
                )
            }

            item {
                SettingsAction(
                    title = stringResource(R.string.refresh_epg),
                    subtitle = stringResource(R.string.refresh_epg_sub),
                    icon = Icons.Default.DateRange,
                    onClick = onRefreshEpg
                )
            }

            item {
                SettingsAction(
                    title = stringResource(R.string.external_se_epg),
                    subtitle = stringResource(R.string.external_se_epg_sub),
                    icon = Icons.Default.Language,
                    value = if (useExternalEpg) stringResource(R.string.on) else stringResource(R.string.off),
                    onClick = { onToggleExternalEpg(!useExternalEpg) }
                )
            }

            item {
                SettingsAction(
                    title = "Använd lokala picons",
                    subtitle = "Extrahera ikoner från picons.zip i assets",
                    icon = Icons.Default.Image,
                    isLoading = isUpdating,
                    onClick = onExtractPicons
                )
            }

            item {
                SettingsAction(
                    title = stringResource(R.string.optimize_library),
                    subtitle = stringResource(R.string.optimize_library_sub),
                    icon = Icons.Default.AutoFixHigh,
                    isLoading = isUpdating,
                    onClick = onOptimizeLibrary
                )
            }

            item {
                SettingsAction(
                    title = stringResource(R.string.delete_favorites),
                    subtitle = stringResource(R.string.delete_favorites_sub),
                    icon = Icons.Default.DeleteForever,
                    isDestructive = true,
                    onClick = {
                        requestConfirm(
                            R.string.confirm_title,
                            R.string.confirm_delete_favorites,
                            onClearFavorites
                        )
                    }
                )
            }

            item {
                SettingsAction(
                    title = stringResource(R.string.clear_history),
                    subtitle = stringResource(R.string.clear_history_sub),
                    icon = Icons.Default.History,
                    isDestructive = true,
                    onClick = {
                        requestConfirm(
                            R.string.confirm_title,
                            R.string.confirm_clear_history,
                            onClearHistory
                        )
                    }
                )
            }

            item { SectionHeader(stringResource(R.string.section_playback)) }

            item {
                SettingsAction(
                    title = stringResource(R.string.autoplay_next),
                    subtitle = stringResource(R.string.autoplay_next_sub),
                    icon = if (autoPlayEnabled) Icons.Default.PlayCircleFilled else Icons.Default.PlayCircleOutline,
                    value = if (autoPlayEnabled) stringResource(R.string.on) else stringResource(R.string.off),
                    onClick = { onToggleAutoPlay(!autoPlayEnabled) }
                )
            }

            item {
                SettingsAction(
                    title = "Tunneluppspelning",
                    subtitle = "Direkt hårdvaruavkodning (hjälper vid lagg på vissa enheter)",
                    icon = Icons.Default.Bolt,
                    value = if (useTunneling) stringResource(R.string.on) else stringResource(R.string.off),
                    onClick = { onToggleTunneling(!useTunneling) }
                )
            }

            item {
                SettingsAction(
                    title = "Visa detaljerad videouppspelning",
                    subtitle = "Visa alltid kvalitet, FPS och nätverkshastighet. Annars visas rutan i 5 sekunder vid start.",
                    icon = Icons.Default.Info,
                    value = if (showPlaybackDetails) stringResource(R.string.on) else stringResource(R.string.off),
                    onClick = { onTogglePlaybackDetails(!showPlaybackDetails) }
                )
            }

            item { SectionHeader(stringResource(R.string.section_system)) }

            item {
                SettingsAction(
                    title = if (isTvMode) "TV-läge" else "Telefon-läge",
                    subtitle = if (isTvMode) "Anpassat för fjärrkontroll" else "Anpassat för pekskärm",
                    icon = if (isTvMode) Icons.Default.Tv else Icons.Default.Smartphone,
                    value = if (isTvMode) "TV" else "MOBIL",
                    onClick = { onToggleTvMode(!isTvMode) }
                )
            }

            item {
                val title = when {
                    appUpdateVersion != null -> "Uppdatering tillgänglig: $appUpdateVersion"
                    isAppUpToDate -> "Du har senaste versionen"
                    else -> "Sök efter uppdatering"
                }
                val subtitle = when {
                    appUpdateVersion != null -> "Klicka för att ladda ner och installera"
                    isAppUpToDate -> "Ingen nyare version hittades"
                    else -> "Kontrollera om det finns en ny version av MMTV"
                }
                
                SettingsAction(
                    title = title,
                    subtitle = subtitle,
                    icon = if (appUpdateVersion != null) Icons.Default.SystemUpdate else Icons.Default.Update,
                    isLoading = isCheckingForAppUpdate,
                    onClick = {
                        if (appUpdateVersion != null) onStartUpdate() else onCheckForUpdate()
                    }
                )
            }

            item {
                SettingsAction(
                    title = stringResource(R.string.logout_user, username),
                    subtitle = stringResource(R.string.logout_sub, host),
                    icon = Icons.AutoMirrored.Filled.Logout,
                    isDestructive = true,
                    onClick = {
                        requestConfirm(
                            R.string.confirm_title,
                            R.string.confirm_logout,
                            onLogout
                        )
                    }
                )
            }

            item { SectionHeader(stringResource(R.string.section_about)) }

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
                            stringResource(R.string.about_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.donation_text),
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
                            Text(stringResource(R.string.swish_prefix) + "0790-16 15 14", style = MaterialTheme.typography.bodySmall, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                title = { Text(stringResource(confirmTitle), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black) },
                text = { Text(stringResource(confirmMessage), color = Color.White, style = MaterialTheme.typography.bodyLarge) },
                containerColor = Color(0xFF1A1A1A),
                shape = RoundedCornerShape(16.dp),
                confirmButton = {
                    var isFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            onConfirmAction()
                            showConfirmDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isFocused) Color.Red else Color.Gray.copy(alpha = 0.2f),
                            contentColor = if (isFocused) Color.White else Color.LightGray
                        ),
                        modifier = Modifier.onFocusChanged { isFocused = it.isFocused }
                    ) {
                        Text(stringResource(R.string.confirm_yes), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    var isFocused by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { showConfirmDialog = false },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.Gray
                        ),
                        modifier = Modifier.onFocusChanged { isFocused = it.isFocused }
                    ) {
                        Text(stringResource(R.string.confirm_no), fontWeight = FontWeight.Bold)
                    }
                }
            )
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
fun SettingsAction(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String,
    icon: ImageVector,
    value: String? = null,
    isDestructive: Boolean = false,
    isLoading: Boolean = false,
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
        onClick = onClick,
        modifier = modifier
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .onFocusChanged { hasFocus = it.isFocused }
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .padding(vertical = 4.dp),
        color = animatedBgColor,
        border = androidx.compose.foundation.BorderStroke(2.dp, animatedBorderColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        icon, 
                        null, 
                        tint = when {
                            isDestructive -> Color.Red
                            hasFocus -> MaterialTheme.colorScheme.primary
                            else -> Color.Gray
                        }, 
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
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

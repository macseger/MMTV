package com.example.mmtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import com.example.mmtv.model.EpgListing
import com.example.mmtv.model.GroupedMedia
import com.example.mmtv.model.MediaSource
import com.example.mmtv.ui.components.EpgModal

@Composable
fun CatchUpScreen(
    viewModel: MediaViewModel,
    isTvMode: Boolean,
    onPlayArchive: (MediaSource, EpgListing, List<MediaSource>) -> Unit,
    onBackPressed: () -> Unit
) {
    var categories by remember { mutableStateOf<List<GroupedMedia>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedChannel by remember { mutableStateOf<MediaSource?>(null) }

    LaunchedEffect(Unit) {
        categories = viewModel.getAllLiveChannelsForFavorites()
            .filter { it.tvArchive == true }
            .groupBy { it.categoryId to it.categoryName }
            .map { (category, channels) ->
                GroupedMedia(
                    title = category.second ?: "ÖVRIGT",
                    items = channels,
                    categoryId = category.first
                )
            }
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            categories.isEmpty() -> {
                BackHandler(onBack = onBackPressed)
                Text(
                    text = "Inga catch-up-kanaler är tillgängliga.",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            else -> MediaListScreen(
                groupedList = categories,
                viewModel = viewModel,
                isLive = true,
                isTvMode = isTvMode,
                onMediaSelected = { selectedChannel = it },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                onBackPressed = onBackPressed,
                backNavigatesImmediately = true
            )
        }

        selectedChannel?.let { channel ->
            key(channel.id) {
                val epgListState = rememberLazyListState()
                val epgFocusRequester = remember { FocusRequester() }
                EpgModal(
                    media = channel,
                    viewModel = viewModel,
                    epgListState = epgListState,
                    epgFocusRequester = epgFocusRequester,
                    onClose = { selectedChannel = null },
                    onPlayArchive = { listing ->
                        onPlayArchive(channel, listing, categories.flatMap { it.items })
                    }
                )
            }
        }
    }
}

package com.example.mmtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.mmtv.api.ApiClient
import com.example.mmtv.api.SessionManager
import com.example.mmtv.database.MediaDatabase
import com.example.mmtv.model.MediaType
import com.example.mmtv.model.MediaSource
import com.example.mmtv.repository.MediaRepository
import com.example.mmtv.ui.*
import com.example.mmtv.ui.theme.MMTVTheme
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {

    override fun onStop() {
        super.onStop()
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MMTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val context = LocalContext.current
                    val sessionManager = remember { SessionManager(context) }
                    val database = remember { MediaDatabase.getDatabase(context) }
                    val navController = rememberNavController()
                    
                    val loginInfo = sessionManager.getLogin()
                    val startDest = if (loginInfo != null) "home" else "login"

                    var sharedViewModel: MediaViewModel? by remember { mutableStateOf(null) }
                    var isInitialLoading by remember { mutableStateOf(loginInfo != null) }

                    LaunchedEffect(loginInfo) {
                        if (loginInfo != null) {
                            val (host, user, pass) = loginInfo
                            val api = ApiClient.getClient(host)
                            val repository = MediaRepository(api, context, database)
                            sharedViewModel = MediaViewModel(repository, sessionManager, database).apply {
                                loadData(user, pass, host)
                            }
                            isInitialLoading = false
                        }
                    }

                    if (isInitialLoading) {
                        LoadingBox()
                    } else {
                        NavHost(navController = navController, startDestination = startDest) {
                            composable("login") {
                                LoginScreen { host, user, pass ->
                                    sessionManager.saveLogin(host, user, pass)
                                    val api = ApiClient.getClient(host)
                                    val repository = MediaRepository(api, context, database)
                                    sharedViewModel = MediaViewModel(repository, sessionManager, database).apply {
                                        loadData(user, pass, host)
                                    }
                                    navController.navigate("home") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            }
                            
                            composable("home") {
                                sharedViewModel?.let { vm ->
                                    HomeScreen(
                                        viewModel = vm,
                                        onNavigate = { dest -> navController.navigate(dest) },
                                        onMediaSelected = { media ->
                                            if (media.type == MediaType.LIVE) {
                                                playMedia(navController, media, sessionManager, vm, emptyList<MediaSource>())
                                            } else {
                                                vm.selectedMedia = media
                                                navController.navigate("details")
                                            }
                                        }
                                    )
                                }
                            }

                            composable("live") {
                                sharedViewModel?.let { vm ->
                                    MediaListScreen(
                                        groupedList = vm.uiState.liveStreamsGrouped,
                                        initialCategoryIndex = vm.lastLiveCategoryIndex,
                                        onCategoryChanged = { 
                                            vm.lastLiveCategoryIndex = it
                                            vm.prefetchEpgForCategory(it)
                                        },
                                        onHideCategory = { title -> vm.hideCategory("live", title) },
                                        onMediaSelected = { media -> 
                                            val currentPlaylist = vm.uiState.liveStreamsGrouped.getOrNull(vm.lastLiveCategoryIndex)?.items ?: emptyList()
                                            playMedia(navController, media, sessionManager, vm, currentPlaylist)
                                        },
                                        epgProvider = { id -> vm.getEpgForId(id) },
                                        nextEpgProvider = { id -> vm.getNextEpgForId(id) }
                                    )
                                }
                            }

                            composable("movies") {
                                sharedViewModel?.let { vm ->
                                    MediaListScreen(
                                        groupedList = vm.uiState.movies,
                                        initialCategoryIndex = vm.lastMovieCategoryIndex,
                                        onCategoryChanged = { vm.lastMovieCategoryIndex = it },
                                        onHideCategory = { title -> vm.hideCategory("movies", title) },
                                        onMediaSelected = { media ->
                                            vm.selectedMedia = media
                                            navController.navigate("details")
                                        }
                                    )
                                }
                            }

                            composable("series") {
                                sharedViewModel?.let { vm ->
                                    MediaListScreen(
                                        groupedList = vm.uiState.series,
                                        initialCategoryIndex = vm.lastSeriesCategoryIndex,
                                        onCategoryChanged = { vm.lastSeriesCategoryIndex = it },
                                        onHideCategory = { title -> vm.hideCategory("series", title) },
                                        onMediaSelected = { media ->
                                            vm.selectedMedia = media
                                            navController.navigate("details")
                                        }
                                    )
                                }
                            }

                            composable("details") {
                                sharedViewModel?.let { vm ->
                                    vm.selectedMedia?.let { media ->
                                        DetailsScreen(
                                            media = media,
                                            onPlayMovie = { m, resume ->
                                                vm.addToHistory(m)
                                                playMedia(navController, m, sessionManager, vm, emptyList<MediaSource>(), resume)
                                            },
                                            onPlayEpisode = { ep, resume ->
                                                vm.addToHistory(media)
                                                sessionManager.getLogin()?.let { login ->
                                                    val (host, user, pass) = login
                                                    val streamUrl = "${host}/series/${user}/${pass}/${ep.id}.${ep.containerExtension ?: "mp4"}"
                                                    val encodedUrl = URLEncoder.encode(streamUrl, StandardCharsets.UTF_8.toString())
                                                    
                                                    vm.selectedMedia = MediaSource(
                                                        id = ep.id?.toIntOrNull() ?: 0,
                                                        title = ep.title ?: "Avsnitt",
                                                        icon = media.icon,
                                                        type = MediaType.SERIES
                                                    )
                                                    
                                                    if (!resume) {
                                                        sessionManager.clearPlaybackPosition(ep.id ?: "0")
                                                    }
                                                    
                                                    navController.navigate("player/$encodedUrl")
                                                }
                                            },
                                            viewModel = vm
                                        )
                                    }
                                }
                            }

                            composable("settings") {
                                sharedViewModel?.let { vm ->
                                    SettingsScreen(sessionManager, vm) {
                                        sharedViewModel = null
                                        navController.navigate("login") {
                                            popUpTo("login") { inclusive = true }
                                        }
                                    }
                                }
                            }

                            composable("player/{url}") { backStackEntry ->
                                val url = backStackEntry.arguments?.getString("url") ?: ""
                                sharedViewModel?.let { vm ->
                                    PlayerScreen(
                                        url = url,
                                        media = vm.selectedMedia,
                                        playlist = vm.currentPlaylist,
                                        categories = vm.uiState.liveStreamsGrouped,
                                        onMediaSelected = { newMedia ->
                                            playMedia(navController, newMedia, sessionManager, vm, vm.currentPlaylist)
                                        },
                                        onCategorySelected = { index ->
                                            vm.lastLiveCategoryIndex = index
                                            vm.currentPlaylist = vm.uiState.liveStreamsGrouped.getOrNull(index)?.items ?: emptyList()
                                            vm.prefetchEpgForCategory(index)
                                        },
                                        onBackPressed = {
                                            if (vm.selectedMedia?.type == MediaType.LIVE) {
                                                navController.navigate("live") {
                                                    popUpTo("live") { inclusive = true }
                                                }
                                            } else {
                                                navController.popBackStack()
                                            }
                                        },
                                        viewModel = vm
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LoadingBox() {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }

    private fun playMedia(
        navController: NavHostController, 
        media: MediaSource, 
        sessionManager: SessionManager,
        viewModel: MediaViewModel,
        playlist: List<MediaSource>,
        resume: Boolean = false
    ) {
        viewModel.selectedMedia = media
        viewModel.currentPlaylist = playlist

        val login = sessionManager.getLogin() ?: return
        val (host, user, pass) = login
        val type = media.type.name.lowercase()
        val ext = media.extension ?: "ts"
        val streamUrl = if (media.type == MediaType.LIVE) {
            "$host/live/$user/$pass/${media.id}.ts"
        } else {
            "$host/$type/$user/$pass/${media.id}.$ext"
        }
        val encodedUrl = URLEncoder.encode(streamUrl, StandardCharsets.UTF_8.toString())
        
        if (!resume && media.type != MediaType.LIVE) {
            sessionManager.clearPlaybackPosition(media.id.toString())
        }

        navController.navigate("player/$encodedUrl") {
            launchSingleTop = true
        }
    }
}

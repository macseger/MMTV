package com.example.mmtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.mmtv.ui.*
import com.example.mmtv.ui.theme.MMTVTheme
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.work.*
import com.example.mmtv.repository.DataSyncWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
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

                    val sharedViewModel: MediaViewModel = viewModel(
                        factory = MediaViewModelFactory(
                            repository = MediaRepository(
                                ApiClient.getClient(loginInfo?.first ?: "http://localhost"), 
                                context, 
                                database
                            ),
                            sessionManager = sessionManager,
                            database = database
                        )
                    )

                    var isProvisioning by remember { mutableStateOf(false) }
                    var provisioningStatus by remember { mutableStateOf("") }

                    // Keep splash screen on until data is loaded
                    splashScreen.setKeepOnScreenCondition {
                        loginInfo != null && sharedViewModel.uiState.liveStreamsGrouped.isEmpty() && sharedViewModel.uiState.isLoading && !isProvisioning
                    }

                    LaunchedEffect(loginInfo) {
                        if (loginInfo != null) {
                            val (h, u, p) = loginInfo
                            sharedViewModel.updateRepository(MediaRepository(ApiClient.getClient(h), context, database))
                            if (sharedViewModel.uiState.liveStreamsGrouped.isEmpty()) {
                                sharedViewModel.loadData(u, p, h)
                            }
                            scheduleDataSync(context)
                        }
                    }

                    if (loginInfo != null && sharedViewModel.uiState.liveStreamsGrouped.isEmpty()) {
                        // Empty black screen while splash is showing
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                    } else {
                        val navBackStackEntry by navController.currentBackStackEntryAsState()
                        val currentRoute = navBackStackEntry?.destination?.route
                        val showTopBar = currentRoute != "login" && currentRoute != "player/{url}" && currentRoute != "details"

                        val topBarHomeFocusRequester = remember { FocusRequester() }
                        val topBarLiveFocusRequester = remember { FocusRequester() }
                        
                        LaunchedEffect(Unit) {
                            if (startDest == "home") {
                                topBarHomeFocusRequester.requestFocus()
                            }
                        }

                        var showExitDialog by remember { mutableStateOf(false) }

                        if (showExitDialog) {
                            AlertDialog(
                                onDismissRequest = { showExitDialog = false },
                                title = { Text("AVSLUTA MMTV", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black) },
                                text = { Text("Vill du verkligen avsluta?", color = Color.White, style = MaterialTheme.typography.bodyLarge) },
                                containerColor = Color(0xFF1A1A1A),
                                shape = RoundedCornerShape(16.dp),
                                confirmButton = {
                                    var isExitFocused by remember { mutableStateOf(false) }
                                    Button(
                                        onClick = { (context as? android.app.Activity)?.finish() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isExitFocused) Color.Red else Color.Gray.copy(alpha = 0.2f),
                                            contentColor = if (isExitFocused) Color.White else Color.LightGray
                                        ),
                                        modifier = Modifier.onFocusChanged { state -> isExitFocused = state.isFocused }
                                    ) {
                                        Text("AVSLUTA", fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    var isCancelFocused by remember { mutableStateOf(false) }
                                    TextButton(
                                        onClick = { showExitDialog = false },
                                        modifier = Modifier.onFocusChanged { state -> isCancelFocused = state.isFocused },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = if (isCancelFocused) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                    ) {
                                        Text("AVBRYT", fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                        }

                        Scaffold(
                            modifier = Modifier.onKeyEvent { 
                                if (it.key == Key.Back && it.type == KeyEventType.KeyDown) {
                                    if (currentRoute == "home" || currentRoute == "login") {
                                        showExitDialog = true
                                        true
                                    } else false
                                } else false
                            },
                            topBar = {
                                if (showTopBar) {
                                    TopBar(
                                        onNavigate = { dest -> 
                                            navController.navigate(dest) {
                                                popUpTo("home") { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        onLiveTvClick = {
                                            val lastLive = sharedViewModel.uiState.history.find { it.type == MediaType.LIVE }
                                            if (lastLive != null) {
                                                sharedViewModel.setLiveCategoryByMediaId(lastLive.id)
                                                val currentPlaylist = sharedViewModel.uiState.liveStreamsGrouped.getOrNull(sharedViewModel.lastLiveCategoryIndex)?.items ?: emptyList()
                                                playMedia(navController, lastLive, sessionManager, sharedViewModel, currentPlaylist)
                                            } else {
                                                navController.navigate("live") {
                                                    popUpTo("home") { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        searchQuery = sharedViewModel.searchQuery,
                                        onSearchQueryChange = { sharedViewModel.searchQuery = it },
                                        homeFocusRequester = topBarHomeFocusRequester,
                                        liveTvFocusRequester = topBarLiveFocusRequester
                                    )
                                }
                            },
                            containerColor = Color.Black
                        ) { paddingValues ->
                            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                                NavHost(navController = navController, startDestination = startDest) {
                                    // ... composables ...
                                    composable("login") {
                                        LoginScreen(
                                            viewModel = sharedViewModel,
                                            onLogin = { h, u, p ->
                                                sharedViewModel.updateRepository(MediaRepository(ApiClient.getClient(h), context, database))
                                                
                                                lifecycleScope.launch {
                                                    // 1. Kontrollera inloggningen först
                                                    val loginSuccess = sharedViewModel.login(h, u, p)
                                                    if (!loginSuccess) return@launch

                                                    // 2. Visa den snygga laddskärmen medan vi hämtar grunddata
                                                    isProvisioning = true
                                                    provisioningStatus = "Hämtar kategorier..."
                                                    
                                                    sharedViewModel.loadData(u, p, h, forceRefresh = true) { success ->
                                                        // EPG laddas i bakgrunden i loadData, men vi väntar på kategorierna för att släppa in användaren
                                                        isProvisioning = false
                                                        if (success) {
                                                            navController.navigate("home") {
                                                                popUpTo("login") { inclusive = true }
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            isProvisioning = isProvisioning,
                                            provisioningStatus = provisioningStatus
                                        )
                                    }
                                    
                                    composable("home") {
                                        HomeScreen(
                                            viewModel = sharedViewModel,
                                            onNavigate = { dest -> navController.navigate(dest) },
                                            onMediaSelected = { media ->
                                                sharedViewModel.addToHistory(media)
                                                if (media.type == MediaType.LIVE) {
                                                    // Hitta kanalens original-kategori och sätt den som aktiv
                                                    sharedViewModel.setLiveCategoryByMediaId(media.id)

                                                    val currentPlaylist = sharedViewModel.uiState.liveStreamsGrouped.getOrNull(sharedViewModel.lastLiveCategoryIndex)?.items ?: emptyList()
                                                    playMedia(navController, media, sessionManager, sharedViewModel, currentPlaylist)
                                                } else {
                                                    sharedViewModel.selectedMedia = media
                                                    navController.navigate("details")
                                                }
                                            },
                                            topBarFocusRequester = topBarHomeFocusRequester
                                        )
                                    }

                                    composable("live") {
                                        MediaListScreen(
                                            groupedList = sharedViewModel.uiState.liveStreamsGrouped,
                                            initialCategoryIndex = sharedViewModel.lastLiveCategoryIndex,
                                            initialMediaId = sharedViewModel.selectedMedia?.id,
                                            onCategoryChanged = { index ->
                                                sharedViewModel.lastLiveCategoryIndex = index
                                                val category = sharedViewModel.uiState.liveStreamsGrouped.getOrNull(index)
                                                if (category?.items?.isEmpty() == true) {
                                                    sharedViewModel.loadItemsForCategory(MediaType.LIVE, category.categoryId)
                                                }
                                                sharedViewModel.prefetchEpgForCategory(index)
                                            },
                                            onToggleFavorite = { sharedViewModel.toggleFavorite(it) },
                                            onMediaSelected = { media -> 
                                                sharedViewModel.addToHistory(media)
                                                val currentPlaylist = sharedViewModel.uiState.liveStreamsGrouped.getOrNull(sharedViewModel.lastLiveCategoryIndex)?.items ?: emptyList()
                                                playMedia(navController, media, sessionManager, sharedViewModel, currentPlaylist)
                                            },
                                            epgProvider = { id, name -> sharedViewModel.getEpgForId(id, name) },
                                            nextEpgProvider = { id, name -> sharedViewModel.getNextEpgForId(id, name) },
                                            onGetIcon = { id, name -> sharedViewModel.getIconForChannel(id, name) },
                                            onBackPressed = { navController.popBackStack() },
                                            topBarFocusRequester = topBarHomeFocusRequester
                                        )
                                    }

                                    composable("movies") {
                                        // Om vi är på index 0 (Historik) och det är tomt, hoppa till index 1
                                        val initialIndex = if (sharedViewModel.lastMovieCategoryIndex == 0 && 
                                            sharedViewModel.uiState.movies.firstOrNull()?.items?.isEmpty() == true) 1 else sharedViewModel.lastMovieCategoryIndex
                                            
                                        MediaListScreen(
                                            groupedList = sharedViewModel.uiState.movies,
                                            initialCategoryIndex = initialIndex,
                                            onCategoryChanged = { index -> 
                                                sharedViewModel.lastMovieCategoryIndex = index 
                                                val category = sharedViewModel.uiState.movies.getOrNull(index)
                                                if (category?.items?.isEmpty() == true) {
                                                    sharedViewModel.loadItemsForCategory(MediaType.MOVIE, category.categoryId)
                                                }
                                            },
                                            onToggleFavorite = { sharedViewModel.toggleFavorite(it) },
                                            onMediaSelected = { media ->
                                                sharedViewModel.selectedMedia = media
                                                navController.navigate("details")
                                            },
                                            onGetIcon = { id, name -> sharedViewModel.getIconForChannel(id, name) },
                                            onBackPressed = { navController.popBackStack() },
                                            topBarFocusRequester = topBarHomeFocusRequester
                                        )
                                    }

                                    composable("series") {
                                        // Om vi är på index 0 (Historik) och det är tomt, hoppa till index 1
                                        val initialIndex = if (sharedViewModel.lastSeriesCategoryIndex == 0 && 
                                            sharedViewModel.uiState.series.firstOrNull()?.items?.isEmpty() == true) 1 else sharedViewModel.lastSeriesCategoryIndex

                                        MediaListScreen(
                                            groupedList = sharedViewModel.uiState.series,
                                            initialCategoryIndex = initialIndex,
                                            onCategoryChanged = { index -> 
                                                sharedViewModel.lastSeriesCategoryIndex = index 
                                                val category = sharedViewModel.uiState.series.getOrNull(index)
                                                if (category?.items?.isEmpty() == true) {
                                                    sharedViewModel.loadItemsForCategory(MediaType.SERIES, category.categoryId)
                                                }
                                            },
                                            onToggleFavorite = { sharedViewModel.toggleFavorite(it) },
                                            onMediaSelected = { media ->
                                                sharedViewModel.selectedMedia = media
                                                navController.navigate("details")
                                            },
                                            onGetIcon = { id, name -> sharedViewModel.getIconForChannel(id, name) },
                                            onBackPressed = { navController.popBackStack() },
                                            topBarFocusRequester = topBarHomeFocusRequester
                                        )
                                    }

                                    composable("details") {
                                        sharedViewModel.selectedMedia?.let { media ->
                                            DetailsScreen(
                                                media = media,
                                                onPlayMovie = { m, resume ->
                                                    sharedViewModel.addToHistory(m)
                                                    playMedia(navController, m, sessionManager, sharedViewModel, emptyList(), resume)
                                                },
                                                onPlayEpisode = { ep, resume ->
                                                    sharedViewModel.addToHistory(media, ep)
                                                    sessionManager.getLogin()?.let { login ->
                                                        val (h, u, p) = login
                                                        val streamUrl = "${h}/series/${u}/${p}/${ep.id}.${ep.containerExtension ?: "mp4"}"
                                                        val encodedUrl = URLEncoder.encode(streamUrl, StandardCharsets.UTF_8.toString())
                                                        
                                                        // VIKTIGT: Vi sparar vilket avsnitt som spelas i ViewModel
                                                        // men vi låter selectedMedia vara kvar som serien så att
                                                        // DetailsScreen inte tappar bort sig vid bakåtnavigering.
                                                        sharedViewModel.playingEpisode = ep
                                                        
                                                        if (!resume) {
                                                            sessionManager.clearPlaybackPosition(ep.id ?: "0")
                                                        }
                                                        
                                                        navController.navigate("player/$encodedUrl")
                                                    }
                                                },
                                                onToggleFavorite = { sharedViewModel.toggleFavorite(it) },
                                                viewModel = sharedViewModel
                                            )
                                        }
                                    }

                                    composable("settings") {
                                        val loginInfo = sessionManager.getLogin()
                                        var autoPlayEnabled by remember { mutableStateOf(sessionManager.getAutoPlayNext()) }
                                        
                                        SettingsScreen(
                                            username = loginInfo?.second ?: "Okänd",
                                            host = loginInfo?.first ?: "",
                                            autoPlayEnabled = autoPlayEnabled,
                                            isUpdating = sharedViewModel.isUpdatingBackground,
                                            onLogout = {
                                                sharedViewModel.logout()
                                                navController.navigate("login") {
                                                    popUpTo("login") { inclusive = true }
                                                }
                                            },
                                            onRefreshLibrary = { sharedViewModel.refreshDataManually() },
                                            onOptimizeLibrary = { sharedViewModel.performOptimization() },
                                            onClearFavorites = { sharedViewModel.clearAllFavorites() },
                                            onClearHistory = { sharedViewModel.clearHistory() },
                                            onToggleAutoPlay = { enabled ->
                                                autoPlayEnabled = enabled
                                                sessionManager.setAutoPlayNext(enabled)
                                            }
                                        )
                                    }
                                    
                                    composable("search") {
                                        SearchScreen(
                                            viewModel = sharedViewModel,
                                            onMediaSelected = { media ->
                                                sharedViewModel.addToHistory(media)
                                                if (media.type == MediaType.LIVE) {
                                                    sharedViewModel.setLiveCategoryByMediaId(media.id)
                                                    val currentPlaylist = sharedViewModel.uiState.liveStreamsGrouped.getOrNull(sharedViewModel.lastLiveCategoryIndex)?.items ?: emptyList()
                                                    playMedia(navController, media, sessionManager, sharedViewModel, currentPlaylist)
                                                } else {
                                                    sharedViewModel.selectedMedia = media
                                                    navController.navigate("details")
                                                }
                                            }
                                        )
                                    }

                                    composable("player/{url}") { backStackEntry ->
                                        val url = backStackEntry.arguments?.getString("url") ?: ""
                                        PlayerScreen(
                                            url = url,
                                            media = sharedViewModel.selectedMedia,
                                            playlist = sharedViewModel.currentPlaylist,
                                            categories = sharedViewModel.uiState.liveStreamsGrouped,
                                            onMediaSelected = { newMedia ->
                                                sharedViewModel.addToHistory(newMedia)
                                                playMedia(navController, newMedia, sessionManager, sharedViewModel, sharedViewModel.currentPlaylist)
                                            },
                                            onCategorySelected = { index ->
                                                sharedViewModel.lastLiveCategoryIndex = index
                                                sharedViewModel.currentPlaylist = sharedViewModel.uiState.liveStreamsGrouped.getOrNull(index)?.items ?: emptyList()
                                                sharedViewModel.prefetchEpgForCategory(index)
                                            },
                                            onBackPressed = {
                                                navController.popBackStack()
                                            },
                                            onPlayNextEpisode = { ep ->
                                                sessionManager.getLogin()?.let { login ->
                                                    val (h, u, p) = login
                                                    val streamUrl = "${h}/series/${u}/${p}/${ep.id}.${ep.containerExtension ?: "mp4"}"
                                                    val encodedUrl = URLEncoder.encode(streamUrl, StandardCharsets.UTF_8.toString())
                                                    
                                                    // Behåll selectedMedia (Serien), men uppdatera spelade avsnittet
                                                    sharedViewModel.playingEpisode = ep
                                                    
                                                    sessionManager.clearPlaybackPosition(ep.id ?: "0")
                                                    
                                                    navController.navigate("player/$encodedUrl") {
                                                        popUpTo("player/{url}") { inclusive = true }
                                                    }
                                                }
                                            },
                                            viewModel = sharedViewModel
                                        )
                                    }
                                }
                                
                                // Overlay for background updates
                                AnimatedVisibility(
                                    visible = sharedViewModel.updateStatus != null,
                                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(16.dp)
                                ) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = sharedViewModel.updateStatus ?: "",
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scheduleDataSync(context: android.content.Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<DataSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "MMTVDataSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
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
            // Om vi redan är i spelaren, rensa bort den förra så vi inte staplar kanaler på varandra
            if (navController.currentDestination?.route?.startsWith("player/") == true) {
                popUpTo(navController.currentDestination?.route!!) { inclusive = true }
            }
            launchSingleTop = true
        }
    }
}

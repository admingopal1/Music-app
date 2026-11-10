package com.app.musicplayer

import android.Manifest
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.musicplayer.ui.PlayerViewModel
import com.app.musicplayer.ui.screens.EqualizerScreen
import com.app.musicplayer.ui.screens.AssistantScreen
import com.app.musicplayer.ui.screens.HomeScreen
import com.app.musicplayer.ui.screens.LibraryScreen
import com.app.musicplayer.ui.screens.PlayerScreen
import com.app.musicplayer.ui.screens.MiniPlayer
import com.app.musicplayer.ui.screens.PlaylistsScreen
import com.app.musicplayer.ui.screens.VideosScreen
import com.app.musicplayer.ui.screens.ShareScreen
import com.app.musicplayer.ui.theme.MusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent { FirebaseAuthGate { AppRoot() } }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

@Composable
private fun AppRoot(vm: PlayerViewModel = viewModel()) {
    val accent by vm.accent.collectAsStateWithLifecycle()
    val amoled by vm.amoled.collectAsStateWithLifecycle()

    val permissions = rememberPermissions()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.loadLibrary()
        vm.loadVideos()
    }
    LaunchedEffect(Unit) { launcher.launch(permissions) }

    val tabs = listOf(
        Tab("Home", Icons.Rounded.Home),
        Tab("Library", Icons.Rounded.LibraryMusic),
        Tab("Player", Icons.Rounded.PlayCircle),
        Tab("EQ", Icons.Rounded.GraphicEq),
        Tab("Playlists", Icons.AutoMirrored.Rounded.QueueMusic),
        Tab("Videos", Icons.Rounded.VideoLibrary),
        Tab("AI", Icons.Rounded.AutoAwesome),
        Tab("Share", Icons.Rounded.Share),
    )
    var tab by rememberSaveable { mutableIntStateOf(0) }

    MusicTheme(accent = accent, amoled = amoled) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (tab != 2) MiniPlayer(vm) { tab = 2 }
                    NavigationBar(
                        containerColor = Color(0xFF171329).copy(alpha = 0.98f),
                        tonalElevation = 10.dp,
                        windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                    ) {
                        tabs.forEachIndexed { i, t ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(t.icon, t.label) },
                                label = { Text(t.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = Color(0xFFB77BFF).copy(alpha = 0.22f),
                                    selectedIconColor = Color(0xFFE4B8FF),
                                    selectedTextColor = Color(0xFFE4B8FF),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            },
        ) { inner ->
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF21133E), Color(0xFF17152F), Color(0xFF080810)))
                ).padding(inner).statusBarsPadding()
            ) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                    label = "tabs",
                ) { t ->
                    when (t) {
                        0 -> HomeScreen(
                            vm,
                            onOpenPlayer = { tab = 2 },
                            onOpenLibrary = { tab = 1 },
                            onOpenVideos = { tab = 5 },
                        )
                        1 -> LibraryScreen(vm, onOpenPlayer = { tab = 2 })
                        2 -> PlayerScreen(vm)
                        3 -> EqualizerScreen(vm.fx)
                        4 -> PlaylistsScreen(vm, onOpenPlayer = { tab = 2 })
                        5 -> VideosScreen(vm)
                        6 -> AssistantScreen(vm, onOpenPlayer = { tab = 2 })
                        else -> ShareScreen(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberPermissions(): Array<String> = androidx.compose.runtime.remember {
    if (Build.VERSION.SDK_INT >= 33)
        arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

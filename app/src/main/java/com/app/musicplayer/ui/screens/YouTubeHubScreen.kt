package com.app.musicplayer.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeHubScreen() {
    val context = LocalContext.current
    var searchText by rememberSaveable { mutableStateOf("") }
    var currentUrl by rememberSaveable { mutableStateOf("https://m.youtube.com") }
    var pageTitle by remember { mutableStateOf("YouTube") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun loadSearch() {
        val q = searchText.trim()
        if (q.isNotEmpty()) {
            currentUrl = "https://m.youtube.com/results?search_query=" + android.net.Uri.encode(q)
            webView?.loadUrl(currentUrl)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("YouTube", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("Search any video on YouTube") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    )
                    Button(onClick = ::loadSearch) { Text("Search") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(onClick = { if (webView?.canGoForward() == true) webView?.goForward() }) {
                        Icon(Icons.Rounded.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = {
                        currentUrl = "https://m.youtube.com"
                        webView?.loadUrl(currentUrl)
                    }) {
                        Icon(Icons.Rounded.Home, contentDescription = "YouTube home")
                    }
                    Text(
                        pageTitle,
                        modifier = Modifier.weight(1f).padding(top = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize().weight(1f),
            factory = {
                WebView(it).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.loadsImagesAutomatically = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.userAgentString = WebSettings.getDefaultUserAgent(it)
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            currentUrl = url
                            pageTitle = view.title?.take(36) ?: "YouTube"
                            val decoded = android.net.Uri.decode(url)
                            if (url.contains("/results?") && searchText.isBlank()) {
                                searchText = android.net.Uri.parse(url).getQueryParameter("search_query").orEmpty()
                            }
                        }
                    }
                    webView = this
                    loadUrl(currentUrl)
                }
            },
            update = { view -> if (webView !== view) webView = view },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}

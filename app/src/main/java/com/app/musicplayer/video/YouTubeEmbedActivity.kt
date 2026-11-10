package com.app.musicplayer.video

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class YouTubeEmbedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        if (videoId == null) {
            finish()
            return
        }
        setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        loadDataWithBaseURL(
                            "https://www.youtube.com",
                            html(videoId),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                }
            )
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    private fun html(id: String) = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;width:100%;height:100%;overflow:hidden}
        .frame{position:absolute;inset:0;display:flex;align-items:center}
        iframe{width:100%;aspect-ratio:16/9;border:0}</style></head>
        <body><div class="frame"><iframe
        src="https://www.youtube.com/embed/$id?playsinline=1&rel=0"
        title="YouTube video player" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
        referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe></div></body></html>
    """.trimIndent()

    companion object {
        private const val EXTRA_VIDEO_ID = "youtube_video_id"
        fun intent(context: Context, videoId: String): Intent =
            Intent(context, YouTubeEmbedActivity::class.java).putExtra(EXTRA_VIDEO_ID, videoId)
    }
}

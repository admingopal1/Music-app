package com.app.musicplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.app.musicplayer.MainActivity
import com.app.musicplayer.R
import com.app.musicplayer.ui.theme.ACCENT_CHOICES
import java.io.File
import java.io.FileOutputStream

object PlayerWidgets {
    const val ACTION_PLAY_PAUSE = "com.app.musicplayer.widget.PLAY_PAUSE"
    const val ACTION_NEXT = "com.app.musicplayer.widget.NEXT"
    const val ACTION_PREV = "com.app.musicplayer.widget.PREV"
    private const val PREFS = "widget_state"
    private const val ART_FILE = "widget_art.jpg"

    /** Remembers what to show, so widgets stay correct after reboot / process death. */
    fun save(ctx: Context, title: String, artist: String, playing: Boolean, art: Bitmap?, replaceArt: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", title).putString("artist", artist).putBoolean("playing", playing).apply()
        if (replaceArt) {
            val f = File(ctx.filesDir, ART_FILE)
            if (art != null) {
                runCatching { FileOutputStream(f).use { art.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            } else {
                f.delete()
            }
        }
    }

    fun refreshAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val targets = listOf(
            SmallWidget::class.java to R.layout.widget_player_small,
            LargeWidget::class.java to R.layout.widget_player_large,
        )
        for ((cls, layout) in targets) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx, layout))
        }
    }

    fun build(ctx: Context, layout: Int): RemoteViews {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = p.getString("title", "").orEmpty().ifBlank { "Not playing" }
        val artist = p.getString("artist", "").orEmpty().ifBlank { "Tap to open the player" }
        val playing = p.getBoolean("playing", false)

        val rv = RemoteViews(ctx.packageName, layout)
        rv.setTextViewText(R.id.w_title, title)
        rv.setTextViewText(R.id.w_artist, artist)
        rv.setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)

        val art = File(ctx.filesDir, ART_FILE).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        if (art != null) rv.setImageViewBitmap(R.id.w_art, art) else rv.setImageViewResource(R.id.w_art, R.drawable.ic_w_note)

        val idx = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("accent_idx", 0)
            .coerceIn(0, ACCENT_CHOICES.lastIndex)
        rv.setInt(R.id.w_play, "setColorFilter", ACCENT_CHOICES[idx].toArgb())

        rv.setOnClickPendingIntent(R.id.w_prev, action(ctx, ACTION_PREV, 1))
        rv.setOnClickPendingIntent(R.id.w_play, action(ctx, ACTION_PLAY_PAUSE, 2))
        rv.setOnClickPendingIntent(R.id.w_next, action(ctx, ACTION_NEXT, 3))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        rv.setOnClickPendingIntent(R.id.w_root, open)
        return rv
    }

    private fun action(ctx: Context, act: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, WidgetActionReceiver::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

abstract class BasePlayerWidget(private val layout: Int) : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, PlayerWidgets.build(context, layout))
    }
}

/** 4x1 widget. */
class SmallWidget : BasePlayerWidget(R.layout.widget_player_small)

/** 4x2 widget with bigger artwork. */
class LargeWidget : BasePlayerWidget(R.layout.widget_player_large)

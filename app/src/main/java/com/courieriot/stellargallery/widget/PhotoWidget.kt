package com.courieriot.stellargallery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Size
import android.view.View
import android.widget.RemoteViews
import com.courieriot.stellargallery.MainActivity
import com.courieriot.stellargallery.R
import com.courieriot.stellargallery.data.MediaRepository
import com.courieriot.stellargallery.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Home screen photo frame. Shows a random favorite (or any photo) and changes every ~30 minutes or on tap. */
class PhotoWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_NEXT) {
            val manager = AppWidgetManager.getInstance(context)
            refresh(context, manager, manager.getAppWidgetIds(ComponentName(context, PhotoWidget::class.java)))
        }
    }

    private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    val app = context.applicationContext
                    val repo = MediaRepository(app)
                    ids.forEach { id ->
                        val uri = runCatching { repo.randomPhoto(Prefs.get(app).widgetFavoritesOnly) }.getOrNull()
                        val bmp: Bitmap? = uri?.let { u ->
                            runCatching { app.contentResolver.loadThumbnail(u, Size(720, 720), null) }.getOrNull()
                        }
                        manager.updateAppWidget(id, buildViews(app, bmp))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun buildViews(context: Context, bmp: Bitmap?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_photo)
        if (bmp != null) {
            views.setImageViewBitmap(R.id.widget_image, bmp)
            views.setViewVisibility(R.id.widget_empty, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_image, open)
        views.setOnClickPendingIntent(R.id.widget_empty, open)
        val next = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, PhotoWidget::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_next, next)
        return views
    }

    companion object {
        private const val ACTION_NEXT = "com.courieriot.stellargallery.widget.NEXT"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Ask all widgets to pick a new photo (e.g. after settings change). */
        fun requestUpdate(context: Context) {
            context.sendBroadcast(Intent(context, PhotoWidget::class.java).setAction(ACTION_NEXT))
        }
    }
}

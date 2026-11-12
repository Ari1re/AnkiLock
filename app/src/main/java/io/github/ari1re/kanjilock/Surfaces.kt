package io.github.ari1re.kanjilock

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Home screen + lock screen (Android 16 "widgets on lock screen" / tablets) widget. */
class KanjiWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val e = Cards.current(context)
        val views = CardViews.widget(context, e, Prefs(context).romajiAll)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    override fun onEnabled(context: Context) {
        Scheduler.ensure(context)
    }

    companion object {
        fun updateAll(ctx: Context, e: Entry) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, KanjiWidget::class.java))
            if (ids.isEmpty()) return
            val views = CardViews.widget(ctx, e, Prefs(ctx).romajiAll)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }
    }
}

/** A silent, persistent notification styled as a card — shows on every phone's lock screen. */
object LockCard {
    private const val CHANNEL = "lock_card"
    private const val ID = 1

    fun canPost(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        // DEFAULT (not LOW) importance: many phones hide "silent" notifications on the lock screen.
        // No sound or vibration, and we only alert once.
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.channel_desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, e: Entry) {
        val p = Prefs(ctx)
        if (!p.lockNotification) { cancel(ctx); return }
        if (!canPost(ctx)) return
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(CardViews.ja(e.text))
            .setContentText(e.meaning)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(CardViews.notifSmall(ctx, e))
            .setCustomBigContentView(CardViews.notifBig(ctx, e, p.romajiAll))
            .setContentIntent(CardViews.openApp(ctx))
            .addAction(R.drawable.ic_next, ctx.getString(R.string.next), CardViews.nextIntent(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(ctx).notify(ID, n)
    }

    fun cancel(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID)
}

class NextReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CardViews.ACTION_NEXT) Cards.advance(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.ensure(context)
        Cards.refresh(context)
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Cards.advance(applicationContext)
        return Result.success()
    }
}

object Scheduler {
    private const val WORK = "rotate_card"

    fun ensure(ctx: Context, replace: Boolean = false) {
        val minutes = Prefs(ctx).intervalMinutes.toLong().coerceAtLeast(15)
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(minutes, TimeUnit.MINUTES)
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK,
            if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }
}

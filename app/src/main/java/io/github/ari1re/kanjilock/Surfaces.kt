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

    const val NOTIF_ID = ID

    /** The card notification (also used as the unlock service's foreground notification). */
    fun build(ctx: Context, e: Entry): android.app.Notification {
        ensureChannel(ctx)
        val p = Prefs(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL)
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Small silent notification for the unlock service when the card notification is turned off. */
    fun buildServiceOnly(ctx: Context): android.app.Notification {
        ensureChannel(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(ctx.getString(R.string.unlock_service_text))
            .setContentIntent(CardViews.openApp(ctx))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, e: Entry) {
        val p = Prefs(ctx)
        if (!p.lockNotification) {
            // While the unlock service runs it owns this id with its own small notification.
            if (!UnlockService.wanted(ctx)) cancel(ctx)
            return
        }
        if (!canPost(ctx)) return
        NotificationManagerCompat.from(ctx).notify(ID, build(ctx, e))
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
        Updater.scheduleChecks(context)
        Cards.refresh(context)
        // After a self-update the system usually relaunches us via the install-status intent;
        // this notification is the fallback if it can't.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.notifyUpdated(context)
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
        UnlockService.sync(ctx)
        val interval = Prefs(ctx).intervalMinutes
        if (interval <= 0) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
            return
        }
        val minutes = interval.toLong().coerceAtLeast(15)
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

/**
 * "Every unlock" mode. Screen on/off broadcasts can't be received from the manifest, so a small
 * foreground service listens for the screen turning off and loads the next card — it's ready the
 * next time the lock screen shows.
 */
class UnlockService : android.app.Service() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) Cards.advance(context)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        ContextCompat.registerReceiver(
            this, receiver, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!wanted(this)) { stopSelf(); return START_NOT_STICKY }
        goForeground()
        return START_STICKY
    }

    private fun goForeground() {
        val n = if (Prefs(this).lockNotification) LockCard.build(this, Cards.current(this)) else LockCard.buildServiceOnly(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(LockCard.NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(LockCard.NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    companion object {
        fun wanted(ctx: Context) = Prefs(ctx).intervalMinutes <= 0

        /** Start or stop to match settings. Safe to call from the app, boot and package-replaced. */
        fun sync(ctx: Context) {
            val intent = Intent(ctx, UnlockService::class.java)
            if (wanted(ctx)) {
                runCatching { ContextCompat.startForegroundService(ctx, intent) }
            } else {
                ctx.stopService(intent)
            }
        }
    }
}

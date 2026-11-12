package io.github.ari1re.kanjilock

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Self-updater: reads the rolling "latest" GitHub release that CI publishes on every push to main,
 * downloads the APK and installs it with PackageInstaller. The app relaunches after the update.
 */
object Updater {
    const val OWNER = "Ari1re"
    const val REPO = "AnkiLock"
    const val ACTION_INSTALL_STATUS = "io.github.ari1re.kanjilock.INSTALL_STATUS"
    const val EXTRA_AUTO_INSTALL = "auto_install_update"
    private const val CHANNEL = "updates"
    private const val NOTIF_AVAILABLE = 2
    private const val NOTIF_UPDATED = 3

    data class Release(val versionCode: Long, val title: String, val apkName: String, val apiUrl: String, val browserUrl: String, val size: Long)

    class UpdateException(msg: String) : Exception(msg)

    fun installedVersionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun installedVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun open(url: String, token: String?, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "KanjiLock-updater")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }

    /** Returns the newest release if it's newer than what's installed, else null. Blocking. */
    fun check(ctx: Context): Release? {
        val token = Prefs(ctx).githubToken
        val conn = open("https://api.github.com/repos/$OWNER/$REPO/releases/tags/latest", token, "application/vnd.github+json")
        try {
            when (conn.responseCode) {
                200 -> {}
                401 -> throw UpdateException("GitHub rejected the token. Check it in Settings → Updates.")
                403, 429 -> throw UpdateException("GitHub rate limit hit — try again in a bit.")
                404 -> throw UpdateException(
                    if (token.isNullOrBlank()) "Can't see $OWNER/$REPO releases. If the repo is private, add a GitHub token below."
                    else "No \"latest\" release found yet (or the token can't read $OWNER/$REPO)."
                )
                else -> throw UpdateException("GitHub answered ${conn.responseCode}.")
            }
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: throw UpdateException("The latest release has no APK yet — the build may still be running.")
            val body = o.optString("body")
            val name = apk.getString("name")
            val code = Regex("""versionCode:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toLong()
                ?: Regex("""-0\.1\.(\d+)\.apk$""").find(name)?.groupValues?.get(1)?.toLong()
                ?: throw UpdateException("Couldn't tell which build the latest release is.")
            val rel = Release(code, o.optString("name", "Latest build"), name, apk.getString("url"), apk.getString("browser_download_url"), apk.optLong("size", -1))
            return if (code > installedVersionCode(ctx)) rel else null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK to cache. Blocking; [progress] gets 0..1 (or -1 if size unknown). */
    fun download(ctx: Context, rel: Release, progress: (Float) -> Unit): File {
        val token = Prefs(ctx).githubToken
        val conn = if (token.isNullOrBlank()) {
            open(rel.browserUrl, null, "application/octet-stream")
        } else {
            // Private repo: ask the API for the asset, then follow the signed redirect WITHOUT the token.
            val api = open(rel.apiUrl, token, "application/octet-stream").apply { instanceFollowRedirects = false }
            val code = api.responseCode
            if (code in 300..399) {
                val loc = api.getHeaderField("Location") ?: throw UpdateException("Download redirect had no location.")
                api.disconnect()
                open(loc, null, "application/octet-stream")
            } else api
        }
        try {
            if (conn.responseCode !in 200..299) throw UpdateException("Download failed (${conn.responseCode}).")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: rel.size
            val out = File(ctx.cacheDir, "update.apk")
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        progress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Hands the APK to the system installer. The result comes back to MainActivity via [ACTION_INSTALL_STATUS]. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            // Once KanjiLock is the installer of record, later updates can go through without a prompt.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(statusIntent(ctx).intentSender)
        }
    }

    private fun statusIntent(ctx: Context): PendingIntent {
        // Delivered to MainActivity: it shows the confirm dialog, and after a successful update the
        // freshly installed app is launched again ("reset").
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(ACTION_INSTALL_STATUS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val options = if (Build.VERSION.SDK_INT >= 34) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        return PendingIntent.getActivity(ctx, 7, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, options)
    }

    // ------------------------------------------------------------ notifications

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.updates_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(ctx: Context, id: Int, title: String, text: String, autoInstall: Boolean) {
        if (!LockCard.canPost(ctx)) return
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_AUTO_INSTALL, autoInstall)
        val pi = PendingIntent.getActivity(ctx, 10 + id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    fun notifyAvailable(ctx: Context, rel: Release) =
        notify(ctx, NOTIF_AVAILABLE, "KanjiLock update available", "Build ${rel.versionCode} — tap to install", autoInstall = true)

    fun notifyUpdated(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_AVAILABLE)
        notify(ctx, NOTIF_UPDATED, "KanjiLock updated", "Now on ${installedVersionName(ctx)}. Tap to open.", autoInstall = false)
    }

    fun clearNotifications(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_AVAILABLE)
        NotificationManagerCompat.from(ctx).cancel(NOTIF_UPDATED)
    }

    fun scheduleChecks(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("update_check", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** Twice a day: if CI published a newer build, post a "tap to install" notification. */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val rel = runCatching { Updater.check(applicationContext) }.getOrNull()
        if (rel != null) Updater.notifyAvailable(applicationContext, rel)
        return Result.success()
    }
}

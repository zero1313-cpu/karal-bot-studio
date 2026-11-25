package com.karal.studio.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.karal.studio.KaralApplication
import com.karal.studio.MainActivity
import com.karal.studio.R
import com.karal.studio.launchIO
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Foreground service that keeps bot gateway connections alive while the app is backgrounded. */
class BotHostingService : Service() {
    companion object {
        const val ACTION_START = "com.karal.studio.action.START_BOT"
        const val ACTION_STOP = "com.karal.studio.action.STOP_BOT"
        const val ACTION_STOP_ALL = "com.karal.studio.action.STOP_ALL"
        const val ACTION_RESTORE = "com.karal.studio.action.RESTORE"
        const val EXTRA_BOT_ID = "botId"
        private const val CHANNEL_ID = "karal_bot_hosting"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context, botId: String) {
            val intent = Intent(context, BotHostingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_BOT_ID, botId)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, botId: String) {
            val intent = Intent(context, BotHostingService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_BOT_ID, botId)
            }
            context.startService(intent)
        }

        fun stopAll(context: Context) {
            context.startService(Intent(context, BotHostingService::class.java).setAction(ACTION_STOP_ALL))
        }

        fun restore(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, BotHostingService::class.java).setAction(ACTION_RESTORE)
            )
        }
    }

    private val manager by lazy { (application as KaralApplication).container.runtime }
    private var observerJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification(emptySet()))
        observerJob = manager.snapshots.onEach { snapshots ->
            val running = snapshots.filter { manager.isRunning(it.key) }.keys
            updateNotification(running)
            if (running.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.launchIn((application as KaralApplication).appScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_BOT_ID)?.let { botId ->
                manager.start(botId)
                (application as KaralApplication).appScope.launchIO {
                    (application as KaralApplication).container.bots.get(botId)?.let { bot ->
                        manager.bindProject(botId, bot.projectId)
                    }
                }
            }
            ACTION_STOP -> intent.getStringExtra(EXTRA_BOT_ID)?.let(manager::stop)
            ACTION_STOP_ALL -> manager.stopAll()
            ACTION_RESTORE -> (application as KaralApplication).appScope.launchIO {
                manager.restoreAutoStart()
                manager.snapshots.value.filter { manager.isRunning(it.key) }.keys.forEach { botId ->
                    (application as KaralApplication).container.bots.get(botId)?.let { bot ->
                        manager.bindProject(botId, bot.projectId)
                    }
                }
            }
            else -> Unit
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observerJob?.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bot hosting", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps your Discord bots connected in the background."
                setShowBadge(false)
            }
        )
    }

    private fun notification(running: Set<String>): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = if (running.isEmpty()) {
            "Preparing bot hosting…"
        } else {
            "${running.size} bot(s) connected — tap to open KARAL"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setContentTitle("KARAL Bot Studio")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(running: Set<String>) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(running)) }
    }
}

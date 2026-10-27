package com.lumentv.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lumentv.R

/** The ongoing notice a foreground download must show. */
object Notifications {
    const val TRANSFER_ID = 4100
    private const val CHANNEL = "transfers"

    fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun transfers(context: Context, titles: List<String>): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            manager(context).createNotificationChannel(NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(context, 1, context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(if (titles.isEmpty()) "Lumen TV" else "${titles.size} téléchargement(s) en cours")
            .setContentText(titles.joinToString(" · ").ifBlank { "Préparation…" })
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}

package net.joshe.pandplay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

const val CHANNEL_ID_PLAYER = "$PANDPLAY_CONST_ID.playerNotificationChannel"
const val CHANNEL_ID_DOWNLOAD = "$PANDPLAY_CONST_ID.downloadSongChannel"
const val NOTIFICATION_ID_PLAYING = 1
const val NOTIFICATION_ID_DOWNLOADING = 2

fun createNotificationChannel(context: Context, id: String, nameId: Int, descId: Int) : Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        mkchan(context, id, nameId, descId)
    return true
}

@RequiresApi(Build.VERSION_CODES.O)
private fun mkchan(context: Context, id: String, nameId: Int, descId: Int) {
    val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val chan = NotificationChannel(id, context.getString(nameId), NotificationManager.IMPORTANCE_LOW)
    chan.description = context.getString(descId)
    mgr.createNotificationChannel(chan)
}

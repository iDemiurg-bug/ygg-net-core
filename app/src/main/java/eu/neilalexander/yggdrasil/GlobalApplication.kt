package eu.neilalexander.yggdrasil

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager

const val PREF_KEY_ENABLED = "enabled"
const val PREF_KEY_PEERS_NOTE = "peers_note"
const val MAIN_CHANNEL_ID = "YggdrasilService"
const val SERVICE_NOTIFICATION_ID = 1000

class GlobalApplication : Application(), YggStateReceiver.StateReceiver {

    private lateinit var config: ConfigurationProxy
    private var currentState: State = State.Disabled
    private var updaterConnections: Int = 0

    override fun onCreate() {
        super.onCreate()
        config = ConfigurationProxy(applicationContext)

        // Register network callback
        val callback = NetworkStateCallback(this)
        callback.register()

        // Register state receiver
        val receiver = YggStateReceiver(this)
        receiver.register(this)

        // Migrate DNS servers if needed
        migrateDnsServers(this)
    }

    fun subscribe() {
        updaterConnections++
    }

    fun unsubscribe() {
        if (updaterConnections > 0) {
            updaterConnections--
        }
    }

    fun needUiUpdates(): Boolean {
        return updaterConnections > 0
    }

    fun getCurrentState(): State {
        return currentState
    }

    override fun onStateChange(state: State) {
        if (state != currentState) {
            // Only show notifications when service is running and UI is not active
            if (state != State.Disabled && !needUiUpdates()) {
                val notification = createServiceNotification(this, state)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(SERVICE_NOTIFICATION_ID, notification)
            }

            currentState = state
        }
    }
}

fun migrateDnsServers(context: Context) {
    val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    if (preferences.getInt(KEY_DNS_VERSION, 0) >= 1) {
        return
    }

    val serverString = preferences.getString(KEY_DNS_SERVERS, "")
    if (!serverString.isNullOrEmpty()) {
        // Replacing old Revertron's servers by new ones
        val newServers = serverString
            .replace("300:6223::53", "308:25:40:bd::")
            .replace("302:7991::53", "308:62:45:62::")
            .replace("302:db60::53", "308:84:68:55::")
            .replace("301:1088::53", "308:c8:48:45::")

        val editor = preferences.edit()
        editor.putInt(KEY_DNS_VERSION, 1)
        if (newServers != serverString) {
            editor.putString(KEY_DNS_SERVERS, newServers)
        }
        editor.apply()
    }
}

fun createServiceNotification(context: Context, state: State): Notification {
    createNotificationChannels(context)

    val text = when (state) {
        State.Enabled -> "Yggdrasil is running"
        State.Connected -> "Yggdrasil connected"
        else -> "Yggdrasil service"
    }

    return NotificationCompat.Builder(context, MAIN_CHANNEL_ID)
        .setContentTitle("Yggdrasil")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()
}

private fun createNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            MAIN_CHANNEL_ID,
            "Yggdrasil Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows Yggdrasil background service status"
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}

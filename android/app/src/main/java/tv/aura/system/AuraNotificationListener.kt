package tv.aura.system

import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Access is opt-in through Android's notification-listener grant; never reads notification text. */
class AuraNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected.value = true
    }

    override fun onListenerDisconnected() {
        connected.value = false
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        connected.value = false
        super.onDestroy()
    }

    companion object {
        private val connected = MutableStateFlow(false)
        internal val connection = connected.asStateFlow()
    }
}

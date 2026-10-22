package tv.aura.homeguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class HomeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Enabling the service is the opt-in. Never steal boot from an installed fallback launcher.
        val status = HomeGuard.status(context)
        if (status.enabled && !status.passiveForHomeOnFire) HomeGuard.launch(context, reset = true)
    }
}

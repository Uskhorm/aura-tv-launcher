package tv.aura.probe.homekey;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public final class HomeKeyProbeService extends AccessibilityService {
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            trace("BROADCAST action=" + intent.getAction() + " extras=" + intent.getExtras());
        }
    };
    static long homeAt;
    static void trace(String message) {
        Log.i("AuraProbe", "uptimeMs=" + SystemClock.uptimeMillis() + " " + message);
    }
    @Override protected void onServiceConnected() {
        trace("PROBE_V2 CONNECTED flags=" + getServiceInfo().flags + " capabilities=" + getServiceInfo().getCapabilities());
        IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction("amazon.intent.action.HOME_PRESSED");
        filter.addAction("com.amazon.intent.action.HOME_PRESSED");
        registerReceiver(receiver, filter);
    }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        trace("KEY code=" + event.getKeyCode() + " name=" + KeyEvent.keyCodeToString(event.getKeyCode())
            + " action=" + event.getAction() + " repeat=" + event.getRepeatCount()
            + " long=" + event.isLongPress() + " flags=" + event.getFlags()
            + " downTime=" + event.getDownTime() + " eventTime=" + event.getEventTime()
            + " device=" + event.getDeviceId() + " source=" + event.getSource());
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            homeAt = SystemClock.uptimeMillis();
            try {
                startActivity(new Intent(this, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("homeAt", homeAt));
                trace("START_REQUEST homeAt=" + homeAt);
            } catch (RuntimeException error) { trace("START_FAILED " + error); }
        }
        trace("CONSUME_HOME action=" + event.getAction());
        return true;
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        trace("EVENT type=" + AccessibilityEvent.eventTypeToString(event.getEventType()) + " windowId=" + event.getWindowId() + " package=" + event.getPackageName()
            + " class=" + event.getClassName() + " eventTime=" + event.getEventTime()
            + " AMAZON=" + "com.amazon.tv.launcher".contentEquals(event.getPackageName() == null ? "" : event.getPackageName()));
    }
    @Override public void onInterrupt() { trace("INTERRUPTED"); }
    @Override public void onDestroy() {
        try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
        trace("DESTROYED"); super.onDestroy();
    }
}

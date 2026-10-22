package tv.aura.probe.homekey;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.TextView;

public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("AURA HOME KEY PROBE\nCheck AuraProbe logcat for monotonic timings");
        view.setGravity(Gravity.CENTER);
        view.setTextColor(Color.WHITE);
        view.setTextSize(30);
        view.setBackgroundColor(Color.rgb(10, 18, 35));
        setContentView(view);
        HomeKeyProbeService.trace("ACTIVITY_CREATE");
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        HomeKeyProbeService.trace("ACTIVITY_NEW_INTENT");
    }
    @Override public void onResume() {
        super.onResume();
        long at = getIntent().getLongExtra("homeAt", 0);
        HomeKeyProbeService.trace("ACTIVITY_RESUME homeAt=" + at + " latencyMs=" + (at == 0 ? -1 : SystemClock.uptimeMillis() - at));
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        HomeKeyProbeService.trace("ACTIVITY_FOCUS focused=" + focused);
    }
}

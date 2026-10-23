package com.nixwang.gromore_mediation;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Internal full-screen host; intentionally has no Flutter engine. */
public final class SplashAdActivity extends Activity {
    private SplashAdController owner;
    private int token;
    private Runnable adCleanup;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Fade this launch overlay instead of using the system navigation slide.
        // Window theme animations alone do not override Activity transitions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,
                    android.R.anim.fade_in, android.R.anim.fade_out);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE,
                    android.R.anim.fade_in, android.R.anim.fade_out);
        }
        token = getIntent().getIntExtra(SplashAdController.REQUEST_ID, -1);
        owner = SplashAdController.owner(token);
        // Process recreation has no live SDK object/request to restore.
        if (owner == null) { finish(); return; }
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.WHITE);
        setContentView(container);
        owner.attach(token, this, container);
    }
    @SuppressWarnings("deprecation")
    @Override public void finish() {
        super.finish();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }
    void finishWithAdCleanup(Runnable cleanup) {
        if (isDestroyed()) {
            cleanup.run();
            return;
        }
        adCleanup = cleanup;
        if (!isFinishing()) finish();
    }
    @Override protected void onDestroy() {
        if (owner != null) owner.detached(token);
        owner = null;
        Runnable cleanup = adCleanup;
        adCleanup = null;
        if (cleanup != null) cleanup.run();
        super.onDestroy();
    }
}

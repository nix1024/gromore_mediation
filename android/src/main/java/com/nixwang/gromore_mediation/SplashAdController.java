package com.nixwang.gromore_mediation;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.graphics.Point;
import android.os.Bundle;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.FrameLayout;
import com.bytedance.sdk.openadsdk.AdSlot;
import com.bytedance.sdk.openadsdk.CSJAdError;
import com.bytedance.sdk.openadsdk.CSJSplashAd;
import com.bytedance.sdk.openadsdk.TTAdNative;
import com.bytedance.sdk.openadsdk.TTAdSdk;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** Uses a separate Activity so Flutter's deferred first frame cannot block ads. */
final class SplashAdController {
    static final String REQUEST_ID = "gromore_request";
    private static int nextId;
    private static final Map<Integer, SplashAdController> owners = new HashMap<>();
    private final Activity host;
    private final Consumer<Object> events;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Request current;
    private static final class Request {
        final int token = ++nextId;
        final String id;
        final long deadline;
        SplashAdActivity page;
        FrameLayout container;
        CSJSplashAd ad;
        Runnable timeout;
        boolean exposed;
        boolean rendered;
        Application.ActivityLifecycleCallbacks lifecycle;
        Request(String id, int millis) {
            this.id = id;
            deadline = SystemClock.uptimeMillis() + millis;
        }
    }
    SplashAdController(Activity host, Consumer<Object> events) {
        this.host = host;
        this.events = events;
    }
    static SplashAdController owner(int token) { return owners.get(token); }
    boolean isActive() { return current != null; }
    void show(MethodCall call, MethodChannel.Result result) {
        if (current != null) { result.success(false); return; }
        String id = call.argument("posId");
        Number seconds = call.argument("timeout");
        int millis = seconds == null ? 3500 : (int) Math.ceil(seconds.doubleValue() * 1000);
        if (millis <= 0) { result.success(false); return; }
        Request request = new Request(id, millis);
        current = request;
        owners.put(request.token, this);
        request.timeout = () -> fail(request, -1, "Splash exposure timed out");
        handler.postDelayed(request.timeout, millis);
        // Cancel if the host leaves the foreground while the ad is still loading.
        request.lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity activity) {
                if (activity == host && !request.rendered) {
                    fail(request, -1, "Host paused before splash was ready");
                }
            }
            @Override public void onActivityDestroyed(Activity activity) {
                if (activity == host) fail(request, -1, "Host destroyed");
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        };
        host.getApplication().registerActivityLifecycleCallbacks(request.lifecycle);
        load(request);
        result.success(current == request);
    }
    void attach(int token, SplashAdActivity page, FrameLayout container) {
        Request request = current;
        if (request == null || request.token != token) { page.finish(); return; }
        request.page = page;
        request.container = container;
        if (SystemClock.uptimeMillis() >= request.deadline) {
            fail(request, -1, "Splash exposure timed out"); return;
        }
        // The ad is already rendered; attaching the page must not start a second load.
        try {
            request.ad.showSplashView(container);
        } catch (RuntimeException error) { fail(request, -1, error.toString()); }
    }
    private void load(Request request) {
        if (current != request) return;
        int millis = (int) (request.deadline - SystemClock.uptimeMillis());
        if (millis <= 0) { fail(request, -1, "Splash exposure timed out"); return; }
        if (host.isFinishing() || host.isDestroyed()) {
            fail(request, -1, "Activity unavailable"); return;
        }
        // Request the fullscreen area before the ad Activity exists. Display size
        // excludes system navigation and includes the status bar hidden by that page.
        Point size = new Point();
        host.getWindowManager().getDefaultDisplay().getSize(size);
        int width = size.x;
        int height = size.y;
        float density = host.getResources().getDisplayMetrics().density;
        AdSlot slot = new AdSlot.Builder().setCodeId(request.id)
                .setImageAcceptedSize(width, height)
                .setExpressViewAcceptedSize(width / density, height / density).build();
        try {
            TTAdSdk.getAdManager().createAdNative(host).loadSplashAd(slot,
                    new TTAdNative.CSJSplashAdListener() {
                @Override public void onSplashLoadSuccess(CSJSplashAd ad) {
                    host.runOnUiThread(() -> {
                        if (current == request && !request.rendered) { request.ad = ad; }
                        else if (ad != request.ad) { destroy(ad); }
                    });
                }
                @Override public void onSplashLoadFail(CSJAdError error) {
                    host.runOnUiThread(() -> fail(request, error.getCode(), error.getMsg()));
                }
                @Override public void onSplashRenderFail(CSJSplashAd ad, CSJAdError error) {
                    host.runOnUiThread(() -> {
                        if (ad != request.ad) destroy(ad);
                        fail(request, error.getCode(), error.getMsg());
                    });
                }
                @Override public void onSplashRenderSuccess(CSJSplashAd ad) {
                    host.runOnUiThread(() -> render(request, ad));
                }
            }, millis);
        } catch (RuntimeException error) { fail(request, -1, error.toString()); }
    }
    private void render(Request request, CSJSplashAd ad) {
        if (current != request) {
            if (ad != request.ad) destroy(ad);
            return;
        }
        if (request.rendered) {
            if (ad != request.ad) destroy(ad);
            return;
        }
        request.ad = ad;
        if (SystemClock.uptimeMillis() >= request.deadline) {
            fail(request, -1, "Splash exposure timed out"); return;
        }
        if (ad == null || host.isFinishing() || host.isDestroyed()) {
            fail(request, -1, "Activity unavailable"); return;
        }
        request.rendered = true;
        try {
            ad.setSplashAdListener(new CSJSplashAd.SplashAdListener() {
                @Override public void onSplashAdShow(CSJSplashAd value) {
                    host.runOnUiThread(() -> {
                        if (current != request || request.exposed) return;
                        request.exposed = true;
                        handler.removeCallbacks(request.timeout);
                        emit(request, "onAdExposure");
                    });
                }
                @Override public void onSplashAdClick(CSJSplashAd value) {
                    host.runOnUiThread(() -> {
                        if (current == request) emit(request, "onAdClicked");
                    });
                }
                @Override public void onSplashAdClose(CSJSplashAd value, int closeType) {
                    host.runOnUiThread(() -> {
                        if (current != request) return;
                        emit(request, "onAdClosed");
                        release(request, true);
                    });
                }
            });
            emit(request, "onAdLoaded");
            host.startActivity(new Intent(host, SplashAdActivity.class)
                    .putExtra(REQUEST_ID, request.token),
                    ActivityOptions.makeCustomAnimation(host,
                            android.R.anim.fade_in, android.R.anim.fade_out).toBundle());
        } catch (RuntimeException error) { fail(request, -1, error.toString()); }
    }
    void detached(int token) {
        if (current != null && current.token == token) {
            fail(current, -1, "Splash Activity destroyed");
        }
    }
    private void emit(Request request, String action) {
        events.accept(InterstitialAdController.event(request.id, action));
    }
    private void fail(Request request, int code, String message) {
        if (current != request) return;
        Map<String, Object> event = InterstitialAdController.event(request.id, "onAdError");
        event.put("errCode", code);
        event.put("errMsg", message == null ? "" : message);
        events.accept(event);
        release(request);
    }
    private void release(Request request) {
        release(request, false);
    }
    private void release(Request request, boolean preserveForFade) {
        current = null;
        owners.remove(request.token);
        host.getApplication().unregisterActivityLifecycleCallbacks(request.lifecycle);
        handler.removeCallbacks(request.timeout);
        if (preserveForFade && request.page != null) {
            // Keep the final ad frame until the Activity's exit transition ends.
            request.page.finishWithAdCleanup(() -> releaseAd(request));
            return;
        }
        SplashAdActivity page = request.page;
        releaseAd(request);
        if (page != null && !page.isFinishing() && !page.isDestroyed()) page.finish();
    }
    private void releaseAd(Request request) {
        if (request.container != null) request.container.removeAllViews();
        destroy(request.ad);
        request.ad = null;
        request.container = null;
        request.page = null;
    }
    private static void destroy(CSJSplashAd ad) {
        if (ad != null && ad.getMediationManager() != null) ad.getMediationManager().destroy();
    }
    void dispose() {
        if (current != null) fail(current, -1, "Activity detached or splash disposed");
    }
}

package com.nixwang.gromore_mediation;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import com.bytedance.sdk.openadsdk.AdSlot;
import com.bytedance.sdk.openadsdk.TTAdNative;
import com.bytedance.sdk.openadsdk.TTAdSdk;
import com.bytedance.sdk.openadsdk.TTFullScreenVideoAd;
import com.bytedance.sdk.openadsdk.mediation.ad.MediationAdSlot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** Owns one cached object per placement. All state is confined to the UI thread. */
final class InterstitialAdController {
    private final Activity activity;
    private final Consumer<Object> events;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, TTFullScreenVideoAd> cached = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private TTFullScreenVideoAd presenting;
    private String presentingId;

    private static final class Pending {
        final ArrayList<MethodChannel.Result> results = new ArrayList<>();
        TTFullScreenVideoAd ad;
        boolean accepted;
        boolean legacyCached;
        Runnable timeout;
    }

    InterstitialAdController(Activity activity, Consumer<Object> events) {
        this.activity = activity;
        this.events = events;
    }

    boolean isPresenting() { return presenting != null; }

    boolean ready(String id) { return ready(cached.get(id)); }

    void load(MethodCall call, MethodChannel.Result result) {
        String id = call.argument("posId");
        if (ready(id)) { result.success(true); return; }
        destroy(cached.remove(id));
        Pending existing = pending.get(id);
        if (existing != null) { existing.results.add(result); return; }
        Pending request = new Pending();
        request.results.add(result);
        pending.put(id, request);
        request.timeout = () -> fail(id, request, -1, "Interstitial load timed out");
        handler.postDelayed(request.timeout, 30000);
        Number orientation = call.argument("orientation");
        AdSlot slot = new AdSlot.Builder().setCodeId(id).setAdCount(1)
                .setOrientation(orientation == null ? 1 : orientation.intValue())
                .setMediationAdSlot(new MediationAdSlot.Builder()
                        .setMuted(Boolean.TRUE.equals(call.argument("isMuted"))).build())
                .build();
        try {
            TTAdSdk.getAdManager().createAdNative(activity).loadFullScreenVideoAd(slot,
                    new TTAdNative.FullScreenVideoAdListener() {
                @Override public void onError(int code, String message) {
                    activity.runOnUiThread(() -> fail(id, request, code, message));
                }
                @Override public void onFullScreenVideoAdLoad(TTFullScreenVideoAd ad) {
                    activity.runOnUiThread(() -> {
                        if (pending.get(id) != request) {
                            if (!request.accepted) destroy(ad);
                            return;
                        }
                        request.ad = ad;
                        if (request.legacyCached) loaded(id, request, ad);
                    });
                }
                @Override public void onFullScreenVideoCached() {
                    activity.runOnUiThread(() -> {
                        request.legacyCached = true;
                        if (request.ad != null) loaded(id, request, request.ad);
                    });
                }
                @Override public void onFullScreenVideoCached(TTFullScreenVideoAd ad) {
                    activity.runOnUiThread(() -> loaded(id, request, ad));
                }
            });
        } catch (RuntimeException error) { fail(id, request, -1, error.toString()); }
    }

    private void loaded(String id, Pending request, TTFullScreenVideoAd ad) {
        if (pending.get(id) != request) {
            // Load/cache callbacks may wrap the same native ad in different Java
            // objects. Once accepted, later callbacks belong to that owned ad.
            if (!request.accepted) destroy(ad);
            return;
        }
        if (!ready(ad)) {
            if (ad != request.ad) destroy(ad);
            fail(id, request, -1, "Cached interstitial is not ready");
            return;
        }
        request.accepted = true;
        // Do not destroy request.ad: its wrapper can share ad's native manager.
        // The cached/presenting owner releases it on expiry, close or disposal.
        cached.put(id, ad);
        emit(id, "onAdLoaded");
        complete(id, request, true);
    }

    private void fail(String id, Pending request, int code, String message) {
        if (pending.get(id) != request) return;
        destroy(request.ad);
        error(id, code, message);
        complete(id, request, false);
    }

    private void complete(String id, Pending request, boolean success) {
        pending.remove(id);
        handler.removeCallbacks(request.timeout);
        for (MethodChannel.Result result : request.results) result.success(success);
        request.results.clear();
    }

    void show(MethodCall call, MethodChannel.Result result) {
        String id = call.argument("posId");
        if (presenting != null) { result.success(false); return; }
        TTFullScreenVideoAd ad = cached.remove(id);
        if (!ready(ad)) { destroy(ad); result.success(false); return; }
        presenting = ad;
        presentingId = id;
        try {
            ad.setFullScreenVideoAdInteractionListener(
                new TTFullScreenVideoAd.FullScreenVideoAdInteractionListener() {
            private void event(String action) {
                activity.runOnUiThread(() -> {
                    if (presenting == ad) emit(id, action);
                });
            }
            @Override public void onAdShow() { event("onAdExposure"); }
            @Override public void onAdVideoBarClick() { event("onAdClicked"); }
            @Override public void onVideoComplete() { event("onAdComplete"); }
            @Override public void onSkippedVideo() { event("onAdSkip"); }
            @Override public void onAdClose() {
                activity.runOnUiThread(() -> {
                    if (presenting != ad) return;
                    presenting = null;
                    presentingId = null;
                    emit(id, "onAdClosed");
                    destroy(ad);
                });
            }
            });
            ad.showFullScreenVideoAd(activity);
            result.success(true);
        } catch (RuntimeException failure) {
            presenting = null;
            presentingId = null;
            destroy(ad);
            error(id, -1, failure.toString());
            result.success(false);
        }
    }

    void dispose() {
        for (TTFullScreenVideoAd ad : cached.values()) destroy(ad);
        cached.clear();
        for (String id : new ArrayList<>(pending.keySet())) {
            fail(id, pending.get(id), -1, "Interstitial disposed");
        }
        TTFullScreenVideoAd ad = presenting;
        String id = presentingId;
        presenting = null;
        presentingId = null;
        if (ad != null) { error(id, -1, "Activity detached or ad disposed"); destroy(ad); }
    }

    private static boolean ready(TTFullScreenVideoAd ad) {
        return ad != null && ad.getMediationManager() != null
                && ad.getMediationManager().isReady();
    }

    private static void destroy(TTFullScreenVideoAd ad) {
        if (ad != null && ad.getMediationManager() != null) ad.getMediationManager().destroy();
    }

    private void emit(String id, String action) { events.accept(event(id, action)); }
    private void error(String id, int code, String message) {
        Map<String, Object> event = event(id, "onAdError");
        event.put("errCode", code);
        event.put("errMsg", message == null ? "" : message);
        events.accept(event);
    }
    static Map<String, Object> event(String id, String action) {
        Map<String, Object> event = new HashMap<>();
        event.put("posId", id);
        event.put("action", action);
        return event;
    }
}

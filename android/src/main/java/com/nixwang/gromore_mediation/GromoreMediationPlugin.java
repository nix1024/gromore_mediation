package com.nixwang.gromore_mediation;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import com.bytedance.sdk.openadsdk.TTAdConfig;
import com.bytedance.sdk.openadsdk.TTAdSdk;
import com.bytedance.sdk.openadsdk.TTCustomController;
import com.bytedance.sdk.openadsdk.mediation.init.MediationPrivacyConfig;
import com.bytedance.sdk.openadsdk.mediation.init.MediationConfig;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public final class GromoreMediationPlugin implements FlutterPlugin, ActivityAware,
        MethodChannel.MethodCallHandler, EventChannel.StreamHandler {
    private MethodChannel methods;
    private EventChannel events;
    private EventChannel.EventSink sink;
    private Context context;
    private Activity activity;
    private InterstitialAdController interstitial;
    private SplashAdController splash;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MethodChannel.Result> initResults = new ArrayList<>();
    private int initGeneration;
    private Runnable initTimeout;
    private static String configuredAppId;

    @Override public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        methods = new MethodChannel(binding.getBinaryMessenger(), "gromore_mediation");
        events = new EventChannel(binding.getBinaryMessenger(), "gromore_mediation/events");
        methods.setMethodCallHandler(this);
        events.setStreamHandler(this);
    }
    @Override public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        detachActivity();
        completeInit(false);
        methods.setMethodCallHandler(null);
        events.setStreamHandler(null);
        sink = null;
        context = null;
    }
    @Override public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        detachActivity();
        activity = binding.getActivity();
        interstitial = new InterstitialAdController(activity, this::emit);
        splash = new SplashAdController(activity, this::emit);
    }
    @Override public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }
    @Override public void onDetachedFromActivityForConfigChanges() { detachActivity(); }
    @Override public void onDetachedFromActivity() { detachActivity(); }
    private void detachActivity() {
        if (interstitial != null) interstitial.dispose();
        if (splash != null) splash.dispose();
        interstitial = null;
        splash = null;
        activity = null;
    }
    @Override public void onListen(Object arguments, EventChannel.EventSink sink) { this.sink = sink; }
    @Override public void onCancel(Object arguments) { sink = null; }
    private void emit(Object value) {
        if (sink != null) sink.success(value);
    }
    @Override public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        if ("initAd".equals(call.method)) { initialize(call, result); return; }
        if ("dispose".equals(call.method)) {
            if (interstitial != null) interstitial.dispose();
            if (splash != null) splash.dispose();
            result.success(null); return;
        }
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            result.error("activity_unavailable", "A foreground Activity is required", null); return;
        }
        if ("launchTestTools".equals(call.method)) { launchTools(result); return; }
        if (!TTAdSdk.isSdkReady()) {
            result.error("not_initialized", "Call initAd after consent before requesting ads", null); return;
        }
        String id = call.argument("posId");
        if (id == null || id.trim().isEmpty()) {
            result.error("invalid_arguments", "posId is required", null); return;
        }
        switch (call.method) {
            case "loadInterstitialAd": interstitial.load(call, result); break;
            case "showInterstitialAd":
                if (splash.isActive()) result.success(false);
                else interstitial.show(call, result);
                break;
            case "isInterstitialAdReady": result.success(interstitial.ready(id)); break;
            case "showSplashAd":
                if (interstitial.isPresenting()) result.success(false);
                else splash.show(call, result);
                break;
            default: result.notImplemented();
        }
    }
    private void initialize(MethodCall call, MethodChannel.Result result) {
        String appId = call.argument("appId");
        if (appId == null || appId.trim().isEmpty()) {
            result.error("invalid_arguments", "appId is required", null); return;
        }
        if (configuredAppId != null && !configuredAppId.equals(appId)) {
            result.error("app_id_mismatch", "GroMore cannot switch appId in the same process", null); return;
        }
        if (TTAdSdk.isSdkReady()) { result.success(true); return; }
        String localConfig = call.argument("localConfig");
        JSONObject localConfigJson = null;
        if (localConfig != null) {
            try {
                localConfigJson = new JSONObject(localConfig);
            } catch (JSONException error) {
                result.error("invalid_arguments", "localConfig must be a JSON object", null); return;
            }
        }
        initResults.add(result);
        if (initResults.size() > 1) return;
        configuredAppId = appId;
        final int generation = ++initGeneration;
        initTimeout = () -> completeInit(false);
        handler.postDelayed(initTimeout, 30000);
        Map<String, Boolean> privacy = call.argument("privacyConfig");
        Map<String, Boolean> values = privacy == null ? Collections.emptyMap() : privacy;
        TTCustomController controller = new TTCustomController() {
            private boolean allowed(String key) { return Boolean.TRUE.equals(values.get(key)); }
            @Override public boolean isCanUseLocation() { return allowed("allowLocation"); }
            @Override public boolean isCanUsePhoneState() { return allowed("allowPhoneState"); }
            @Override public boolean isCanUseWifiState() { return allowed("allowWifiState"); }
            @Override public boolean isCanUseWriteExternal() { return allowed("allowExternalStorage"); }
            @Override public boolean isCanUseAndroidId() { return allowed("allowAndroidId"); }
            @Override public boolean alist() { return allowed("allowAppList"); }
            @Override public boolean isCanUsePermissionRecordAudio() { return false; }
            @Override public boolean isCanUseMessage() { return false; }
            @Override public MediationPrivacyConfig getMediationPrivacyConfig() {
                return new MediationPrivacyConfig() {
                    @Override public boolean isCanUseOaid() { return allowed("allowOaid"); }
                    @Override public boolean isLimitPersonalAds() { return !allowed("personalizedAds"); }
                    @Override public boolean isProgrammaticRecommend() { return allowed("personalizedAds"); }
                };
            }
        };
        Number theme = call.argument("themeStatus");
        try {
            TTAdConfig.Builder config = new TTAdConfig.Builder().appId(appId)
                    .appName(context.getApplicationInfo().loadLabel(context.getPackageManager()).toString())
                    .useMediation(true).supportMultiProcess(false)
                    .debug(Boolean.TRUE.equals(call.argument("isDebug")))
                    .themeStatus(theme == null ? 0 : theme.intValue())
                    .customController(privacy == null ? new TTCustomController() {} : controller);
            if (localConfigJson != null) {
                config.setMediationConfig(new MediationConfig.Builder()
                        .setCustomLocalConfig(localConfigJson).build());
            }
            TTAdSdk.init(context, config.build());
            TTAdSdk.start(new TTAdSdk.Callback() {
                @Override public void success() {
                    handler.post(() -> { if (generation == initGeneration) completeInit(true); });
                }
                @Override public void fail(int code, String message) {
                    handler.post(() -> { if (generation == initGeneration) completeInit(false); });
                }
            });
        } catch (RuntimeException error) { completeInit(false); }
    }
    private void completeInit(boolean success) {
        ++initGeneration;
        if (initTimeout != null) handler.removeCallbacks(initTimeout);
        for (MethodChannel.Result result : initResults) result.success(success);
        initResults.clear();
    }
    private void launchTools(MethodChannel.Result result) {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            result.success(false); return;
        }
        try {
            Class<?> tool = Class.forName("com.bytedance.mtesttools.api.TTMediationTestTool");
            Class<?> callback = Class.forName("com.bytedance.mtesttools.api.TTMediationTestTool$ImageCallBack");
            Object images = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback},
                    (proxy, method, args) -> {
                        // Match the existing host integration: image loading is optional.
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        if ("toString".equals(method.getName())) return "GroMoreTestImages";
                        return null;
                    });
            tool.getMethod("launchTestTools", Context.class, callback).invoke(null, activity, images);
            result.success(true);
        } catch (ReflectiveOperationException | LinkageError error) { result.success(false); }
    }
}

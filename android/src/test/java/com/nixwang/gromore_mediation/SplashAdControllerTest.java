package com.nixwang.gromore_mediation;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import android.app.Activity;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.View;
import com.bytedance.sdk.openadsdk.CSJSplashAd;
import com.bytedance.sdk.openadsdk.TTAdManager;
import com.bytedance.sdk.openadsdk.TTAdNative;
import com.bytedance.sdk.openadsdk.TTAdSdk;
import com.bytedance.sdk.openadsdk.mediation.manager.MediationSplashManager;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, shadows = SdkShadow.class,
        instrumentedPackages = {"com.bytedance.sdk.openadsdk"})
@LooperMode(LooperMode.Mode.PAUSED)
public class SplashAdControllerTest {
    private SplashAdController controller;
    private Activity activity;
    private org.robolectric.android.controller.ActivityController<Activity> hostController;
    private TTAdNative loader;
    private TTAdManager manager;
    private TTAdNative.CSJSplashAdListener listener;
    private ViewGroup root;
    private SplashAdActivity page;
    private final ArrayList<Object> events = new ArrayList<>();
    @Before public void setup() {
        hostController = Robolectric.buildActivity(Activity.class).setup();
        activity = hostController.get();
        loader = mock(TTAdNative.class);
        manager = mock(TTAdManager.class);
        when(manager.createAdNative(any())).thenReturn(loader);
        doAnswer(invocation -> { listener = invocation.getArgument(1); return null; })
                .when(loader).loadSplashAd(any(), any(TTAdNative.CSJSplashAdListener.class), anyInt());
        SdkShadow.manager = manager;
        controller = new SplashAdController(activity, events::add);
        Map<String, Object> args = new HashMap<>();
        args.put("posId", "splash"); args.put("timeout", 0.125);
        controller.show(new MethodCall("showSplashAd", args), mock(MethodChannel.Result.class));
    }
    private void openPage() {
        android.content.Intent intent = Shadow.<ShadowActivity>extract(activity).getNextStartedActivity();
        assertNotNull(intent);
        page = Robolectric.buildActivity(SplashAdActivity.class, intent).setup().get();
        root = page.findViewById(android.R.id.content);
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idle();
    }
    @After public void teardown() { if (controller != null) controller.dispose(); SdkShadow.reset(); }
    private CSJSplashAd ad() {
        CSJSplashAd ad = mock(CSJSplashAd.class);
        when(ad.getMediationManager()).thenReturn(mock(MediationSplashManager.class));
        return ad;
    }
    @Test public void waitsForRenderBeforeOpeningPageAndLoadsOnlyOnce() {
        verify(manager).createAdNative(activity);
        assertNotNull(listener);
        assertNull(Shadow.<ShadowActivity>extract(activity).getNextStartedActivity());
        CSJSplashAd ad = ad();
        listener.onSplashLoadSuccess(ad);
        assertNull(Shadow.<ShadowActivity>extract(activity).getNextStartedActivity());
        listener.onSplashRenderSuccess(ad);
        verify(ad, never()).showSplashView(any());
        assertEquals("onAdLoaded", ((Map<?, ?>) events.get(0)).get("action"));
        openPage();
        verify(ad).showSplashView(any());
        verify(loader, times(1)).loadSplashAd(any(), any(TTAdNative.CSJSplashAdListener.class), anyInt());
        listener.onSplashRenderSuccess(ad);
        assertNull(Shadow.<ShadowActivity>extract(activity).getNextStartedActivity());
        verify(ad, times(1)).showSplashView(any());
    }
    @Test public void timeoutBeforePageAttachesDoesNotShowAd() {
        CSJSplashAd ad = ad();
        listener.onSplashRenderSuccess(ad);
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(126));
        openPage();
        assertTrue(page.isFinishing());
        verify(ad, never()).showSplashView(any());
        verify(ad.getMediationManager()).destroy();
    }
    @Test public void hostPauseWhileLoadingPreventsLateLaunch() {
        hostController.pause();
        CSJSplashAd ad = ad();
        listener.onSplashRenderSuccess(ad);
        assertFalse(controller.isActive());
        assertNull(Shadow.<ShadowActivity>extract(activity).getNextStartedActivity());
        verify(ad.getMediationManager()).destroy();
    }
    @Test public void hostPauseForAdPageDoesNotCancelReadyAd() {
        CSJSplashAd ad = ad();
        listener.onSplashRenderSuccess(ad);
        hostController.pause();
        openPage();
        assertTrue(controller.isActive());
        verify(ad).showSplashView(any());
    }
    @Test public void lateRenderAfterDeadlineNeverShowsAd() {
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(126));
        CSJSplashAd ad = ad();
        listener.onSplashRenderSuccess(ad);
        assertFalse(controller.isActive());
        assertNull(Shadow.<ShadowActivity>extract(activity).getNextStartedActivity());
        verify(ad, never()).showSplashView(any());
        verify(ad.getMediationManager()).destroy();
    }
    @Test public void exposureCancelsLoadDeadlineAndCloseFinishesActivity() {
        CSJSplashAd ad = ad();
        doAnswer(invocation -> {
            ViewGroup target = invocation.getArgument(0);
            target.addView(new View(target.getContext()));
            return null;
        }).when(ad).showSplashView(any());
        listener.onSplashRenderSuccess(ad);
        openPage();
        ViewGroup container = (ViewGroup) root.getChildAt(0);
        ArgumentCaptor<CSJSplashAd.SplashAdListener> capture = ArgumentCaptor.forClass(CSJSplashAd.SplashAdListener.class);
        verify(ad).setSplashAdListener(capture.capture());
        capture.getValue().onSplashAdShow(ad);
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertTrue(controller.isActive());
        assertEquals(1, root.getChildCount());
        capture.getValue().onSplashAdClose(ad, 0);
        assertFalse(controller.isActive());
        assertTrue(page.isFinishing());
        assertEquals(1, container.getChildCount());
        verify(ad.getMediationManager(), never()).destroy();
        assertEquals("onAdClosed", ((Map<?, ?>) events.get(events.size() - 1)).get("action"));
        int eventCount = events.size();
        capture.getValue().onSplashAdClose(ad, 0);
        listener.onSplashLoadSuccess(ad);
        listener.onSplashRenderSuccess(ad);
        controller.dispose();
        assertEquals(eventCount, events.size());
        verify(ad.getMediationManager(), never()).destroy();
        page.onDestroy();
        assertEquals(0, container.getChildCount());
        verify(ad.getMediationManager(), times(1)).destroy();
        assertEquals(eventCount, events.size());
    }
    @Test public void unexpectedActivityDestructionReleasesAdImmediately() {
        CSJSplashAd ad = ad();
        listener.onSplashRenderSuccess(ad);
        openPage();
        page.onDestroy();
        assertFalse(controller.isActive());
        verify(ad.getMediationManager(), times(1)).destroy();
        assertEquals("onAdError", ((Map<?, ?>) events.get(events.size() - 1)).get("action"));
    }
    @Test public void disposeFinishesSplashActivity() {
        CSJSplashAd ad = ad();
        listener.onSplashRenderSuccess(ad);
        openPage();
        controller.dispose();
        assertTrue(page.isFinishing());
        assertFalse(controller.isActive());
        verify(ad.getMediationManager()).destroy();
    }
}

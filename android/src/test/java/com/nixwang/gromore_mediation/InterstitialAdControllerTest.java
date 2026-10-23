package com.nixwang.gromore_mediation;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import android.app.Activity;
import android.os.Looper;
import com.bytedance.sdk.openadsdk.TTAdManager;
import com.bytedance.sdk.openadsdk.TTAdNative;
import com.bytedance.sdk.openadsdk.TTAdSdk;
import com.bytedance.sdk.openadsdk.TTFullScreenVideoAd;
import com.bytedance.sdk.openadsdk.mediation.manager.MediationFullScreenManager;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, shadows = SdkShadow.class,
        instrumentedPackages = {"com.bytedance.sdk.openadsdk"})
@LooperMode(LooperMode.Mode.PAUSED)
public class InterstitialAdControllerTest {
    private InterstitialAdController controller;
    private final ArrayList<TTAdNative.FullScreenVideoAdListener> loads = new ArrayList<>();
    private final MethodCall load = new MethodCall("loadInterstitialAd", Collections.singletonMap("posId", "ad"));
    private Activity activity;
    private final ArrayList<Object> events = new ArrayList<>();

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        TTAdNative loader = mock(TTAdNative.class);
        TTAdManager manager = mock(TTAdManager.class);
        when(manager.createAdNative(any())).thenReturn(loader);
        doAnswer(invocation -> { loads.add(invocation.getArgument(1)); return null; })
                .when(loader).loadFullScreenVideoAd(any(), any());
        SdkShadow.manager = manager;
        controller = new InterstitialAdController(activity, events::add);
    }
    @After public void teardown() { if (controller != null) controller.dispose(); SdkShadow.reset(); }
    private TTFullScreenVideoAd readyAd() {
        TTFullScreenVideoAd ad = mock(TTFullScreenVideoAd.class);
        MediationFullScreenManager manager = mock(MediationFullScreenManager.class);
        when(ad.getMediationManager()).thenReturn(manager);
        when(manager.isReady()).thenReturn(true);
        return ad;
    }
    @Test public void waitsForCacheAndCoalescesDuplicateLoads() {
        MethodChannel.Result a = mock(MethodChannel.Result.class);
        MethodChannel.Result b = mock(MethodChannel.Result.class);
        controller.load(load, a); controller.load(load, b);
        assertEquals(1, loads.size());
        TTFullScreenVideoAd ad = readyAd();
        loads.get(0).onFullScreenVideoAdLoad(ad);
        verifyNoInteractions(a, b);
        loads.get(0).onFullScreenVideoCached(ad);
        loads.get(0).onFullScreenVideoCached();
        verify(a, times(1)).success(true);
        verify(b, times(1)).success(true);
        assertTrue(controller.ready("ad"));
        verify(ad.getMediationManager(), never()).destroy();
    }
    @Test public void timedOutCallbackCannotCompleteNewLoad() {
        MethodChannel.Result first = mock(MethodChannel.Result.class);
        controller.load(load, first);
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
        verify(first).success(false);
        MethodChannel.Result retry = mock(MethodChannel.Result.class);
        controller.load(load, retry);
        TTFullScreenVideoAd stale = readyAd();
        loads.get(0).onFullScreenVideoCached(stale);
        verify(stale.getMediationManager()).destroy();
        verifyNoInteractions(retry);
        loads.get(1).onFullScreenVideoCached(readyAd());
        verify(retry).success(true);
    }

    @Test public void loadAndCacheWrappersShareOneAdUntilPresentationCloses() {
        TTFullScreenVideoAd loaded = readyAd();
        TTFullScreenVideoAd cached = mock(TTFullScreenVideoAd.class);
        MediationFullScreenManager manager = loaded.getMediationManager();
        when(cached.getMediationManager()).thenReturn(manager);
        MethodChannel.Result result = mock(MethodChannel.Result.class);
        controller.load(load, result);
        loads.get(0).onFullScreenVideoAdLoad(loaded);
        loads.get(0).onFullScreenVideoCached(cached);
        verify(result).success(true);
        verify(loaded.getMediationManager(), never()).destroy();

        controller.show(load, mock(MethodChannel.Result.class));
        ArgumentCaptor<TTFullScreenVideoAd.FullScreenVideoAdInteractionListener> callbacks =
                ArgumentCaptor.forClass(TTFullScreenVideoAd.FullScreenVideoAdInteractionListener.class);
        verify(cached).setFullScreenVideoAdInteractionListener(callbacks.capture());
        callbacks.getValue().onAdShow();
        assertEquals("onAdExposure", ((Map<?, ?>) events.get(events.size() - 1)).get("action"));
        assertTrue(controller.isPresenting());
        callbacks.getValue().onAdClose();
        assertEquals("onAdClosed", ((Map<?, ?>) events.get(events.size() - 1)).get("action"));
        assertFalse(controller.isPresenting());
        verify(loaded.getMediationManager(), times(1)).destroy();
    }

    @Test public void duplicateCacheAndLoadWrappersCannotDestroyAcceptedAd() {
        TTFullScreenVideoAd loaded = readyAd();
        TTFullScreenVideoAd duplicate = mock(TTFullScreenVideoAd.class);
        MediationFullScreenManager manager = loaded.getMediationManager();
        when(duplicate.getMediationManager()).thenReturn(manager);
        MethodChannel.Result result = mock(MethodChannel.Result.class);
        controller.load(load, result);
        loads.get(0).onFullScreenVideoAdLoad(loaded);
        loads.get(0).onFullScreenVideoCached();
        controller.show(load, mock(MethodChannel.Result.class));
        // New SDK wrappers for the already accepted request are aliases, not stale ads.
        loads.get(0).onFullScreenVideoCached(duplicate);
        loads.get(0).onFullScreenVideoAdLoad(duplicate);
        loads.get(0).onFullScreenVideoCached();
        verify(result, times(1)).success(true);
        verify(loaded.getMediationManager(), never()).destroy();
        assertTrue(controller.isPresenting());
        controller.dispose();
        loads.get(0).onFullScreenVideoCached(duplicate);
        verify(loaded.getMediationManager(), times(1)).destroy();
    }
    @Test public void disposedLoadDoesNotResurrectCache() {
        MethodChannel.Result result = mock(MethodChannel.Result.class);
        controller.load(load, result);
        controller.dispose();
        verify(result).success(false);
        TTFullScreenVideoAd stale = readyAd();
        loads.get(0).onFullScreenVideoCached(stale);
        assertFalse(controller.ready("ad"));
        verify(result, never()).success(true);
        verify(stale.getMediationManager()).destroy();
    }
    @Test public void showConsumesCacheAndRejectsSecondPresentation() {
        TTFullScreenVideoAd ad = readyAd();
        controller.load(load, mock(MethodChannel.Result.class));
        loads.get(0).onFullScreenVideoCached(ad);
        MethodChannel.Result shown = mock(MethodChannel.Result.class);
        controller.show(load, shown);
        verify(shown).success(true);
        assertFalse(controller.ready("ad"));
        verify(ad).showFullScreenVideoAd(activity);
        MethodChannel.Result again = mock(MethodChannel.Result.class);
        controller.show(load, again);
        verify(again).success(false);
        verify(ad, times(1)).showFullScreenVideoAd(activity);
    }
    @Test public void presentationExceptionReleasesNativeOwnership() {
        TTFullScreenVideoAd ad = readyAd();
        controller.load(load, mock(MethodChannel.Result.class));
        loads.get(0).onFullScreenVideoCached(ad);
        doThrow(new IllegalStateException("Activity lost"))
                .when(ad).setFullScreenVideoAdInteractionListener(any());
        MethodChannel.Result result = mock(MethodChannel.Result.class);
        controller.show(load, result);
        verify(result).success(false);
        assertFalse(controller.isPresenting());
        verify(ad.getMediationManager()).destroy();
    }

    @Test public void lateExposureKeepsNativeOwnershipUntilCloseAndOldCallbacksCannotAffectNextAd() {
        TTFullScreenVideoAd first = readyAd();
        controller.load(load, mock(MethodChannel.Result.class));
        loads.get(0).onFullScreenVideoCached(first);
        controller.show(load, mock(MethodChannel.Result.class));
        ArgumentCaptor<TTFullScreenVideoAd.FullScreenVideoAdInteractionListener> callbacks =
                ArgumentCaptor.forClass(TTFullScreenVideoAd.FullScreenVideoAdInteractionListener.class);
        verify(first).setFullScreenVideoAdInteractionListener(callbacks.capture());
        TTFullScreenVideoAd.FullScreenVideoAdInteractionListener old = callbacks.getValue();

        // Dart has stopped waiting at 5s, but the native ad still owns presentation.
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        TTFullScreenVideoAd next = readyAd();
        controller.load(load, mock(MethodChannel.Result.class));
        loads.get(1).onFullScreenVideoCached(next);
        MethodChannel.Result blocked = mock(MethodChannel.Result.class);
        controller.show(load, blocked);
        verify(blocked).success(false);
        assertTrue(controller.ready("ad"));
        old.onAdShow();
        assertTrue(controller.isPresenting());
        assertEquals("onAdExposure", ((Map<?, ?>) events.get(events.size() - 1)).get("action"));
        old.onAdClose();
        assertFalse(controller.isPresenting());

        MethodChannel.Result shown = mock(MethodChannel.Result.class);
        controller.show(load, shown);
        verify(shown).success(true);
        int eventCount = events.size();
        old.onAdShow();
        old.onAdClose();
        old.onVideoComplete();
        assertEquals(eventCount, events.size());
        assertTrue(controller.isPresenting());
        verify(next.getMediationManager(), never()).destroy();
        verify(first.getMediationManager(), times(1)).destroy();
    }

    @Test public void expiredCacheIsRejectedAndCanBeReloaded() {
        TTFullScreenVideoAd expired = readyAd();
        controller.load(load, mock(MethodChannel.Result.class));
        loads.get(0).onFullScreenVideoCached(expired);
        when(expired.getMediationManager().isReady()).thenReturn(false);
        assertFalse(controller.ready("ad"));
        MethodChannel.Result result = mock(MethodChannel.Result.class);
        controller.show(load, result);
        verify(result).success(false);
        verify(expired, never()).showFullScreenVideoAd(any());
        verify(expired.getMediationManager()).destroy();
        controller.load(load, mock(MethodChannel.Result.class));
        loads.get(1).onFullScreenVideoCached(readyAd());
        assertTrue(controller.ready("ad"));
    }

    @Test public void disposeWhilePresentingInvalidatesAllOldCallbacks() {
        TTFullScreenVideoAd ad = readyAd();
        controller.load(load, mock(MethodChannel.Result.class));
        loads.get(0).onFullScreenVideoCached(ad);
        controller.show(load, mock(MethodChannel.Result.class));
        ArgumentCaptor<TTFullScreenVideoAd.FullScreenVideoAdInteractionListener> callbacks =
                ArgumentCaptor.forClass(TTFullScreenVideoAd.FullScreenVideoAdInteractionListener.class);
        verify(ad).setFullScreenVideoAdInteractionListener(callbacks.capture());
        controller.dispose();
        int eventCount = events.size();
        callbacks.getValue().onAdShow();
        callbacks.getValue().onAdClose();
        assertEquals(eventCount, events.size());
        assertFalse(controller.isPresenting());
        verify(ad.getMediationManager(), times(1)).destroy();
    }

}

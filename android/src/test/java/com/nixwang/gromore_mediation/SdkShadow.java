package com.nixwang.gromore_mediation;

import com.bytedance.sdk.openadsdk.TTAdManager;
import com.bytedance.sdk.openadsdk.TTAdSdk;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/** Replaces SDK startup; tests drive only its public listener interfaces. */
@Implements(value = TTAdSdk.class, isInAndroidSdk = false)
public class SdkShadow {
    static TTAdManager manager;
    @Implementation protected static TTAdManager getAdManager() { return manager; }
    @Resetter public static void reset() { manager = null; }
}

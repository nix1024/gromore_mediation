library;

import 'package:flutter/services.dart';

part 'src/ad_event.dart';
part 'src/privacy_config.dart';

/// Android and HarmonyOS GroMore bridge. Call [initAd] only after obtaining consent.
/// SDK initialization is process-wide; the first successful configuration wins.
abstract final class GromoreMediation {
  static const _methods = MethodChannel('gromore_mediation');
  static const _events = EventChannel('gromore_mediation/events');

  /// Shared broadcast stream. Subscribe before requesting any ads and cancel
  /// subscriptions when the owning service is disposed.
  static final Stream<AdEvent> events = _events.receiveBroadcastStream().map(
        (data) => AdEvent.fromMap(Map<Object?, Object?>.from(data as Map)),
      );

  static Future<bool> initAd(
    String appId, {
    bool isDebug = false,
    int themeStatus = 0,
    PrivacyConfig? privacyConfig,
    // JSON exported from GroMore for this appId, loaded by the host.
    String? localConfig,
  }) async {
    _placement(appId);
    if (themeStatus != 0 && themeStatus != 1) {
      throw ArgumentError.value(themeStatus, 'themeStatus', 'Expected 0 or 1');
    }
    return await _methods.invokeMethod<bool>('initAd', {
          'appId': appId,
          'isDebug': isDebug,
          'themeStatus': themeStatus,
          if (localConfig != null) 'localConfig': localConfig,
          if (privacyConfig != null) 'privacyConfig': privacyConfig.toMap(),
        }) ??
        false;
  }

  /// Loads and displays a splash using the native SDK presentation.
  /// True means the request started, not that an impression occurred.
  /// [timeout] is the maximum time in seconds to reach exposure.
  static Future<bool> showSplashAd(String posId, {double timeout = 3.5}) async {
    _placement(posId);
    if (!timeout.isFinite || timeout <= 0 || timeout > 2147483) {
      throw ArgumentError.value(
        timeout,
        'timeout',
        'Expected positive seconds',
      );
    }
    return await _methods.invokeMethod<bool>('showSplashAd', {
          'posId': posId,
          'timeout': timeout,
        }) ??
        false;
  }

  /// Completes only when the concrete ad has been cached and is ready.
  /// Concurrent requests for the same placement share the native load.
  static Future<bool> loadInterstitialAd(
    String posId, {
    int orientation = 1,
    bool isMuted = false,
  }) async {
    _placement(posId);
    if (orientation != 1 && orientation != 2) {
      throw ArgumentError.value(orientation, 'orientation', 'Expected 1 or 2');
    }
    return await _methods.invokeMethod<bool>('loadInterstitialAd', {
          'posId': posId,
          'orientation': orientation,
          'isMuted': isMuted,
        }) ??
        false;
  }

  /// Checks the native SDK, including cache expiration.
  static Future<bool> isInterstitialAdReady(String posId) async {
    _placement(posId);
    return await _methods.invokeMethod<bool>('isInterstitialAdReady', {
          'posId': posId,
        }) ??
        false;
  }

  /// Consumes a cached ad. False means no ready ad or rejected presentation.
  /// True means presentation started; observe events for exposure and close.
  static Future<bool> showInterstitialAd(String posId) async {
    _placement(posId);
    return await _methods.invokeMethod<bool>('showInterstitialAd', {
          'posId': posId,
        }) ??
        false;
  }

  /// Android requires tools-release.aar in debug builds only.
  /// HarmonyOS provides the CSJ test tool in debug hosts only.
  /// Returns false if unavailable or the host is not debuggable.
  static Future<bool> launchTestTools() async =>
      await _methods.invokeMethod<bool>('launchTestTools') ?? false;

  /// Releases native ads and pending loads. Does not uninitialize the SDK.
  static Future<void> dispose() => _methods.invokeMethod<void>('dispose');

  static void _placement(String value) {
    if (value.trim().isEmpty) {
      throw ArgumentError.value(value, 'id', 'Required');
    }
  }
}

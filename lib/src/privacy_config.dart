part of '../gromore_mediation.dart';

/// Optional data-access controls. These do not replace the app's consent UI.
/// Defaults deny sensitive device data; explicitly enable only what the host
/// has disclosed and is authorized to collect. No runtime permissions requested.
class PrivacyConfig {
  const PrivacyConfig({
    this.allowLocation = false,
    this.allowPhoneState = false,
    this.allowWifiState = false,
    this.allowExternalStorage = false,
    this.allowAndroidId = false,
    this.allowOaid = false,
    this.allowAppList = false,
    this.personalizedAds = true,
  });
  final bool allowLocation;
  final bool allowPhoneState;
  final bool allowWifiState;
  final bool allowExternalStorage;
  final bool allowAndroidId;
  final bool allowOaid;
  final bool allowAppList;
  final bool personalizedAds;

  Map<String, bool> toMap() => {
        'allowLocation': allowLocation,
        'allowPhoneState': allowPhoneState,
        'allowWifiState': allowWifiState,
        'allowExternalStorage': allowExternalStorage,
        'allowAndroidId': allowAndroidId,
        'allowOaid': allowOaid,
        'allowAppList': allowAppList,
        'personalizedAds': personalizedAds,
      };
}

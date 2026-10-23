part of '../gromore_mediation.dart';

abstract final class AdEventAction {
  static const onAdLoaded = 'onAdLoaded';
  static const onAdExposure = 'onAdExposure';
  static const onAdClicked = 'onAdClicked';
  static const onAdClosed = 'onAdClosed';
  static const onAdComplete = 'onAdComplete';
  static const onAdSkip = 'onAdSkip';
  static const onAdError = 'onAdError';
}

class AdEvent {
  const AdEvent({required this.posId, required this.action, this.adId});
  final String posId;
  final String action;
  final int? adId;

  factory AdEvent.fromMap(Map<Object?, Object?> map) {
    final posId = map['posId'] as String;
    final action = map['action'] as String;
    if (action == AdEventAction.onAdError) {
      return AdErrorEvent(
        posId: posId,
        errCode: (map['errCode'] as num).toInt(),
        errMsg: map['errMsg'] as String?,
      );
    }
    return AdEvent(posId: posId, action: action);
  }
}

class AdErrorEvent extends AdEvent {
  const AdErrorEvent({required super.posId, required this.errCode, this.errMsg})
      : super(action: AdEventAction.onAdError);
  final int errCode;
  final String? errMsg;
}

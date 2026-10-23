import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gromore_mediation/gromore_mediation.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('gromore_mediation');
  const events = MethodChannel('gromore_mediation/events');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    messenger.setMockMethodCallHandler(events, null);
  });

  test('loading waits for native cache completion rather than method dispatch',
      () async {
    final cache = Completer<bool>();
    messenger.setMockMethodCallHandler(channel, (call) => cache.future);
    var completed = false;
    final load = GromoreMediation.loadInterstitialAd('placement').then((value) {
      completed = true;
      return value;
    });
    await Future<void>.delayed(Duration.zero);
    expect(completed, false);
    cache.complete(true);
    expect(await load, true);
  });

  test(
      'cache miss and native failures are not reported as presentation success',
      () async {
    messenger.setMockMethodCallHandler(channel, (_) async => false);
    expect(await GromoreMediation.showInterstitialAd('placement'), false);
    messenger.setMockMethodCallHandler(channel, (_) async {
      throw PlatformException(code: 'activity_unavailable');
    });
    await expectLater(GromoreMediation.showInterstitialAd('placement'),
        throwsA(isA<PlatformException>()));
  });

  test('initialization preserves defaults and forwards explicit configuration',
      () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return true;
    });
    await GromoreMediation.initAd('app');
    expect(calls.last.arguments.containsKey('privacyConfig'), false);
    expect(calls.last.arguments.containsKey('localConfig'), false);
    const localConfig = '{"cypher":4,"message":"exported-config"}';
    await GromoreMediation.initAd('app',
        localConfig: localConfig,
        privacyConfig: const PrivacyConfig(allowOaid: true));
    expect(calls.last.arguments['localConfig'], localConfig);
    expect(calls.last.arguments['privacyConfig']['allowOaid'], true);
    expect(calls.last.arguments['privacyConfig']['allowLocation'], false);
  });

  test(
      'splash forwards fractional remaining deadline without rounding to seconds',
      () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'showSplashAd');
      expect(call.arguments, {'posId': 'splash', 'timeout': 0.125});
      return true;
    });
    expect(await GromoreMediation.showSplashAd('splash', timeout: 0.125), true);
    await expectLater(GromoreMediation.showSplashAd('splash', timeout: 0),
        throwsArgumentError);
    await expectLater(
        GromoreMediation.showSplashAd('splash', timeout: double.nan),
        throwsArgumentError);
  });

  test('native cache readiness is queried instead of keeping a Dart cache flag',
      () async {
    var ready = true;
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'isInterstitialAdReady');
      return ready;
    });
    expect(await GromoreMediation.isInterstitialAdReady('ad'), true);
    ready = false;
    expect(await GromoreMediation.isInterstitialAdReady('ad'), false);
  });

  test('multiple Dart listeners share one native subscription and cancellation',
      () async {
    final calls = <String>[];
    messenger.setMockMethodCallHandler(events, (call) async {
      calls.add(call.method);
      return null;
    });
    final first = <AdEvent>[];
    final second = <AdEvent>[];
    final a = GromoreMediation.events.listen(first.add);
    final b = GromoreMediation.events.listen(second.add);
    await Future<void>.delayed(Duration.zero);
    expect(calls, ['listen']);
    await messenger.handlePlatformMessage(
      'gromore_mediation/events',
      const StandardMethodCodec().encodeSuccessEnvelope({
        'posId': 'ad',
        'action': 'onAdError',
        'errCode': 42,
        'errMsg': 'No fill',
      }),
      (_) {},
    );
    await Future<void>.delayed(Duration.zero);
    expect(first.single, isA<AdErrorEvent>());
    expect((second.single as AdErrorEvent).errCode, 42);
    await a.cancel();
    expect(calls, ['listen']);
    await b.cancel();
    expect(calls, ['listen', 'cancel']);
  });
}

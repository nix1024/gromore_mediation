import {
  AbilityAware, AbilityPluginBinding, EventChannel, EventSink, FlutterPlugin,
  FlutterPluginBinding, MethodCall, MethodCallHandler, MethodChannel, MethodResult, StreamHandler
} from '@ohos/flutter_ohos';
import { CSJAdTestTool } from '@csj/testtool';
import { common } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import {
  AdSlotBuilder, CSJAdSdk, CSJCustomPrivacyController, CSJFullAd, CSJSplashAd,
  CSJSplashAdLoadParam, MediationAdInfo, MediationConfigBuilder, SDKConfigBuilder
} from '@csj/openadsdk';

class PrivacyController extends CSJCustomPrivacyController {
  private config: Map<string, Object>;
  constructor(config: Map<string, Object>) { super(); this.config = config; }
  isCanUseAppTrackingConsent(): boolean { return this.config.get('allowOaid') === true; }
  isCanUseLocation(): boolean { return this.config.get('allowLocation') === true; }
  isCanUseWifiState(): boolean { return this.config.get('allowWifiState') === true; }
}

class InterstitialRequest {
  ad: CSJFullAd | null = null;
  results: MethodResult[] = [];
  timer: number = -1;
  loaded: boolean = false;
  exposed: boolean = false;
  posId: string;
  constructor(posId: string) { this.posId = posId; }
}

class SplashRequest {
  ad: CSJSplashAd | null = null;
  timer: number = -1;
  exposed: boolean = false;
  presenting: boolean = false;
  posId: string;
  constructor(posId: string) { this.posId = posId; }
}

/** No SDK initialization occurs until the host explicitly calls initAd. */
export default class GromoreMediationPlugin implements FlutterPlugin, AbilityAware,
  MethodCallHandler, StreamHandler {
  private methods: MethodChannel | null = null;
  private events: EventChannel | null = null;
  private sink: EventSink | null = null;
  private context: common.UIAbilityContext | null = null;
  private stage: window.WindowStage | null = null;
  private cache: Map<string, InterstitialRequest> = new Map();
  private showing: InterstitialRequest | null = null;
  private splash: SplashRequest | null = null;
  private static appId: string | null = null;
  private static initializing: Promise<boolean> | null = null;

  getUniqueClassName(): string { return 'GromoreMediationPlugin'; }

  onAttachedToEngine(binding: FlutterPluginBinding): void {
    this.methods = new MethodChannel(binding.getBinaryMessenger(), 'gromore_mediation');
    this.methods.setMethodCallHandler(this);
    this.events = new EventChannel(binding.getBinaryMessenger(), 'gromore_mediation/events');
    this.events.setStreamHandler(this);
  }

  onDetachedFromEngine(_binding: FlutterPluginBinding): void {
    this.disposeAds();
    this.methods?.setMethodCallHandler(null);
    this.events?.setStreamHandler(null);
    this.methods = null;
    this.events = null;
    this.sink = null;
    this.context = null;
    this.stage = null;
  }

  onAttachedToAbility(binding: AbilityPluginBinding): void {
    this.context = binding.getAbility().context;
    this.stage = this.context.windowStage;
  }

  onDetachedFromAbility(): void {
    this.disposeAds();
    this.context = null;
    this.stage = null;
  }

  onListen(_args: Object, sink: EventSink): void { this.sink = sink; }
  onCancel(_args: Object): void { this.sink = null; }

  onMethodCall(call: MethodCall, result: MethodResult): void {
    const args = call.args as Map<string, Object>;
    try {
      switch (call.method) {
        case 'initAd':
          this.initAd(args).then((ready: boolean) => result.success(ready))
            .catch(() => result.error('init_failed', 'GroMore initialization failed', null));
          break;
        case 'loadInterstitialAd': this.loadInterstitial(args, result); break;
        case 'isInterstitialAdReady': result.success(this.isReady(args.get('posId') as string)); break;
        case 'showInterstitialAd': result.success(this.showInterstitial(args.get('posId') as string)); break;
        case 'showSplashAd': result.success(this.showSplash(args)); break;
        case 'launchTestTools':
          if (this.context?.applicationInfo.debug && this.stage) {
            CSJAdTestTool.show(this.stage);
            result.success(true);
          } else { result.success(false); }
          break;
        case 'dispose': this.disposeAds(); result.success(null); break;
        default: result.notImplemented();
      }
    } catch (_) {
      result.error('gromore_error', 'Native GroMore operation failed', null);
    }
  }

  private async initAd(args: Map<string, Object>): Promise<boolean> {
    const appId = args.get('appId') as string;
    if (!this.context || !appId?.trim()) { return false; }
    if (GromoreMediationPlugin.appId !== null && GromoreMediationPlugin.appId !== appId) {
      return false;
    }
    if (CSJAdSdk.isSdkReady()) { return true; }
    if (GromoreMediationPlugin.initializing) { return GromoreMediationPlugin.initializing; }
    const mediation = new MediationConfigBuilder().useMediation(true);
    const localConfig = args.get('localConfig') as string | undefined;
    if (localConfig) { mediation.setCustomLocalConfig(localConfig); }
    const builder = new SDKConfigBuilder().appId(appId).appName('GroMore')
      .debug(args.get('isDebug') === true).allowShowNotify(false)
      .setThemeStatus((args.get('themeStatus') as number) ?? 0)
      .setMediationConfig(mediation.build());
    const privacy = args.get('privacyConfig') as Map<string, Object> | undefined;
    if (privacy) { builder.customController(new PrivacyController(privacy)); }
    CSJAdSdk.init(this.context, builder.build());
    GromoreMediationPlugin.appId = appId;
    // Keep the process-wide promise until the SDK actually settles. A host
    // timeout must not permit overlapping start calls with another config.
    const starting = CSJAdSdk.start().then((status) => status.code === 0);
    starting.then(() => { GromoreMediationPlugin.initializing = null; })
      .catch(() => { GromoreMediationPlugin.initializing = null; });
    let timer = -1;
    const timeout = new Promise<boolean>((resolve) => { timer = setTimeout(() => resolve(false), 30000); });
    const bounded = Promise.race([starting, timeout]);
    GromoreMediationPlugin.initializing = bounded;
    try { return await bounded; }
    finally { clearTimeout(timer); }
  }

  private send(posId: string, action: string, code?: number, message?: string): void {
    const event = new Map<string, Object>();
    event.set('posId', posId);
    event.set('action', action);
    if (code !== undefined) {
      event.set('errCode', code);
      event.set('errMsg', message ?? 'GroMore error');
    }
    this.sink?.success(event);
  }

  private error(posId: string, code: number, message: string): void {
    this.send(posId, 'onAdError', code, message);
  }

  private adReady(ad: CSJFullAd): boolean {
    const info = ad.getMediationAdInfo();
    return info instanceof MediationAdInfo && info.isReady();
  }

  private isReady(posId: string): boolean {
    const request = this.cache.get(posId);
    if (!request?.loaded || !request.ad) { return false; }
    if (this.adReady(request.ad)) { return true; }
    this.cache.delete(posId);
    this.releaseInterstitial(request);
    return false;
  }

  private finishLoad(request: InterstitialRequest, ready: boolean): void {
    clearTimeout(request.timer);
    request.timer = -1;
    const results = request.results;
    request.results = [];
    results.forEach((result: MethodResult) => result.success(ready));
  }

  private releaseInterstitial(request: InterstitialRequest): void {
    this.finishLoad(request, false);
    // CSJFullAd 7.6.4 exposes no destroy API. Drop references; identity checks
    // below prevent callbacks from a detached/expired request reaching Dart.
    request.ad = null;
  }

  private loadInterstitial(args: Map<string, Object>, result: MethodResult): void {
    const posId = args.get('posId') as string;
    if (!this.context || !CSJAdSdk.isSdkReady() || this.showing?.posId === posId) {
      result.success(false); return;
    }
    if (args.get('isMuted') === true) {
      result.error('unsupported_option', 'OHOS SDK 7.6.4 does not expose interstitial mute control', null);
      return;
    }
    if (this.isReady(posId)) { result.success(true); return; }
    const pending = this.cache.get(posId);
    if (pending) { pending.results.push(result); return; }
    const request = new InterstitialRequest(posId);
    request.results.push(result);
    this.cache.set(posId, request);
    request.timer = setTimeout(() => this.failLoad(request, -1, 'Interstitial load timed out'), 30000);
    try {
      CSJAdSdk.getAdCreator().loadFullAd(new AdSlotBuilder().setCodeId(posId)
        .setOrientation((args.get('orientation') as number) ?? 1).build(), {
        onAdLoaded: (_ad: CSJFullAd) => {
          // Metadata load is not cache completion. Resolve only onAdCached.
        },
        onAdCached: (ad: CSJFullAd) => {
          if (this.cache.get(posId) !== request || request.loaded) { return; }
          if (!this.adReady(ad)) { this.failLoad(request, -2, 'Cached interstitial is not ready'); return; }
          request.ad = ad;
          request.loaded = true;
          this.finishLoad(request, true);
          this.send(posId, 'onAdLoaded');
        },
        onError: (code: number, message: string) => this.failLoad(request, code, message)
      });
    } catch (_) { this.failLoad(request, -3, 'Interstitial load failed'); }
  }

  private failLoad(request: InterstitialRequest, code: number, message: string): void {
    if (this.cache.get(request.posId) !== request) { return; }
    this.cache.delete(request.posId);
    this.releaseInterstitial(request);
    this.error(request.posId, code, message);
  }

  private showInterstitial(posId: string): boolean {
    if (!this.stage || this.showing || this.splash || !this.isReady(posId)) { return false; }
    const request = this.cache.get(posId)!;
    const ad = request.ad!;
    this.cache.delete(posId);
    this.showing = request;
    const send = (action: string): void => {
      if (this.showing === request) { this.send(posId, action); }
    };
    try {
      ad.setInteractionListener({
        onShow: () => {
          if (this.showing !== request || request.exposed) { return; }
          request.exposed = true;
          clearTimeout(request.timer);
          send('onAdExposure');
        },
        onClick: () => send('onAdClicked'),
        onSkip: () => send('onAdSkip'),
        onComplete: () => send('onAdComplete'),
        onClose: () => {
          if (this.showing !== request) { return; }
          this.showing = null;
          this.releaseInterstitial(request);
          this.send(posId, 'onAdClosed');
        }
      });
      request.timer = setTimeout(() => {
        if (this.showing !== request || request.exposed) { return; }
        this.showing = null;
        this.releaseInterstitial(request);
        this.error(posId, -4, 'Interstitial exposure timed out');
      }, 5000);
      ad.showFullAd(this.stage);
      return true;
    } catch (_) {
      this.showing = null;
      this.releaseInterstitial(request);
      this.error(posId, -3, 'Interstitial presentation failed');
      return false;
    }
  }

  private showSplash(args: Map<string, Object>): boolean {
    if (!this.context || !this.stage || !CSJAdSdk.isSdkReady() || this.splash || this.showing) { return false; }
    const posId = args.get('posId') as string;
    const timeoutMs = (args.get('timeout') as number) * 1000;
    const request = new SplashRequest(posId);
    this.splash = request;
    const fail = (code: number, message: string): void => {
      if (this.splash !== request) { return; }
      this.releaseSplash();
      this.error(posId, code, message);
    };
    request.timer = setTimeout(() => fail(-1, 'Splash exposure timed out'), timeoutMs);
    try {
      const mainWindow = this.stage.getMainWindowSync();
      const uiContext = mainWindow.getUIContext();
      const rect = mainWindow.getWindowProperties().windowRect;
      const slot = new AdSlotBuilder().setCodeId(posId)
        .setAcceptSize(uiContext.px2vp(rect.width), uiContext.px2vp(rect.height)).build();
      const present = (ad: CSJSplashAd): void => {
        if (this.splash !== request || request.presenting || !this.stage) { return; }
        request.presenting = true;
        request.ad = ad;
        try {
          ad.setInteractionListener({
            onDidShow: () => {
              if (this.splash !== request || request.exposed) { return; }
              request.exposed = true;
              clearTimeout(request.timer);
              this.send(posId, 'onAdExposure');
            },
            onDidClick: () => { if (this.splash === request) { this.send(posId, 'onAdClicked'); } },
            onDidClose: () => {
              if (this.splash !== request) { return; }
              this.releaseSplash();
              this.send(posId, 'onAdClosed');
            },
            onVideoDidPlayFinish: () => {
              if (this.splash === request) { this.send(posId, 'onAdComplete'); }
            },
            onVideoDidPlayFail: (code: number, message: string) => fail(code, message)
          });
          ad.showSplashAd(this.stage);
        } catch (_) { fail(-3, 'Splash presentation failed'); }
      };
      CSJAdSdk.getAdCreator().loadSplashAd(new CSJSplashAdLoadParam(slot, {
        onAdLoaded: (_ad: CSJSplashAd) => {
          if (this.splash === request) { this.send(posId, 'onAdLoaded'); }
        },
        onRenderSuccess: present,
        onError: fail,
        onRenderFail: fail
      }, uiContext, timeoutMs));
      return true;
    } catch (_) { fail(-3, 'Splash load failed'); return false; }
  }

  private releaseSplash(): void {
    if (this.splash) { clearTimeout(this.splash.timer); this.splash.ad = null; }
    this.splash = null;
  }

  private disposeAds(): void {
    this.cache.forEach((request: InterstitialRequest) => {
      this.releaseInterstitial(request);
      this.error(request.posId, -5, 'Ability detached or ads disposed');
    });
    this.cache.clear();
    if (this.showing) {
      const request = this.showing;
      this.showing = null;
      this.releaseInterstitial(request);
      this.error(request.posId, -5, 'Ability detached or ads disposed');
    }
    if (this.splash) {
      const posId = this.splash.posId;
      this.releaseSplash();
      this.error(posId, -5, 'Ability detached or ads disposed');
    }
  }
}

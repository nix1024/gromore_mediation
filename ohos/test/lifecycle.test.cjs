// Executes the actual bridge against SDK/Flutter doubles. The HAP build checks
// ArkTS separately; these tests do not simulate SDK rendering or real ad fills.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript'); // Provided by the HarmonyOS command-line tools.
const source = fs.readFileSync(path.join(__dirname, '../src/main/ets/GromoreMediationPlugin.ets'), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 } }).outputText;

function harness() {
  let timerId = 0;
  const timers = new Map();
  const callbacks = { full: [], splash: [] };
  const events = [];
  class MediationAdInfo { constructor(ready = true) { this.ready = ready; } isReady() { return this.ready; } }
  class Builder {
    appId() { return this; } appName() { return this; } debug() { return this; }
    allowShowNotify() { return this; } setThemeStatus() { return this; }
    setMediationConfig() { return this; } customController() { return this; }
    useMediation() { return this; } setCustomLocalConfig() { return this; }
    setCodeId() { return this; } setOrientation() { return this; }
    setAcceptSize() { return this; } build() { return {}; }
  }
  const sdk = {
    ready: true, starts: 0, isSdkReady() { return this.ready; }, init() {},
    start() { this.starts++; return Promise.resolve({ code: 0 }); },
    getAdCreator() { return {
      loadFullAd(_slot, listener) { callbacks.full.push(listener); },
      loadSplashAd(param) { callbacks.splash.push(param.listener); }
    }; }
  };
  const module = { exports: {} };
  const sdkModule = { CSJAdSdk: sdk, MediationAdInfo, CSJCustomPrivacyController: class {},
    AdSlotBuilder: Builder, MediationConfigBuilder: Builder, SDKConfigBuilder: Builder,
    CSJSplashAdLoadParam: class { constructor(_slot, listener) { this.listener = listener; } }
  };
  vm.runInNewContext(compiled, { exports: module.exports, module,
    require(name) {
      if (name === '@csj/openadsdk') return sdkModule;
      if (name === '@csj/testtool') return { CSJAdTestTool: { show() {} } };
      if (name === '@ohos/flutter_ohos') return {};
      throw new Error(name);
    },
    setTimeout(fn, ms) { const id = ++timerId; timers.set(id, { fn, ms }); return id; },
    clearTimeout(id) { timers.delete(id); }
  });
  const plugin = new module.exports.default();
  const stage = { getMainWindowSync() { return {
    getUIContext() { return { px2vp: value => value }; },
    getWindowProperties() { return { windowRect: { width: 400, height: 800 } }; }
  }; } };
  plugin.onAttachedToAbility({ getAbility: () => ({ context: { windowStage: stage, applicationInfo: { debug: true } } }) });
  plugin.onListen(null, { success(event) { events.push(Object.fromEntries(event)); } });
  function call(method, args = {}) {
    let complete;
    const value = new Promise(resolve => complete = resolve);
    plugin.onMethodCall({ method, args: new Map(Object.entries(args)) }, {
      success: complete, error: (code, message) => complete({ code, message }),
      notImplemented: () => complete('notImplemented')
    });
    return value;
  }
  function ad() {
    const info = new MediationAdInfo();
    return { info, shows: 0, getMediationAdInfo: () => info,
      setInteractionListener(listener) { this.listener = listener; },
      showFullAd() { this.shows++; }, showSplashAd() { this.shows++; }
    };
  }
  function tick(ms) {
    for (const [id, timer] of [...timers]) {
      if (timer.ms <= ms && timers.has(id)) { timers.delete(id); timer.fn(); }
    }
  }
  return { plugin, call, callbacks, events, sdk, ad, tick, timers };
}

async function cached(h, posId = 'full') {
  const loading = h.call('loadInterstitialAd', { posId });
  const ad = h.ad();
  h.callbacks.full.at(-1).onAdCached(ad);
  assert.equal(await loading, true);
  return ad;
}

test('metadata does not complete loading; concurrent callers share one cached ad', async () => {
  const h = harness();
  let settled = false;
  const one = h.call('loadInterstitialAd', { posId: 'full' }).then(value => { settled = true; return value; });
  const two = h.call('loadInterstitialAd', { posId: 'full' });
  assert.equal(h.callbacks.full.length, 1);
  h.callbacks.full[0].onAdLoaded(h.ad());
  await Promise.resolve();
  assert.equal(settled, false);
  h.callbacks.full[0].onAdCached(h.ad());
  assert.equal(await one, true);
  assert.equal(await two, true);
  assert.equal(await h.call('isInterstitialAdReady', { posId: 'full' }), true);
});

test('SDK cache expiry rejects showing and allows a new load', async () => {
  const h = harness();
  const ad = await cached(h);
  ad.info.ready = false;
  assert.equal(await h.call('showInterstitialAd', { posId: 'full' }), false);
  assert.equal(ad.shows, 0);
  const next = h.call('loadInterstitialAd', { posId: 'full' });
  assert.equal(h.callbacks.full.length, 2);
  h.callbacks.full[1].onError(404, 'no fill');
  assert.equal(await next, false);
});

test('show consumes cache; exposure, video completion and close stay distinct', async () => {
  const h = harness();
  const ad = await cached(h);
  assert.equal(await h.call('showInterstitialAd', { posId: 'full' }), true);
  assert.equal(await h.call('showInterstitialAd', { posId: 'full' }), false);
  assert.equal(await h.call('isInterstitialAdReady', { posId: 'full' }), false);
  ad.listener.onShow(); ad.listener.onShow(); ad.listener.onComplete();
  assert.equal(h.events.filter(event => event.action === 'onAdExposure').length, 1);
  assert.equal(await h.call('showSplashAd', { posId: 'splash', timeout: 3 }), false);
  h.tick(5000); // Exposure cancelled the deadline.
  assert.equal(h.events.some(event => event.action === 'onAdError'), false);
  ad.listener.onClose(); ad.listener.onClose();
  assert.equal(h.events.filter(event => event.action === 'onAdClosed').length, 1);
});

test('load timeout and late callbacks cannot complete a retry', async () => {
  const h = harness();
  const first = h.call('loadInterstitialAd', { posId: 'full' });
  h.tick(30000);
  assert.equal(await first, false);
  const retry = h.call('loadInterstitialAd', { posId: 'full' });
  h.callbacks.full[0].onAdCached(h.ad());
  h.callbacks.full[0].onError(99, 'late');
  assert.equal(await h.call('isInterstitialAdReady', { posId: 'full' }), false);
  h.callbacks.full[1].onAdCached(h.ad());
  assert.equal(await retry, true);
});

test('ability detach resolves pending loads and invalidates cached callbacks', async () => {
  const h = harness();
  const pending = h.call('loadInterstitialAd', { posId: 'full' });
  h.plugin.onDetachedFromAbility();
  assert.equal(await pending, false);
  const before = h.events.length;
  h.callbacks.full[0].onAdCached(h.ad());
  assert.equal(h.events.length, before);
  assert.equal(h.timers.size, 0);
  assert.equal(await h.call('showSplashAd', { posId: 'splash', timeout: 3 }), false);
});

test('splash waits for render, exposes once and releases on close', async () => {
  const h = harness();
  assert.equal(await h.call('showSplashAd', { posId: 'splash', timeout: 3 }), true);
  const ad = h.ad();
  h.callbacks.splash[0].onAdLoaded(ad);
  assert.equal(ad.shows, 0);
  h.callbacks.splash[0].onRenderSuccess(ad);
  h.callbacks.splash[0].onRenderSuccess(ad);
  assert.equal(ad.shows, 1);
  ad.listener.onDidShow(); ad.listener.onDidShow();
  h.tick(3000);
  assert.equal(h.events.filter(event => event.action === 'onAdExposure').length, 1);
  assert.equal(h.events.some(event => event.action === 'onAdError'), false);
  ad.listener.onDidClose();
  assert.equal(await h.call('showSplashAd', { posId: 'splash', timeout: 3 }), true);
});

test('splash exposure deadline suppresses a late rendered ad', async () => {
  const h = harness();
  await h.call('showSplashAd', { posId: 'splash', timeout: 0.125 });
  h.tick(125);
  const ad = h.ad();
  h.callbacks.splash[0].onRenderSuccess(ad);
  assert.equal(ad.shows, 0);
  assert.equal(h.events.filter(event => event.action === 'onAdError').length, 1);
});

test('interstitial show exceptions release presentation ownership', async () => {
  const h = harness();
  const ad = await cached(h);
  ad.showFullAd = () => { throw new Error('SDK failure'); };
  assert.equal(await h.call('showInterstitialAd', { posId: 'full' }), false);
  assert.equal(h.timers.size, 0);
  assert.equal(await h.call('showSplashAd', { posId: 'splash', timeout: 3 }), true);
});

test('initialization coalesces, times out for every caller and locks app ID', async () => {
  const h = harness();
  h.sdk.ready = false;
  let finish;
  h.sdk.start = () => { h.sdk.starts++; return new Promise(resolve => finish = resolve); };
  const one = h.call('initAd', { appId: 'app' });
  const two = h.call('initAd', { appId: 'app' });
  assert.equal(h.sdk.starts, 1);
  h.tick(30000);
  assert.equal(await one, false);
  assert.equal(await two, false);
  assert.equal(await h.call('initAd', { appId: 'another' }), false);
  finish({ code: 0 });
});

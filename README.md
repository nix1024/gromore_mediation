# gromore_mediation

供 Flutter Android / HarmonyOS 应用使用的 GroMore 原生桥接插件，提供 SDK 初始化、开屏广告、插屏广告和测试助手；不依赖 `flutter_gromore_ads` 或 `flutterads-core`。

## 环境

- Android 7.0 / API 24 及以上，Java 8+；插件 compileSdk 36。
- Flutter >= 3.22，Dart >= 3.4。
- 穿山甲融合 SDK `com.pangle.cn:mediation-sdk:7.8.1.4`。
- 单进程 Flutter Activity 接入；不提供 iOS、后台引擎或多进程初始化支持。
- 广点通、快手等 ADN 的 SDK、adapter、Provider 和对应资源由宿主按需配置。

## 接入

在应用的 `pubspec.yaml` 中添加公开 Git 仓库依赖：

```yaml
dependencies:
  gromore_mediation:
    git:
      url: https://github.com/nix1024/gromore_mediation.git
      ref: main
```

正式项目建议将 `ref` 固定为经过验证的完整提交 SHA，并提交 `pubspec.lock`。

本地开发时，可将应用与插件仓库放在同一父目录，使用 path 依赖：

```yaml
dependencies:
  gromore_mediation:
    path: ../gromore_mediation
```

添加依赖后，在应用目录执行 `flutter pub get`。使用 FVM 的项目可将文中的 `flutter` 命令替换为 `fvm flutter`；HarmonyOS 项目需使用支持 OHOS 的 Flutter SDK。

## API

```dart
import 'package:gromore_mediation/gromore_mediation.dart';

// 必须先完成宿主的隐私同意流程；插件不会在进程启动时自动初始化。
final subscription = GromoreMediation.events.listen((event) {
  if (event is AdErrorEvent) {
    print('${event.posId}: ${event.errCode} ${event.errMsg}');
  }
  // onAdExposure 才算曝光；onAdClosed 才结束全屏展示占用。
});
final initialized = await GromoreMediation.initAd(appId, themeStatus: 0);
if (initialized) {
  await GromoreMediation.showSplashAd(splashId, timeout: 3.5);
  if (await GromoreMediation.loadInterstitialAd(interstitialId)) {
    await GromoreMediation.showInterstitialAd(interstitialId);
  }
}
// 所有者释放时：
await subscription.cancel();
await GromoreMediation.dispose();
```

上面展示 API 调用顺序；实际产品应等开屏关闭后，才在业务触发点展示插屏。开屏与插屏不能同时展示。

| 方法 | 语义 |
| --- | --- |
| `initAd` | 等待 SDK start 结果；进程内 App ID 不可切换，首次配置生效；30 秒失败兜底 |
| `showSplashAd` | 立即返回请求是否启动；`timeout` 为秒，可传启动流程剩余预算；真实曝光由事件通知 |
| `loadInterstitialAd` | 等待 SDK 缓存完成且 `isReady` 为真，最多等待 30 秒；同广告位并发请求共用一次加载 |
| `isInterstitialAdReady` | 实时查询原生缓存，包含 SDK 的过期判断 |
| `showInterstitialAd` | 消费该广告位的缓存；不会临时加载；返回 true 仅表示开始展示 |
| `events` | 共享广播流，可取消订阅，按 `posId` 区分广告位 |
| `dispose` | 释放所有广告，结束未完成的加载；SDK 本身不会反初始化 |
| `launchTestTools` | 仅在 Debug 宿主提供 `tools-release.aar` 时可用，否则返回 false |

事件包含 `onAdLoaded`、`onAdExposure`、`onAdClicked`、`onAdClosed`、`onAdComplete`、`onAdSkip`、`onAdError`。视频播放完成/跳过不等于全屏页面已经关闭。

开屏采用独立原生 Activity，避免 Flutter 延迟首帧阻塞广告绘制；失败、超时或 Activity 分离时关闭；曝光后由 SDK 关闭回调结束展示，不强行截断广告。Activity 重建会释放缓存，需要重新加载。插屏迟到回调通过每次请求的身份隔离，不会完成重试请求。

插屏 SDK 的加载和缓存回调可能返回不同的 Java 包装对象，但共享同一原生广告。请求被接受后不销毁其余回调包装对象；由缓存/展示持有者在过期、关闭或销毁时统一释放。

`consumer-rules.pro` 精确忽略 7.8.1.4 可选素材截图插件的 `AdnMaterialWindowCopyUtils` 与 `CopyRequest` 缺类警告；该 SDK 在创建截图请求时捕获 `NoClassDefFoundError`。升级 SDK 时需重新核对这一可选依赖。

## 隐私配置

不传 `privacyConfig` 时使用原生 SDK 默认控制器。可显式传 `PrivacyConfig` 控制位置、手机状态、Wi-Fi、外部存储、Android ID、OAID、应用列表与个性化广告。显式创建的配置默认关闭敏感设备数据访问，个性化广告默认开启。配置不代替用户同意，也不会申请运行时权限。

## 开发与验证

```sh
flutter pub get
flutter analyze
flutter test
```

Android 编译及原生回归测试需在已接入插件的宿主应用目录执行；如应用使用 flavor 或自定义入口，请按应用配置补充参数：

```sh
flutter build apk --debug
cd android
./gradlew :gromore_mediation:testDebugUnitTest
```

真机回归需覆盖：同意前不初始化、开屏曝光/无填充/超时、插屏缓存和过期、重复加载、曝光后关闭、切后台与 Activity 重建、广点通/快手填充及 Debug 测试助手。编译和模拟回调测试不证明真实广告网络已可用。

原生接口参考：[初始化](https://www.csjplatform.com/supportcenter/28659)、[开屏](https://www.csjplatform.com/supportcenter/28660)。

## HarmonyOS

OHOS 插件直接接入 `@csj/openadsdk` 7.6.4，不依赖 FlutterAdsCore。使用支持 OHOS 的 Flutter SDK 与 HarmonyOS API 26 工具链，宿主 minimum API 17。单 Flutter 引擎 / UIAbility 接入。

- 注册不初始化 SDK；宿主取得用户同意后显式调用 `initAd`。同 App ID 并发初始化共用结果，30 秒超时；SDK 未返回时不重叠 start。
- 开屏等待 render success 后通过 WindowStage 展示，不依赖 Flutter 首帧；从请求到曝光有 timeout 兜底，超时后的迟到 render 不展示。
- 插屏 metadata load 不算缓存完成；`onAdCached` 且 `MediationAdInfo.isReady()` 为真才成功。就绪检查与展示前均重新查询 SDK；展示消费缓存。
- 同广告位并发加载合并，加载超时 30 秒；展示曝光超时 5 秒，曝光后等待关闭。Ability 分离 / dispose 清理计时器与待完成调用，并隔离迟到回调。
- SDK 全屏接口没有公开 destroy 方法。插件丢弃引用，无法强制关闭已展示的 SDK 页面；dispose 不反初始化 SDK。
- `PrivacyConfig` 仅映射 `allowOaid`（AppTrackingConsent）、`allowLocation`、`allowWifiState`，其他字段只适用于 Android。未传配置时沿用 SDK 默认控制器。
- SDK 不提供插屏静音接口，`isMuted: true` 返回 `unsupported_option`。
- 保留 `@csj/testtool` 1.0.0 HAR，`launchTestTools` 仅 Debug 宿主可用，Release 返回 false。

在 HarmonyOS 宿主中执行 `flutter build hap --debug --no-codesign` 检查 ArkTS 接入。生命周期测试执行真实桥接代码，替换 SDK / Flutter 与计时器，不调用真实广告网络：

```sh
NODE_PATH="$HARMONY_COMMAND_LINE_TOOLS/hvigor/hvigor/node_modules" node --test ohos/test/lifecycle.test.cjs
```

覆盖并发缓存、metadata/cached 区别、SDK 过期、重复曝光/关闭、超时与重试隔离、Ability 分离、开屏 render/曝光预算、展示异常、初始化合并与超时。真机广告与测试助手仍需独立验证。

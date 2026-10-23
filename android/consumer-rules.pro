# Native SDK and mediation adapters use reflection.
-keep class com.bytedance.sdk.openadsdk.** { *; }
-keep class com.bytedance.msdk.adapter.** { *; }
-keep class com.bytedance.msdk.api.** { *; }
# Optional host-provided debug tool is accessed via reflection.
-keep class com.bytedance.mtesttools.api.TTMediationTestTool { public *; }
-keep interface com.bytedance.mtesttools.api.TTMediationTestTool$ImageCallBack { *; }

# Mediation SDK 7.8.1.4 optionally captures ad material through this plugin.
# Its buildCopyRequest call catches NoClassDefFoundError when it is absent.
-dontwarn com.bytedance.msdk.report.plugin.AdnMaterialWindowCopyUtils
-dontwarn com.bytedance.msdk.report.plugin.AdnMaterialWindowCopyUtils$CopyRequest

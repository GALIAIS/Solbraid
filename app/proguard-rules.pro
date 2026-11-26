# Solbraid Release R8 / ProGuard 保护规则

# 保护所有 WebView @JavascriptInterface 原生桥接类与方法不被 R8 混淆/裁剪
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.galiais.agent.ui.RichInlineRendererKt$* { *; }
-keep class com.galiais.agent.ui.ArtifactStudioSheetKt$* { *; }
-keep class com.galiais.agent.ui.TerminalSheetKt$* { *; }
-keep class com.galiais.agent.ui.LivePreviewSheetKt$* { *; }

# 保留 org.commonmark 扩展与节点类
-keep class org.commonmark.** { *; }

# 保留 FileProvider 与 Manifest 声明组件
-keep class androidx.core.content.FileProvider { *; }
-keep class com.galiais.agent.VmRuntimeService { *; }
-keep class com.galiais.agent.MainActivity { *; }
-keep class com.galiais.agent.GaliaisApplication { *; }
-keep class com.galiais.agent.engine.mobile.SolbraidAccessibilityService { *; }
-keep class com.galiais.agent.engine.mobile.SolbraidNotificationListenerService { *; }
-keep class com.galiais.agent.engine.mobile.SolbraidShizukuUserService { *; }
-keep class com.galiais.agent.engine.mobile.ISolbraidShizukuService** { *; }
-keep class com.galiais.agent.engine.mobile.MobileUseStopReceiver { *; }
-keep class rikka.shizuku.** { *; }
-keep class com.jcraft.jsch.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.jcraft.jsch.**

# WebView app — no JavaScript interfaces are exposed, so default rules are enough.
-keepattributes SourceFile,LineNumberTable
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Modern libxposed API 101/102 R8 rules
-dontwarn io.github.libxposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep class com.synapse.app.xposed.ModernXposedEntry {
    *;
}
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public void onSystemServerStarting(...);
}
-keep class io.github.libxposed.service.** {
    *;
}

# Legacy Xposed API 82 entry fallback (only present in legacy flavor)
-keep class com.synapse.app.xposed.LegacyXposedEntry {
    public <init>();
    public void handleLoadPackage(...);
}

# Keep protocol data classes & enums used by reflection / serialization
-keep class com.synapse.protocol.** {
    *;
}

# Strip verbose debug logs in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

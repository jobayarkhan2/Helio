# Keep our app classes
-keep class com.gamebooster.app.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Material Components
-keep class com.google.android.material.** { *; }

# ViewBinding
-keep class * implements androidx.viewbinding.ViewBinding {
    public static * inflate(...);
    public static * bind(android.view.View);
}

# Suppress warnings for unused platform APIs
-dontwarn java.lang.reflect.**
-dontwarn android.os.**

# Keep Room entities
-keep class com.om.offlineai.data.db.entities.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep JNI bridge class (native methods must never be renamed)
-keep class com.om.offlineai.engine.LlamaEngine { *; }
-keep interface com.om.offlineai.engine.TokenCallback { *; }

# Keep Gson models
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

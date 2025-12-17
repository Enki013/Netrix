# netrix ProGuard Rules
# ===========================

# ==========================================
# KOTLIN COROUTINES & FLOW
# ==========================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# ==========================================
# DATA CLASSES (SharedPreferences, Serialization)
# ==========================================
-keep class com.enki.netrix.data.** { *; }
-keepclassmembers class com.enki.netrix.data.** { *; }

# ==========================================
# VPN SERVICE CLASSES
# ==========================================
-keep class com.enki.netrix.vpn.** { *; }
-keepclassmembers class com.enki.netrix.vpn.** { *; }

# ==========================================
# ANDROID SERVICES (Manifest'te tanımlı)
# ==========================================
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# ==========================================
# COMPOSE
# ==========================================
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ==========================================
# DEBUG - Stack Trace Koruma
# ==========================================
# Crash raporlarında okunabilir stack trace için
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==========================================
# GENEL KOTLIN
# ==========================================
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# --- kotlinx.serialization ---------------------------------------------------
# The compiler plugin generates serializers; keep the generated companions and
# the @Serializable model classes reachable from them.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.xiaozhangben.ledger.**$$serializer { *; }
-keep class com.xiaozhangben.ledger.data.backup.** { *; }

# --- Room --------------------------------------------------------------------
# room-runtime ships consumer rules; these are belt-and-braces for DAO impls.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- Glance / AppWidget ------------------------------------------------------
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class androidx.glance.** { *; }
-dontwarn androidx.glance.**

# --- Notification listener --------------------------------------------------
-keep class * extends android.service.notification.NotificationListenerService { *; }

# --- Misc -------------------------------------------------------------------
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**

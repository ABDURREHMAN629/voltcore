# Keep hidden-API stubs we call reflectively through Shizuku.
-keep class android.app.IActivityManager { *; }
-keep class android.content.pm.IPackageManager { *; }
-keep class android.os.IUserManager { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Shizuku instantiates the user service reflectively in a separate shell-UID process,
# so nothing in this APK references it by name and R8 would otherwise delete it —
# along with the AIDL stub it is bound through.
-keep class com.voltcore.app.core.privileged.ShizukuUserService { *; }
-keep interface com.voltcore.app.core.privileged.IUserService { *; }
-keep class com.voltcore.app.core.privileged.IUserService$* { *; }

# The accessibility service and every manifest-declared component are constructed by
# the framework from their names.
-keep class com.voltcore.app.core.privileged.ForceStopAccessibilityService { *; }
-keep class com.voltcore.app.service.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.voltcore.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.voltcore.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }

-dontwarn org.jetbrains.annotations.**

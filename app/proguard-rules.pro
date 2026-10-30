-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.bitcoindevkit.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
-dontwarn org.bitcoindevkit.**
# strip debug logging
-assumenosideeffects class android.util.Log { public static *** d(...); public static *** v(...); public static *** i(...); }

# Google Mobile Ads SDK
-keep class com.google.android.gms.ads.** { *; }

# ironSource
-keepclassmembers class com.ironsource.sdk.** { public *; }
-keep class com.ironsource.** { *; }
-dontwarn com.ironsource.**

# AppLovin
-keep class com.applovin.** { *; }
-dontwarn com.applovin.**

# Unity Ads
-keep class com.unity3d.ads.** { *; }
-dontwarn com.unity3d.ads.**
-keep class com.unity3d.services.** { *; }
-dontwarn com.unity3d.services.**

# InMobi
-keep class com.inmobi.** { *; }
-dontwarn com.inmobi.**
-keep class com.squareup.picasso.** { *; }

# Vungle (Liftoff)
-keep class com.vungle.** { *; }
-dontwarn com.vungle.**

# Mintegral
-keepattributes Signature
-keep class com.mbridge.** { *; }
-dontwarn com.mbridge.**

# Pangle (ByteDance)
-keep class com.bytedance.sdk.** { *; }
-dontwarn com.bytedance.sdk.**

# AdColony
-keep class com.adcolony.** { *; }
-dontwarn com.adcolony.**

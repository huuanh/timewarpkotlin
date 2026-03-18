---
name: android-kotlin
description: "Android Kotlin native development skill. USE FOR: creating Activities, Fragments, layouts XML, drawables, adapters, ViewBinding, CameraX, OpenGL, AdMob ads integration, In-App Billing, RecyclerView, navigation, manifest registration, Gradle dependencies, ProGuard rules, Android resource management. DO NOT USE FOR: React Native, Flutter, iOS, web development."
argument-hint: "Describe the Android feature or component to build"
---

# Android Kotlin Native Development

## When to Use
- Creating or modifying Android Activities, Fragments, Services
- Building XML layouts and drawable resources
- Setting up RecyclerView with adapters
- Integrating AdMob (banner, interstitial, native, app open ads)
- Implementing In-App Billing (subscriptions, one-time purchases)
- CameraX + OpenGL ES rendering pipeline
- Managing Gradle dependencies and build configuration
- Writing ProGuard/R8 keep rules
- Registering components in AndroidManifest.xml

## Design System
- Dark theme: background `#1A1A1A`, camera/player `#000000`
- Primary accent: `#FFC107` (yellow) for buttons, active states, FAB
- Text on yellow: `#1A1A1A` (dark)
- Text active on dark: `#FFFFFF`
- Text inactive: `#80FFFFFF`
- Overlay: `#66000000`
- Corner radius: buttons 24dp, pills 17-20dp, cards 12dp

## Naming Conventions
- Drawable backgrounds: `bg_` prefix (`bg_button_primary`, `bg_circle_button`)
- Drawable icons: `ic_` prefix (`ic_back`, `ic_camera`, `ic_home`)
- Layouts: `activity_`, `item_`, `fragment_`, `dialog_` prefixes
- Activity class: `XxxActivity.kt` paired with `activity_xxx.xml`
- IDs: camelCase (`btnCamera`, `rvVideos`, `tvTitle`, `imgThumbnail`)

## Activity Creation Procedure
1. Create layout XML in `res/layout/activity_xxx.xml`
2. Create Activity class in appropriate `ui/` subfolder
3. Use ViewBinding: `ActivityXxxBinding.inflate(layoutInflater)`
4. Register in `AndroidManifest.xml`:
   ```xml
   <activity
       android:name=".ui.xxx.XxxActivity"
       android:exported="false"
       android:screenOrientation="portrait"
       android:theme="@style/Theme.NativeCamera.Fullscreen" />
   ```
5. Build and verify with `gradlew assembleDebug`

## Adapter Creation Procedure
1. Create item layout `res/layout/item_xxx.xml`
2. Create adapter class in `adapter/` subfolder
3. Use ViewBinding in ViewHolder: `ItemXxxBinding.inflate()`
4. Use `bindingAdapterPosition` (NOT deprecated `adapterPosition`)
5. Pass click lambda: `(Item) -> Unit`

## Drawable Creation Rules
- Vector drawables: 24dp viewport, white fill `#FFFFFF`
- Shape drawables: use `<shape>` with `<solid>`, `<corners>`, `<gradient>`
- Gradients: top `#CC000000` → transparent, bottom transparent → `#CC000000`
- All drawables flat in `res/drawable/` (no subfolders)

## Common Back Button Pattern
```xml
<ImageView
    android:id="@+id/btnBack"
    android:layout_width="44dp"
    android:layout_height="44dp"
    android:layout_marginStart="16dp"
    android:layout_marginTop="44dp"
    android:background="@drawable/bg_circle_button"
    android:contentDescription="Back"
    android:padding="10dp"
    android:scaleType="centerInside"
    android:src="@drawable/ic_back" />
```

## Ad Integration Pattern
```kotlin
// Before navigation with interstitial
AdManager.frequencyController.recordAction()
AdManager.showInterstitialIfReady(activity) {
    // onDismiss callback — navigate here
}

// App Open Ad with callback
AdManager.appOpenAdManager.showIfAvailable(activity) {
    // onDismiss callback
}
```

## Video Playback Pattern
```kotlin
val videoUri = Uri.parse("android.resource://$packageName/$rawResId")
binding.videoView.setVideoURI(videoUri)
binding.videoView.setOnPreparedListener { mp ->
    mp.isLooping = true
    binding.videoView.start()
}
```

## Thumbnail Extraction Pattern
```kotlin
// For raw resources — use file descriptor, NOT URI
val afd = context.resources.openRawResourceFd(rawResId)
val retriever = MediaMetadataRetriever()
retriever.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
val bitmap = retriever.getFrameAtTime(1_000_000)
retriever.release()
afd.close()
```

## Build & Dependencies
- Build command: `.\gradlew.bat assembleDebug`
- Clean build if stale R class errors: `.\gradlew.bat clean assembleDebug`
- Add dependencies in `app/build.gradle.kts`
- Add custom Maven repos in `settings.gradle.kts` with `content{}` filters
- ProGuard rules in `app/proguard-rules.pro`

## Checklist Before Done
1. No compile errors (`get_errors`)
2. Build successful (`gradlew assembleDebug`)
3. No deprecation warnings (fix proactively)
4. Activity registered in Manifest
5. All drawables referenced exist

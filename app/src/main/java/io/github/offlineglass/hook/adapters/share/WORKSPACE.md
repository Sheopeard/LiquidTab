# Share (`com.hengye.share`)

- Verified on APK `versionName=3.9.6`, `versionCode=925`, `targetSdk=29` (AndResGuard-obfuscated resource names).
- Main activity: `com.hengye.share.module.status.StatusActivity`.
- Layout hierarchy: `HomeDrawerLayout` -> `CoordinatorLayout` containing feed `LinearLayout`, `AnimatedFloatingActionButton`, and `CustomBottomBar` (`BottomBar`) -> `NavigationView` drawer.
- Adapter locates `CustomBottomBar` and its horizontal `LinearLayout` tab row by widget structure instead of obfuscated resource names, shifts the native bar off hit-testing with `alpha=0f`, lifts `AnimatedFloatingActionButton` via `bottomMargin` so scroll hide animations remain intact, and hides the glass bar while the navigation drawer is open.

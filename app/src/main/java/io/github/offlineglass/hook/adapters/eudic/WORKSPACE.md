# Eudic (`com.eusoft.eudic`)

- Verified on APK `versionName=26.10.1`, `versionCode=34523`, `targetSdk=34`.
- Main activity: `com.eusoft.dict.activity.MainTabActivity`.
- Navigation source: `id/nav_view` (`BottomNavigationView`) inside `ConstraintLayout` (`id/container`), with `id/divider_line` and `id/fragment_container` constrained `bottomToTop=@id/nav_view`.
- Eudic toggles tab items in `menu_main_tab_nav_dict` via `View.VISIBLE` / `View.GONE`, so slots are resolved dynamically from visible tab children.
- Adapter hides `divider_line`, re-constrains `fragment_container` `bottomToBottom=0` for full-bleed content behind the floating glass bar, shifts `nav_view` off hit-testing with `alpha=0f`, and draws native icons/badges/labels directly.

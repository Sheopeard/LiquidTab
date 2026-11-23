package io.github.offlineglass.hook.adapters.baidu_map

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec: TargetSpec = TargetSpec(
    key = "baidu_map",
    packageName = "com.baidu.BaiduMap",
    displayName = "百度地图",
    source = AdapterSource.DEDICATED,
    activityHints = setOf("com.baidu.baidumaps.MapsActivity"),
    idHints = setOf("ufo_root", "new_btns"),
    classHints = setOf("MapsActivity"),
    textHints = setOf("首页", "周边", "长按说话", "打车", "我的"),
    preferredSlots = 5..5,
    supportsPostButton = true,
    defaultDetachedPublish = true,
    defaultAccentColor = 0xFF3385FF.toInt(),
    entryLabels = listOf("首页", "周边", "AI助手", "打车", "我的"),
    hookSignals = hookSignals(),
    drawsNavigationInOptics = true,
    glassAccentColor = 0xFF3385FF.toInt(),
    sceneVisibilityAnimation = true,
)

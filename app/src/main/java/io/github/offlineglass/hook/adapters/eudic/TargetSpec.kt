package io.github.offlineglass.hook.adapters.eudic

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec = TargetSpec("eudic", "com.eusoft.eudic", "欧陆词典", AdapterSource.DEDICATED,
    activityHints = setOf("com.eusoft.dict.activity.MainTabActivity"),
    idHints = setOf("nav_view"),
    classHints = setOf("BottomNavigationView"),
    preferredSlots = 2..6,
    defaultAccentColor = 0xFF0D85FF.toInt(),
    entryLabels = listOf("词典", "AI", "生词笔记", "学习", "账号"),
    sceneVisibilityAnimation = true,
)

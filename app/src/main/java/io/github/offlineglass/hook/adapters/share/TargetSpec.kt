package io.github.offlineglass.hook.adapters.share

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec = TargetSpec("share", "com.hengye.share", "Share", AdapterSource.DEDICATED,
    activityHints = setOf("com.hengye.share.module.status.StatusActivity"),
    classHints = setOf("CustomBottomBar"),
    preferredSlots = 2..5,
    defaultAccentColor = 0xFFFF8200.toInt(),
    entryLabels = listOf("首页", "消息", "发现"),
    sceneVisibilityAnimation = true,
)

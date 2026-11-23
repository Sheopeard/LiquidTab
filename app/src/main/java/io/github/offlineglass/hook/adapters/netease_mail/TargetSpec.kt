package io.github.offlineglass.hook.adapters.netease_mail

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec: TargetSpec = TargetSpec(
    key = "netease_mail",
    packageName = "com.netease.mail",
    displayName = "网易邮箱大师",
    source = AdapterSource.DEDICATED,
    activityHints = setOf("com.netease.mail.biz.main.MainITabActivity"),
    idHints = setOf("tab_layout", "content_tabhost"),
    classHints = setOf("MainITabActivity"),
    textHints = setOf("邮件", "文件", "Mail Agent", "待办", "我"),
    preferredSlots = 5..5,
    defaultAccentColor = 0xFFDF362F.toInt(),
    entryLabels = listOf("邮件", "文件", "Mail Agent", "待办", "我"),
    hookSignals = hookSignals(),
    drawsNavigationInOptics = true,
    glassAccentColor = 0xFFDF362F.toInt(),
    sceneVisibilityAnimation = true,
)

package io.github.offlineglass.hook.adapters.netease_mail

import io.github.offlineglass.targets.HookSignal

internal fun hookSignals(): List<HookSignal> = listOf(
    HookSignal("com.netease.mail.biz.main.MainITabActivity", listOf("onResume", "onWindowFocusChanged")),
)

package io.github.offlineglass.hook.adapters.baidu_map

import io.github.offlineglass.targets.HookSignal

internal fun hookSignals(): List<HookSignal> = listOf(
    HookSignal("com.baidu.baidumaps.MapsActivity", listOf("onResume", "onWindowFocusChanged")),
)

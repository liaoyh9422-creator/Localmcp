package com.apkstoapk.app.ui.compose

enum class MainTab {
    Home, Mcp, Log, Settings
}

sealed class AppRoute {
    data object Home : AppRoute()
    data object Mcp : AppRoute()
    data object Log : AppRoute()
    data object Settings : AppRoute()
    data object Merge : AppRoute()
    data object Lua2Dex : AppRoute()
    data object Lua2DexModded : AppRoute()
    data object Manifest : AppRoute()
}

package com.dboycht.keyforge.keyboard

import android.content.Context

/**
 * The facts the "About" dialog shows.
 *
 * Kept as data for two reasons: the same dialog is opened from the settings page and from the
 * full-screen panel (it must not drift between them), and text that nobody can unit-test is text that
 * quietly grows markdown markers, wrong links or a stale support claim - so the values live here,
 * `AboutInfoTest` checks them, and the dialog only lays them out.
 *
 * The **version number is deliberately not here**: it is read from the installed package
 * (`versionName` in `app/build.gradle.kts` is the single source of truth for it), which is the rule
 * this project follows everywhere - a version written into a string is a version that goes stale.
 */
internal object AboutInfo {

    const val APP_NAME_ZH = "键铸"
    const val APP_NAME_EN = "KeyForge"

    const val TAGLINE =
        "把安卓手机变成蓝牙键盘：经蓝牙 HID 直接给 iPad 等主机打字，无需 root。"

    const val SUPPORT =
        "Android 13（API 33）及以上；手机需支持蓝牙 HID Device 角色" +
            "（本机是否支持请看「能力探针」页的结论）。"

    const val HOSTS =
        "实测可用：iPad。实测不可用：Windows 11 + MediaTek 蓝牙（主机不登记 HID 服务）。" +
            "其他主机未实测，可用 tools/bt-service-probe.ps1 自查。"

    const val LIMITATION =
        "蓝牙键盘只能发按键扫描码：中文与 emoji 没有扫描码，发不出去 —— " +
            "中文要靠主机自己的输入法（打拼音过去的是字母）。"

    const val LICENSE_NAME = "Apache License 2.0"

    const val REPO_URL = "https://github.com/dboycht/keyforge"
    const val RELEASES_URL = "https://github.com/dboycht/keyforge/releases/latest"

    // Row labels for the dialog. They live here rather than in strings.xml so that the test can walk
    // every user-visible string of this screen together with its value (see AboutInfoTest); the short
    // chrome - the dialog title and its buttons - stays in strings.xml like the rest of the UI.
    const val LABEL_SUPPORT = "支持范围"
    const val LABEL_HOSTS = "实测主机"
    const val LABEL_LIMITATION = "已知限制"
    const val LABEL_LICENSE = "许可证"
    const val LABEL_REPO = "GitHub 仓库"
    const val LABEL_RELEASES = "查看最新版本"

    /** Every user-visible line, for the test that keeps markdown markers out of the UI. */
    val userVisibleLines: List<String> = listOf(
        TAGLINE, SUPPORT, HOSTS, LIMITATION, LICENSE_NAME,
        LABEL_SUPPORT, LABEL_HOSTS, LABEL_LIMITATION, LABEL_LICENSE, LABEL_REPO, LABEL_RELEASES,
    )
}

/**
 * The version string of the installed build, read from the package manager.
 *
 * Dynamic on purpose: this is the app telling the user which build they are running, and it has to
 * match `versionName` in `app/build.gradle.kts` without anyone remembering to update a string.
 */
internal fun appVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "unknown"

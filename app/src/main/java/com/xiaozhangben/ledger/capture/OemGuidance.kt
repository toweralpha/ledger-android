package com.xiaozhangben.ledger.capture

/**
 * Per-manufacturer background-survival guidance.
 *
 * Why this exists: the APK installs and runs on any Android 8.0+ phone, but a
 * notification listener only keeps working if the user whitelists the app against that
 * manufacturer's battery manager. Every Chinese ROM hides that switch somewhere
 * different, and a user who cannot find it concludes the app is broken. "Compatible
 * with other phones" therefore means *this* table, not the APK.
 *
 * Deliberately plain data with string component names rather than `ComponentName`
 * objects, so brand detection and the step lists are unit-testable without Android.
 *
 * Accuracy caveat: OEMs move and rename these settings screens between ROM versions,
 * and some builds remove the exported activity entirely. So each brand lists several
 * candidate activities, they are tried in order, and the caller falls back to the app
 * details screen plus the written steps. Claiming a single deep link would be a lie on
 * half the devices in the wild.
 */
enum class Oem(val displayName: String) {
    XIAOMI("小米 / Redmi / POCO"),
    HUAWEI("华为"),
    HONOR("荣耀"),
    OPPO("OPPO / realme"),
    ONEPLUS("一加"),
    VIVO("vivo / iQOO"),
    SAMSUNG("三星"),
    MEIZU("魅族"),
    GENERIC("其它安卓"),
}

data class OemProfile(
    val oem: Oem,
    /** Steps written for this ROM specifically, shown in the app. */
    val setupSteps: List<String>,
    /** `"package/class"` candidates for the auto-start list, tried in order. */
    val autoStartActivities: List<String>,
    /** `"package/class"` candidates for the battery / power screen. */
    val batteryActivities: List<String>,
)

object OemGuidance {

    /**
     * Brand detection from `Build.MANUFACTURER` / `Build.BRAND`.
     *
     * Both are checked because they disagree on sub-brands: a Redmi reports
     * manufacturer `Xiaomi` and brand `Redmi`, and checking only one would miss half the
     * line-up.
     */
    fun detect(manufacturer: String?, brand: String?): Oem {
        val haystack = listOfNotNull(manufacturer, brand)
            .joinToString(" ")
            .lowercase()

        return when {
            haystack.contains("xiaomi") || haystack.contains("redmi") ||
                haystack.contains("poco") || haystack.contains("blackshark") -> Oem.XIAOMI

            haystack.contains("honor") || haystack.contains("hihonor") -> Oem.HONOR
            haystack.contains("huawei") -> Oem.HUAWEI

            haystack.contains("oneplus") -> Oem.ONEPLUS
            haystack.contains("oppo") || haystack.contains("realme") -> Oem.OPPO

            haystack.contains("vivo") || haystack.contains("iqoo") -> Oem.VIVO
            haystack.contains("samsung") -> Oem.SAMSUNG
            haystack.contains("meizu") -> Oem.MEIZU
            else -> Oem.GENERIC
        }
    }

    fun profile(oem: Oem): OemProfile = when (oem) {
        Oem.XIAOMI -> OemProfile(
            oem = oem,
            setupSteps = listOf(
                "设置 → 应用设置 → 应用管理 → 小账本 → 自启动 → 允许",
                "同一页 → 省电策略 → 选「无限制」",
                "最近任务卡片 → 长按小账本 → 加锁",
            ),
            autoStartActivities = listOf(
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            batteryActivities = listOf(
                "com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            ),
        )

        Oem.HUAWEI -> OemProfile(
            oem = oem,
            setupSteps = listOf(
                "手机管家 → 应用启动管理 → 小账本 → 关闭「自动管理」",
                "展开后把「自启动 / 关联启动 / 后台活动」三项都打开",
                "设置 → 电池 → 更多电池设置 → 关闭「智能省电」对小账本的限制",
                "最近任务 → 下拉小账本卡片 → 加锁",
            ),
            autoStartActivities = listOf(
                "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
            batteryActivities = listOf(
                "com.huawei.systemmanager/com.huawei.systemmanager.power.ui.HwPowerManagerActivity",
            ),
        )

        Oem.HONOR -> OemProfile(
            oem = oem,
            setupSteps = listOf(
                "设置 → 应用 → 应用启动管理 → 小账本 → 关闭「自动管理」",
                "把「自启动 / 关联启动 / 后台活动」三项都打开",
                "设置 → 电池 → 更多电池设置 → 关闭智能省电限制",
            ),
            autoStartActivities = listOf(
                "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.hihonor.systemmanager/com.hihonor.systemmanager.optimize.process.ProtectActivity",
            ),
            batteryActivities = listOf(
                "com.hihonor.systemmanager/com.hihonor.systemmanager.power.ui.HwPowerManagerActivity",
            ),
        )

        Oem.OPPO -> OemProfile(
            oem = oem,
            setupSteps = listOf(
                "设置 → 电池 → 应用耗电管理 → 小账本",
                "允许「后台运行」「自动启动」「关联启动」",
                "设置 → 应用管理 → 小账本 → 耗电管理 → 允许后台活动",
                "最近任务 → 长按小账本 → 锁定",
            ),
            autoStartActivities = listOf(
                "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity",
            ),
            batteryActivities = listOf(
                "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerConsumptionActivity",
                "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
            ),
        )

        Oem.ONEPLUS -> OemProfile(
            oem = oem,
            setupSteps = listOf(
                "设置 → 电池 → 电池优化 → 小账本 → 不优化",
                "设置 → 应用 → 应用管理 → 小账本 → 耗电管理 → 允许后台活动",
                "最近任务 → 长按小账本 → 锁定",
            ),
            autoStartActivities = listOf(
                "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
                "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity",
            ),
            batteryActivities = listOf(
                "com.oneplus.security/com.oneplus.security.battery.BatteryActivity",
                "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerConsumptionActivity",
            ),
        )

        Oem.VIVO -> OemProfile(
            oem = oem,
            setupSteps = listOf(
                "i管家 → 应用管理 → 自启动 → 允许小账本",
                "设置 → 电池 → 后台耗电管理 → 小账本 → 允许后台高耗电",
                "最近任务卡片下拉 → 加锁",
                "关闭对小账本的省电限制",
            ),
            autoStartActivities = listOf(
                "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
            batteryActivities = listOf(
                "com.vivo.abe/com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity",
            ),
        )

        Oem.SAMSUNG -> OemProfile(
            oem = oem,
            setupSteps = listOf(
                "设置 → 电池和设备维护 → 电池 → 后台使用限制",
                "在「从不休眠的应用」里加入小账本",
                "设置 → 应用 → 小账本 → 电池 → 选「不受限制」",
            ),
            autoStartActivities = listOf(
                "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
            batteryActivities = listOf(
                "com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity",
            ),
        )

        Oem.MEIZU -> OemProfile(
            oem = oem,
            setupSteps = listOf(
                "手机管家 → 权限管理 → 后台管理 → 小账本 → 允许后台运行",
                "设置 → 电量管理 → 耗电排行 → 小账本 → 允许后台运行",
            ),
            autoStartActivities = listOf(
                "com.meizu.safe/com.meizu.safe.permission.SmartBGActivity",
                "com.meizu.safe/com.meizu.safe.security.SHOW_APPSEC",
            ),
            batteryActivities = listOf(
                "com.meizu.safe/com.meizu.safe.powerui.PowerAppPermissionActivity",
            ),
        )

        Oem.GENERIC -> OemProfile(
            oem = oem,
            setupSteps = listOf(
                "设置 → 应用 → 小账本 → 电池 → 选「不受限制」或允许后台活动",
                "设置 → 电池 → 关闭「省电模式」小账本的限制",
                "最近任务 → 锁定小账本",
            ),
            autoStartActivities = emptyList(),
            batteryActivities = emptyList(),
        )
    }
}

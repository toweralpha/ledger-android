# 小账本 · XiaoZhangBen

一款**本地优先、实时自动记账**的 Android 记账本。

- 设定每月总钱量，拆成你自己命名的若干部分
- 「今天还能花多少」再拆成你自己命名的每日类别（早饭 / 午饭 / 晚饭 / 机动……），**每类带时间段，付款后按时间自动归类**
- **付款后自动入账**：监听支付宝与微信的支付通知，1 秒内记上并自动归类
- 也能导入官方账单 CSV 补齐历史（支付宝 GBK、微信 UTF-8 BOM 均支持）
- 转账、红包、退款、余额宝、信用卡还款**不会被当成消费**
- 自动适配 8 家国产 ROM 的后台保活设置，按机型给出图文步骤
- 不申请 `INTERNET` 权限，数据不出手机

---

## 一、为什么是这个形态

### 支付宝和微信没有面向个人的消费查询 API

任何声称"自动同步"的第三方应用，走的都是下面几条路之一。本项目只用合法的三条：

| 通道 | 实时性 | 作用 | 实现 |
|---|---|---|---|
| **通知监听** | 实时（<1 秒） | 主力 | `PaymentNotificationListenerService` 监听指定包名的支付通知 |
| **账单导入** | 手动，建议每月一次 | 补齐历史与漏单 | 解析官方导出的 CSV |
| **手动记账** | 随时 | 兜底 | 底部金额键盘，3 秒一笔 |

**明确不做**：抓包、模拟登录、非官方接口、无障碍服务读屏（`AccessibilityService` 风险高、耗电、易被判定为风险应用）。

### "挂后台"被翻译成了可验证的目标

现代 Android 上不存在"普通应用永久常驻"。用户真正需要的是**不漏单 + 提醒准时 + 不偷电**，所以：

- `NotificationListenerService` 是**系统绑定服务**，被回收后系统会自动重新绑定，且不需要常驻通知 —— 这正是记账应用最需要的那个能力
- **空闲 CPU 为 0**：没有任何轮询。通知是事件驱动；后台只有一个 6 小时的 WorkManager 周期任务
- **不做黑产式保活**：没有互相唤醒、没有 1 像素 Activity、没有播放无声音频、没有双进程守护。这些在新版 Android 上已失效，只会让系统把你的应用标记成"高耗电应用"

---

## 二、构建

### 环境

| 依赖 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.9 |
| Android Gradle Plugin | 8.6.1 |
| Kotlin | 2.0.20（Compose 编译器随 Kotlin 版本） |
| compileSdk / targetSdk / minSdk | 35 / 34 / **26** |

工具链可以完全装在仓库之外，不需要管理员权限、也不污染系统。开发时用的是这样一个自包含目录：

```
<TOOLCHAIN>/
├── jdk17/                  Temurin JDK 17
├── gradle-8.9/             Gradle 发行版
├── android-sdk/            platform-tools / platforms;android-35 / build-tools
├── android-user/           sdkmanager 的缓存与许可证（ANDROID_USER_HOME）
├── gradle-home/            Gradle 缓存（GRADLE_USER_HOME）
└── sdk-env.ps1             环境变量脚本（把上面几项设进当前 PowerShell 会话）
```

### 编译

项目自带 Gradle Wrapper，**只需要 JDK 17 与 Android SDK**：

```powershell
# 先让 JAVA_HOME / ANDROID_HOME 指向你的 JDK 与 SDK，然后：
.\gradlew.bat testDebugUnitTest        # 单元测试
.\gradlew.bat assembleDebug            # 调试包
.\gradlew.bat assembleRelease          # 发布包（需要签名配置，见下）
```

在 Android Studio 里直接打开项目根目录即可；SDK 路径写在 `local.properties` 里。

> `local.properties` 与 `keystore.properties` 是机器相关/机密配置，已加入 `.gitignore`，不应提交。

### 在受限的 Windows 主机上可能遇到的两个坑

如果你也在一个**不允许写用户目录和共享 `%TEMP%`** 的环境里构建，会撞到这两个问题：

1. **共享 `%TEMP%` 不可写** → Kotlin 编译器创建 `<...>.alive` 标记文件时报 `AccessDeniedException`。
   解法是把 `java.io.tmpdir` 指向可写目录。注意**不要写进仓库的 `gradle.properties`**（那是公开发布的文件）；
   推荐用环境变量 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<可写目录>`，因为 forked 的 Kotlin 编译器守护进程不读 `gradle.properties`。
2. **Windows schannel 在某些受限环境不可用**（`SEC_E_NO_CREDENTIALS`）→ 基于 schannel 的下载工具会失败。
   Java 自身的 TLS 不受影响，所以 Gradle 拉依赖正常；只有命令行下载工具需要换用带 OpenSSL 栈的实现。

这两条都只是**特定宿主环境**的限制，与项目本身无关，普通开发机不会遇到。

### 发布签名（重要）

仓库里**不包含**任何签名文件。`*.jks` 与 `keystore.properties` 都已加入 `.gitignore`，**请永远不要提交它们**，也不要把口令写进文档、注释或提交信息里。

首次克隆后自己生成一份：

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore release.jks -alias ledger `
  -keyalg RSA -keysize 2048 -validity 10000
```

再照 `keystore.properties.example` 的格式创建 `keystore.properties`。没有该文件时 release 会回退用 debug 签名，仅用于本地验证。

> **口令 + keystore 文件同时外流，攻击者就能签出系统会当作"正常升级"接受的 APK。**
> 因此：口令只放密码管理器，`release.jks` 单独备份（**不要**放在项目目录里，以免被误提交）。

已构建产物：

```
app/build/outputs/apk/release/app-release.apk    约 2.6 MB，已签名，R8 已开启
app/build/outputs/apk/debug/app-debug.apk        约 18.8 MB，未混淆
```

实测 `aapt dump permissions` 输出确认 **没有 `android.permission.INTERNET`**，且打包了 `arm64-v8a / armeabi-v7a / x86 / x86_64` 四个 ABI。

---

## 三、安装与各品牌保活设置

**APK 本身是通用的**：`minSdk 26`（Android 8.0+，覆盖在用机型的 99%）+ 四个 ABI，任何安卓机都能直接装。**真正的差异不是"能不能装"，而是"会不会被系统杀掉"**——各家国产 ROM 的后台清理策略完全不同，同一份代码在 vivo 上正常，到小米上可能两小时就被清掉。

应用会读 `Build.MANUFACTURER` / `Build.BRAND` **自动识别品牌**，在「我的 → 后台实时自检」里显示该品牌专属的设置步骤，并尝试一键跳转到对应的设置页面（跳不过去就退回应用详情页 + 图文步骤）。

| 品牌 | 关键设置 | 一键跳转的目标组件 |
|---|---|---|
| 小米 / Redmi / POCO | 自启动 + 省电策略改「无限制」 | `com.miui.securitycenter/.permcenter.autostart.AutoStartManagementActivity` |
| 华为 | 应用启动管理 → 手动管理三项全开 | `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` |
| 荣耀 | 应用启动管理 → 手动管理三项全开 | `com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity` |
| OPPO / realme | 应用耗电管理 → 允许后台运行 + 自动启动 | `com.coloros.safecenter/.permission.startup.StartupAppListActivity` |
| 一加 | 电池优化 → 不优化 | `com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity` |
| vivo / iQOO | i管家自启动 + 允许后台高耗电 | `com.vivo.permissionmanager/.activity.BgStartUpManagerActivity` |
| 三星 One UI | 后台使用限制 → 从不休眠的应用 | `com.samsung.android.lool/.sm.ui.battery.BatteryActivity` |
| 魅族 Flyme | 后台管理 → 允许后台运行 | `com.meizu.safe/.permission.SmartBGActivity` |
| 其它 | 应用电池设置 → 不受限制 | 无（只给图文步骤） |

**关于跳转的诚实说明**：OEM 会在不同 ROM 版本里改名、移动、甚至删除这些 Activity。所以每个品牌都配了**多个候选**，按顺序尝试，全部失败就退回应用详情页并展示图文步骤。声称一个"永远有效"的深链是不成立的——这也是代码里 `OemGuidanceTest` 会检查每条组件名格式良好的原因。

### 各品牌通用的一步

不论什么品牌，**通知使用权**都要单独授权，而且**重装应用后会被系统作废**，需要重新开一次：

- 应用内：我的 → 实时捕获 → 通知使用权 → 跳转开启
- 手动：设置 → 应用管理 → 特殊应用权限 → 通知使用权 → 小账本 → 允许

### 打包的 ABI



```
lib/arm64-v8a/libandroidx.graphics.path.so
lib/arm64-v8a/libdatastore_shared_counter.so
lib/armeabi-v7a/…   lib/x86/…   lib/x86_64/…
```

（`androidx.graphics.path` 与 `datastore` 带 `.so`。）所以不需要 ABI split，一个 APK 装到任何主流安卓机上，也能直接发给别人。

### 安装准备

**允许安装未知来源**：设置 → 安全 → 更多安全设置 → 允许安装未知来源应用。
多数国产 ROM 还有「纯净模式」之类的拦截（vivo、OPPO、小米都有），需要在拦截弹窗里选「继续安装」，或临时关闭该模式。

**通知使用权**必须在**每次重装后重新授权**（Android 的行为，不是本应用的特性）。未授权时应用**完全可用**，只是要手动记账或导入账单。

### 用 adb 直接安装（可选）

```powershell
adb devices
adb install -r app/build/outputs/apk/release/app-release.apk
```

手机需先开启开发者选项与 USB 调试。

> ### ⚠️ 手机必须解锁，而且不要按电源键
>
> 实测 vivo 在**锁屏状态下会静默拒绝所有 USB 安装**，报错却是
> `INSTALL_FAILED_ABORTED: User rejected permissions` —— 看上去像权限问题，实际上是 `isKeyguardShowing=true`。
> 这个坑会浪费很多时间，排查方法：
>
> ```powershell
> adb shell dumpsys window | Select-String "isKeyguardShowing"   # true = 装不上
> adb shell dumpsys power  | Select-String "mWakefulness="       # Asleep = 装不上
> ```
>
> `adb shell settings put global stay_on_while_plugged_in 7` 可以让插着 USB 时不息屏，
> 但**按电源键仍然会让它睡过去**，`mStayOn=true` 也挡不住。

**部分 ROM（实测 vivo）还需额外打开开发者选项里的「USB 安装」**，否则会报同一个错。

装完后通知使用权会被系统清空，需要重新开一次：

```powershell
adb shell cmd notification allow_listener com.xiaozhangben.ledger/com.xiaozhangben.ledger.capture.PaymentNotificationListenerService
```

验证是否真的绑定了（要看到 `All notification listeners (1) enabled`）：

```powershell
adb shell dumpsys notification | Select-String "All notification listeners"
```

---

## 四、导出账单（用于补齐历史）

### 支付宝

App：我的 → 账单 → 右上角 `…` → **开具交易流水证明** → 用于个人对账 → 选时间段 → 填邮箱 → 下载 zip，解压得到 CSV。

> 该 CSV 是 **GBK/GB18030 编码**。按 UTF-8 打开会全是乱码，中文商户名一个都匹配不上 —— 应用会自动探测并回退到 GB18030。

### 微信

App：我 → 服务 → 钱包 → 账单 → 右上角常见问题 → **下载账单** → 用于个人对账 → 选时间段 → 填邮箱。邮件里的 zip 有密码，**请先在手机或电脑上解压出 CSV** 再导入（应用不做加密 zip 解压）。
CSV 是 **UTF-8 with BOM**。

### 导入流程

```
选择文件 → 解析 → 〔预览页〕 → 确认 → 〔结果报告〕
```

预览页会显示：识别到的来源与时间范围、**推断出的列映射**、将新增/跳过重复/已排除/待归类各多少笔、前 20 条样例、以及所有被跳过的行及原因。

**导入是幂等的**：同一个文件导入两次，第二次新增 0 笔。

---

## 五、账算得对不对，取决于这几件事

### 1. 非消费流水必须排除

真实账单里混着大量不花钱（或不算消费）的流水。一旦计入，`已用` 会永久偏高：

| 类型 | 处理 |
|---|---|
| 转账 / AA 收款 / 亲友往来 | 标记 `TRANSFER`，不计入支出 |
| 退款 / 已全额退款 / 对方已退还 | 标记 `REFUND`，**冲抵原交易的原分类**，绝不记成收入 |
| 余额宝 / 零钱通 / 理财通 / 基金 / 信用卡还款 / 提现 | 标记 `INVEST`，不计入支出 |
| 交易关闭 / 支付失败 / 已撤销 | 标记 `CLOSED`，不计入支出 |
| 微信 `收/支 = /`、支付宝 `不计收支` | 记录为 `NEUTRAL`，天然不参与预算 |

退款只在**原交易同月**时才冲抵。上个月的订单这个月退款，不能让你这个月凭空变宽松。

### 2. 兜底不能是「其他」

匹配不上任何规则的流水进入**待归类**队列，首页有红点，可以批量归类。静默塞进「其他」会让你以为账是对的。

### 3. 归类引擎会学习

```
① 账单状态        → 退款 / 已关闭            （结构性，置信度最高）
② 账单交易类型    → 转账 / 理财 / 支付宝分类  （高置信度）
③ 规则引擎        → 学习规则 > 内置关键词库   （按 priority 升序）
④ 待归类队列      → 绝不落入「其他」
```

你手动改一次分类，就会生成一条 `LEARNED` 规则（优先级 500，高于内置库的 1000+），**同一商户以后自动归到新分类**。流水详情里会显示"由规则『星巴克 → 餐饮』自动归类"，所以归类结果永远是可解释、可纠正的。

没有引入机器学习模型：对个人记账，关键词规则 + 一次学习在准确率、耗时、耗电和可解释性上都更好。

### 4. 金额一律用「分」

所有金额是 `Long` 分值。解析 `"12.34"` 走 `BigDecimal`。
`12.34 * 100` 在浮点里等于 `1233.9999999998` —— 这类误差会随一个月的流水不断累积，所以 `Double`/`Float` 在本项目中完全不参与金额运算。

---

## 六、隐私

**AndroidManifest 里没有 `INTERNET` 权限。** 这是系统层面的证明：这个应用在技术上不可能上传你的账单。

自行验证（对已构建的 APK）：

```powershell
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt.exe" dump permissions `
  app/build/outputs/apk/release/app-release.apk
```

实测输出（debug 包，release 包相同）：

```
uses-permission: android.permission.POST_NOTIFICATIONS
uses-permission: android.permission.RECEIVE_BOOT_COMPLETED
uses-permission: android.permission.FOREGROUND_SERVICE
uses-permission: android.permission.FOREGROUND_SERVICE_DATA_SYNC
uses-permission: android.permission.WAKE_LOCK              ← 见下方说明
uses-permission: android.permission.ACCESS_NETWORK_STATE   ← 见下方说明
```

**关于这两个"多出来"的权限，必须说清楚：**

它们不是本应用申请的，而是 `androidx.work` 的 manifest 合并进来的（WorkManager 内部用 `WakeLock` 保证一个 job 执行完，用 `NetworkStateTracker` 判断网络约束）。本应用**没有任何代码获取 WakeLock**，也没有设置网络约束。

`ACCESS_NETWORK_STATE` 只能读取连接状态，**无法发送任何数据**；真正的分界线是 `INTERNET`，而它不在列表里。

之所以不移除这两个声明：`WorkManager` 在初始化时会构造 `NetworkStateTracker`，移除 `ACCESS_NETWORK_STATE` 会导致运行时 `SecurityException`；移除 `WAKE_LOCK` 会让 job 执行时崩溃。它们带来的实际开销是一个 6 小时一次、每次几十毫秒的 job，而空闲 CPU 仍然是 0。

其他隐私约束：

- 不接入任何第三方 SDK：无广告、无统计、无崩溃上报、无云推送
- 通知内容只在内存中解析，**不写日志**。`LogX` 只接受 lambda，release 包里连字符串都不会构造；金额、商户名、通知原文永不进入 logcat
- `android:allowBackup="false"`，`data_extraction_rules.xml` 排除云备份与设备迁移 —— 账单不能从侧信道离开设备，唯一的迁移路径是本应用自己的加密导出
- 权限最小化：`POST_NOTIFICATIONS`、`BIND_NOTIFICATION_LISTENER_SERVICE`（服务级）、`FOREGROUND_SERVICE(_DATA_SYNC)`、`RECEIVE_BOOT_COMPLETED`；用 `<queries>` 精确声明支付宝与微信两个包名，**不申请 `QUERY_ALL_PACKAGES`**；不申请通讯录、定位、相机、存储全盘
- 合规说明：仅读取本机通知、由用户主动授权、数据不出设备、不绕过任何平台风控、不上架应用商店（自用侧载）

---

## 七、界面

**五个 Tab** + 一个「记一笔」悬浮按钮，子页面从 Tab 内部跳转。

| 页面 | 内容 |
|---|---|
| **首页** | 月份切换 · 本月可用（大数字 + 环形进度 + 70/90/超额分档）· 今日还能花与**每日类别**各自的剩余 · 预算分项进度条 · 待归类/未开启捕获/漏单/未分配 四类告警 |
| **每日** | 按天回看：◀ 日期 ▶ 切换（可回到今天）· 当天合计与各类别合计 · **早饭/午饭/晚饭各自花了多少、还剩多少**、当天的流水明细、**一键改到别的类别** · **没归类的单独列出并可一键归类** · 每个类别上直接「记一笔」（自动带出该类别） |
| **记一笔** | 底部单屏完成：金额键盘（支持 `12＋8.5` 连续相加）、分类点选、日期选择、备注。**全程不跳页、不滚动、不弹系统键盘** |
| **流水** | 按月 → 按天分组含当日小计 · 搜索商户/商品/备注 · 来源与「待归类」筛选 · **左滑改分类、右滑删除**，删除后底部可撤销 · 点开详情显示**归类依据（命中了哪条规则）与全部原始字段** |
| **统计** | 本月支出与较上月涨跌 · 固定/弹性拆分 · 分类占比环形图（Canvas 手绘）· 与上月对比的 Top 变化 · 近 6 个月柱状趋势 |
| **预算设置** | 总钱量 · **用户自由命名的预算分项**（含固定/弹性、绑定分类）· **用户自由命名的每日类别**（金额、**时间段**、作用星期、绑定分类）· 未分配提示 · 日额度与类别合计不一致提示 · 一键平均分配 · 结余结转开关 · 复制上月 |
| **导入账单** | 导出步骤图文 · 选文件 → **预览（列映射 + 新增/重复/排除/待归类计数 + 样例 + 被跳过的行）** → 确认 → 结果报告 · 历史导入记录与整批撤销 |
| **我的** | 实时捕获开关与**授权状态** · 后台存活自检（最近捕获、累计、被系统回收次数）· **按机型定制的保活设置图文与一键跳转** · 提醒开关 · 主题 · 分类管理 · 归类规则（可停用/删除/改目标，内置库可停用不可删）· **加密备份导出/恢复** · 清空数据 · 隐私说明 |

### 「每日」页为什么需要人工参与

付款通知里只有金额。微信写的是「已支付¥15.00」，**没有一个字说明这是午饭还是晚饭**。所以归类规则按优先级依次尝试，每一步都要求**唯一解**：

| 优先级 | 规则 |
|---|---|
| 1 | **用户手动指定过的** —— 永远最高，之后任何规则变化都不会改动它 |
| 2 | 只有一个类别绑定了这笔的分类 |
| 3 | **时间段**覆盖了付款时刻的类别（时间重叠时**更窄的赢**，所以「宵夜 22–02」能盖过「晚饭 15–24」） |
| 4 | 只有一个既没绑分类、也没设时间的「机动」类别 |
| 5 | 都不满足 → **不猜**，列进「还没分到哪一餐」，等用户点一下 |

第 5 条是关键。旧版本会把所有餐饮都塞给排序最前的那一类，结果是**早饭和午饭同时算错，而且用户毫无察觉**。宁可问一句，也不要静默分错。

### 设计令牌与动效

- 单一强调色 `#0E7C66`（墨绿），收入 `#16A34A`，预警 `#F59E0B`，超支 `#DC2626`；**颜色只表达状态**
- 圆角四档 `12 / 16 / 20 / 28`，间距为 4 的倍数，页面左右边距 20
- 金额一律等宽数字（`fontFeatureSettings = "tnum"`），否则首页每来一笔数字都会抖
- 统一弹簧 `spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)`，时长 180–260ms，**全部 ≤ 300ms**
- 动效只用在有信息量的地方：首页金额与环形进度随入账**弹簧生长**、Tab 切换淡入 + 8dp 位移、分类环与趋势柱入场生长、记账成功收拢回弹
- 读 `Settings.Global.ANIMATOR_DURATION_SCALE`，系统开启「减弱动态效果」时全部动画退化为 `snap()` 直接切终态

### 分层

```
domain/    纯 Kotlin，无 Android 依赖，可单测
  model/  categorize/  bill/  budget/（含 SpendAggregator）
data/      Room 实体、DAO、数据库、种子、映射、仓储、备份
capture/   通知监听服务、通知文本解析、权限与 OEM 跳转
ui/        common（组件/对话框/配色）· home · add · transactions · stats · plan · imports · settings
widget/    Glance 小组件
work/      WorkManager 周期任务与预算预警
di/        手写 AppContainer
```

UI 状态全部是 `StateFlow<UiState>`；ViewModel 通过 `containerViewModel { ... }` 从容器构造，`viewModel()` 负责缓存，**不引入 Hilt**（一个图、几个类，注解处理器只会拖慢构建并引入一类难调的失败）。

### 数据模型要点

`BudgetPeriod`（总钱量）→ `BudgetAllocation`（**用户命名的月分项**）+ `DailyPocket`（**用户命名的日额度分项**）→ `Transaction` → `CategoryRule`。

所有表带 `id`（UUID）、`updatedAt`、`deletedAt`（软删除），并预留 `SyncAdapter` 接口，为将来的同步留好位置（本期只提供 `LocalOnlyAdapter`）。

**没有 `fallbackToDestructiveMigration`。** schema 升级必须写真实的 `Migration` —— 静默丢用户账本不是可接受的失败模式。

---

## 八、测试

```powershell
.\gradlew.bat testDebugUnitTest
```

**当前：14 个测试类，161 个测试，0 失败。** 其中两个测试类跑**真实 SQLite**（Robolectric），因为手写 SQL 能藏住的错误——漏掉 NOT NULL 列、写错的去重条件——只有真数据库才抓得到。

| 测试类 | 覆盖 |
|---|---|
| `MoneyTest` | 千分位、`¥` 前缀、`元` 后缀、`1.005 → 101`、拒绝畸形输入 |
| `AmountInputTest` | 记账键盘状态机：连续相加（`12＋8.5`）、前导零、重复小数点、退格跨过「＋」、单笔上限 |
| `CategorizerTest` | 商户名归一化与品牌别名、交易类型直投、转账/退款/理财/关闭排除、具体规则优先、待归类、规则学习生效 |
| `BillParserTest` | **GB18030 探测**、**UTF-8 BOM 探测**、前导行与表尾汇总行、列映射推断、**千分位金额拆分重对齐**、**未转义逗号重对齐**、引号内逗号、去重键稳定性、跨通道去重规则 |
| `BudgetCalculatorTest` | 退款冲抵原分类、跨月退款不冲抵、固定支出预留、日额度分项归属互斥、工作日限定、70/90/超额分档、无预算不除零 |
| `SpendAggregatorTest` | 分项之和等于总额、退款只冲抵自己的分类、**与 `BudgetCalculator` 的总额必然一致** |
| `NotificationTextParserTest` | 支付宝/微信两种商户名位置、转账（含收/付方向消歧）、优惠券与营销通知**不入账**、待付款与支付失败**不入账**、微信聊天消息**不入账**、只有金额或只有标记都不入账、拒绝原因分类 |
| `CaptureStatsDaoTest` | **真实 SQLite**：诊断计数的完整生命周期（这条曾因漏写 NOT NULL 列而整体静默失效） |
| `LedgerRepositoryDedupeTest` | **真实 SQLite**：重复通知只记一次、两笔同额手动记账都保留、手动记账不吞掉几分钟后的真实支付、**实时捕获的消费在导入账单后不会重复计入**、支付应用为同一笔支付发的第二条通知不算第二笔、退款关联原交易、规则学习生效 |
| `OemGuidanceTest` | 品牌识别（小米子品牌、荣耀 vs 华为、一加 vs OPPO、vivo/iQOO、未知品牌回退）；**每个品牌的步骤都非空**、每条深链组件名格式良好、未知品牌不声称自己做不到的跳转 |
| `DailySlotAssignerTest` | 12:30 的消费归午饭而不是排序最前的那一类、一天内各时段各归其位、23:59 仍算晚饭、**02:00–05:00 谁都不归而是留给用户**、跨午夜时间段、**重叠时间段让更窄的赢**、手动指定永远优先、唯一分类匹配无需时间段、唯一兜底类别、两个兜底类别则不猜；以及 `HH:mm` 解析（含 `24:00` 与各种畸形输入） |

夹具在 `app/src/test/resources/bills/`，**已脱敏**（无真实姓名、账号、单号）。夹具以 UTF-8 存放以便阅读，测试在运行时按真实导出编码重新编码（支付宝 → GB18030，微信 → UTF-8 + BOM），从而真正走通编码探测路径。替换成你自己的账单即可复现。

`SpendAggregatorTest` 里最后一条测试锁的是一个关键不变量：首页的「已用」与统计页的「本月支出」必须来自同一个组件，否则用户会看到两个不同的总数，然后两个都不信。

### 真机验收（需要你操作）

```powershell
# 空闲 CPU（应为 0%）
adb shell top -H -p (adb shell pidof com.xiaozhangben.ledger)

# 确认没有 WakeLock 泄漏
adb shell dumpsys power | Select-String -Pattern "ledger"

# 确认没有 INTERNET 权限
& "$env:ANDROID_HOME\build-tools\35.0.0\aapt.exe" dump permissions <apk>
```

1. 授权通知使用权后，用支付宝付一笔 0.01 元 → 首页数字应在 1 秒内自动变化、无任何手动操作
2. 点通知里的「改分类」改成新分类 → 同一商户下次付款应自动归到新分类
3. 连续挂后台 24 小时，与支付宝/微信账单核对笔数 → 检查漏单率
4. 导入上月账单 → 第二次导入应新增 0 笔
5. 打开「我的 → 后台存活自检」，确认「被系统回收次数」与「最近一次捕获」符合实际

**这几项必须在真机上做，也必须由你来做。** 通知监听的可用性取决于系统版本、OEM 电池策略和你的通知设置，任何在电脑上跑出来的结论都不算数。

---

## 九、真机验证记录（Android 16 / 国产 ROM，实测）

以下都是在真机上跑出来的结论，不是推测。

> 机型与系统版本已隐去：下面每一条都是**系统行为**或**本项目的行为**，与具体是哪台机器无关。

### 已经验证通过

| 项 | 结果 |
|---|---|
| 安装通用 APK | ✅ 单包含 4 个 ABI，`adb install` 直接成功 |
| 运行时权限 | ✅ `dumpsys package` 确认**没有 INTERNET** |
| 界面渲染 | ✅ 首页 / 每日 / 流水 / 统计 / 我的 / 预算设置 / 导入 全部正常，无崩溃 |
| 底部导航 | ✅ 5 个 Tab 互相切换正常，且从任一 Tab 都能回到首页 |
| 通知监听被系统绑定 | ✅ `dumpsys notification` 里出现在 Live listeners |
| **实时捕获** | ✅ 微信扫码付 0.01 元 → 日志 `received a notification from WECHAT` → `captured=1` → 流水页出现该笔（金额 ¥0.01、来源微信、时间正确） |
| **每日类别自动归类** | ✅ 中午 11:41 的一笔微信付款，因落在「午饭」的时间窗（10:00–15:00）而自动归入午饭，首页与每日页金额一致 |
| Room 迁移 | ✅ schema v1 → v3 在已有数据的设备上升级通过，无崩溃、无数据丢失 |
| 去重 | ✅ 一笔支付只产生一条流水 |
| **空闲 CPU = 0%** | ✅ 切后台静置 20 秒后 `top` 连续两次采样（间隔 5s）均为 `0.0%` |
| **无 WakeLock** | ✅ `dumpsys power` 里没有任何来自本应用的 WakeLock |
| **内存** | ✅ **TOTAL PSS 33 MB**（Java 堆 3.2 MB + Native 6.1 MB + System 13 MB）。`top` 显示的 RES ~199 MB 主要是与其它应用共享的框架库页，不是本应用的成本 |

复现命令：

```powershell
$p = (adb shell pidof com.xiaozhangben.ledger).Trim()
adb shell top -b -n 2 -d 5 -p $p          # 看第二个采样，[%CPU] 应为 0.0
adb shell dumpsys meminfo com.xiaozhangben.ledger   # 看 TOTAL PSS
adb shell dumpsys power | Select-String xiaozhangben  # 不应有任何输出
```

### 只有真机才暴露的六个问题（全部已修）

单元测试抓不到它们，这也是为什么每一轮改动都要装到手机上看一眼。

| 问题 | 现象 | 根因 |
|---|---|---|
| 诊断面板整体失效 | 「累计捕获」永远是 0，「监听最近连接」永远是 `—` | `capture_stats` 的两列是 `NOT NULL` 且没有 SQL 默认值，手写 `INSERT` 漏了它们，约束失败被吞掉 |
| 微信转账整条丢失 | 转账付了两次，账上一笔都没有 | 词表里没有「已转账」，`已转账¥0.01` 匹配不到任何支出标记 |
| **账单导入会重复计账** | —— | 模糊去重要求两笔**来源不同**，而通知捕获和月度账单**都是微信**，永远匹配不上。这会让用户每个月导入账单后，所有实时捕获的消费被再记一遍 |
| **种出两个「早饭」** | 每日页出现两张「早饭」卡片 | `bootstrap()` 会从 Application、开机广播、周期任务三处调用，首次启动时两处并发，各自看到"还没有类别"就各插三条。已改为**固定 id + `INSERT OR IGNORE`** |
| **卡片里文字叠在一起** | 当天合计的标题、金额、按钮互相重叠 | `LedgerCard` 的内容槽是 `Box` 不是 `Column`，多个兄弟元素会重叠。其他所有调用点都包了 `Column`，只有那张卡漏了 |
| **首页 Tab 点不动** | 点「首页」会落到「流水」上 | 底部导航那句"标准写法" `popUpTo(起始目的地) { saveState = true } + restoreState = true`：**回到起始目的地时会恢复一份过期的保存栈**。规律很明确——只有作为起始目的地的首页进不去，其余四个正常 |

后三个是**典型的"只有跑起来才看得见"**：一个是并发时序，一个是布局，一个是导航库的状态恢复语义。161 个单元测试全部通过，它们照样漏网。

同一轮还修了一个**潜伏的深链问题**：`MainActivity.onNewIntent` 的注释写着"第二次点通知仍会跳转"，代码却只调了 `setIntent`，**没把新 id 传给界面**，所以应用已在运行时再点通知毫无反应。现在改为状态流 + 用完即清——否则残留的深链会在每次 Activity 重建时把用户拽回流水页。

### 真机上才发现的三件事

**1. 微信「人对人转账」不发系统通知。**

在微信里给好友转账并停留在微信，通知栏不会出现任何东西——所以实时捕获看不到它。支付宝卡对卡转账也一样。日志证据：

```
（付了两次转账，日志里没有任何 received a notification 行）
```

**实时捕获覆盖的是商户消费**（扫码付款、被扫、小程序下单），这类两家都会发通知。这个边界无法绕过：微信不会为转账发通知，而我们不做无障碍读屏（风险高、耗电、易被判定为风险应用）。

实际影响比听起来小：**转账本来就不计入支出**，它不进预算。真正花掉的钱是有通知的。要留存转账记录，走账单导入。

**2. 扫码付款的通知里常常没有商户名。**

微信扫码付款的通知只写「已支付¥0.01」，标题是通用的「微信支付」。所以这笔能正确记上金额和方向，但会落进**待归类**。这是诚实的结果，不是丢数据：

- 在流水里点它改分类，会**学习**该商户
- 或者导入当月账单，那一笔会被**补全商户名并自动重新归类**（同一笔支付不会记两次）

**3. 重装 app 会让系统作废通知使用权授权。**

每次 `adb install -r` 或卸载重装后，`enabled_notification_listeners` 会被清空，必须重新开启一次。这不是本应用的特性，是 Android 的行为。重装后请到「我的 → 实时捕获」确认显示「已开启」。

### 诊断面板

「我的 → 后台存活自检」在真机排查时最有用，它会区分三种"没记账"：

| 显示 | 含义 |
|---|---|
| 监听最近连接 = `—` | 监听服务从未被系统绑定（授权没生效） |
| 累计捕获 = 0，且**没有**「最近忽略的通知」 | 支付应用压根没发通知（转账就是这种情况） |
| 出现「最近忽略的通知：xxx」 | 通知到了但被规则拒了，原因写在这里 |

## 十、已知限制与后续

**本期不做**：云同步 / 多设备、iOS、桌面版、账号体系、短信解析、无障碍读屏、精确闹钟、任何变现功能。

**已知限制**：

- **微信/支付宝的人对人和卡对卡转账不产生系统通知**，实时捕获拿不到（详见第九节）。转账不计入支出，影响有限；要留存记录请用账单导入
- **扫码付款的通知常常没有商户名**，这类流水会先落进「待归类」。导入账单时会补全商户名并自动重新归类，不会重复计入
- **重装 app 会作废通知使用权授权**，需要重新开启一次
- **每日类别是全局的，跨月保持**（v3 起）。它们的金额也不随月份变；月份只管「总钱量怎么分成几块」。这是刻意的：一天的形状不该每个月重建一次
- 每日类别的自动归类规则见第七节。**归不准时会明确列进「还没分到哪一餐」而不猜** —— 凌晨 02:00–05:00 的消费通常落在这里，因为那不属于任何一顿饭。手动指定过的归属**永远优先，任何后续规则变化都不会改动它**
- 时间段重叠时**更窄的赢**（宵夜 22–02 会盖过晚饭 15–24）。两个窗口一样宽且同时覆盖某一刻时按 `sortOrder` 决定 —— 这种设置本身有歧义
- 一个 `BudgetAllocation` 目前绑定**一个**分类。未绑定分类的信封只能当纯数字用，统计不到自动消费 —— 建议创建时顺手绑定（编辑页已直接说明这一点）
- 退款冲抵只在**原交易同月**时生效。跨月退款的流水会保留，但不参与本月预算
- 去重窗口是 3 分钟。同一商户 3 分钟内两笔**金额完全相同**的消费，第二笔会被判为重复。真实场景里极少见，但确实存在
- 打开应用期间跨月（比如 23:59 开着放到 00:01），首页月份不会自动切换，重新进入即可；后台 00:10 的周期任务会刷新小组件
- 备份恢复是**覆盖式**的，没有逐条合并。恢复前有二次确认，但不会自动备份当前数据 —— 请按提示先导出
- 分类占比环只画前 5 个分类的图例，但环形图本身包含全部
- 统计页每次月份内流水变化都会重新聚合 6 个月的数据。个人账本量级（几千行）下无感，但数据量到十万行时需要改成 SQL 聚合

**下一步候选**：信封结余结转的下月自动写入、月度复盘报告页（可导出 PDF）、`SyncAdapter` 的 WebDAV 实现、分类多绑定、记账键盘的历史金额建议、通知捕获时对无商户名的流水给出更积极的默认分类建议。

---

## 十一、合规与免责

本应用仅解析**用户自己导出、或用户自己设备上收到**的支付通知，仅在用户主动授权后运行，数据全程留在本机。应用不尝试访问支付宝/微信的任何非公开接口、不抓包、不模拟登录、不绕过平台风控。账单文件的导出方式与格式由支付宝/微信官方决定，可能随版本变化；解析失败时应用会明确报错并展示推断出的列映射，而不是猜着写库。

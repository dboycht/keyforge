# keyforge · 键铸

> 把安卓手机变成蓝牙键盘：横屏**全屏键盘** + 手机键盘等多种布局，经蓝牙 HID 直连 iPad 等主机直接打字。
> 也可作为**输入法**使用（手机负责录入，按键流发给主机）。**无需 root。**

Turn your Android phone into a Bluetooth HID keyboard — a full-screen landscape keyboard plus
other layouts, and an input-method mode that forwards typed text to the host. No root required.

---

## 状态：可用（全屏键盘 + 4 套布局 + 连接管理 + 输入法）

已在真机上跑通"注册为键盘 → 连接主机 → 敲字上屏"的完整链路，并逐项验证过按键手感：

| 已实现 | 说明 |
| --- | --- |
| 蓝牙键盘 | 手机注册为 HID 键盘（`BluetoothHidDevice`），与主机配对后直接输入 |
| **全屏键盘** | 隐藏系统栏，键盘占满整屏；顶部一条细状态条 + 按需展开的面板；**退出全屏按钮常驻可见**，系统栏也可下滑临时呼出 |
| **4 套布局** | 电脑全键盘（60%）· 全屏（带数字行）· 全屏（无数字行，键更大）· 手机风格；**选择会被记住** |
| **输入法模式** | 可在系统输入法列表里选「键铸输入法」：手机自身的编辑/联想保持可用，输出发给主机 |
| 长按连发 | 普通键按住会连续输出（先等 0.4 秒，随后每 0.06 秒一次） |
| 修饰键两种模式 | **默认同电脑键盘**（按住生效、松手释放）；可在设置里切成"点一下保持" |
| 前台服务 | 离开应用后键盘仍保持连接（有常驻通知，可直接断开/停止） |

**尚未实现（后续方向）**：鼠标/媒体键/触摸板。

> ⚠️ **输入法只转 ASCII**：HID 键盘只有按键扫描码，中文/emoji 没有。输入这些字符时
> 界面会**逐字列出"发不出去"的字符和原因**，不会假装成功，也不会发出错误的按键。

## 兼容性（实测，请先读）

键盘能力来自 Android 的 `BluetoothHidDevice`（HID **Device** 角色，Android 9 / API 28+），
但**能否真正用起来取决于主机侧的蓝牙栈**。实测结果：

| 主机 | 结果 |
| --- | --- |
| **iPad（iPadOS）** | ✅ **可用**（90 ms 内建立连接，持续在线，敲字正常上屏） |
| **Windows 11 + MediaTek 蓝牙** | ❌ **不可用**：主机的配对记录里始终没有 HID 服务，手机发起的连接约 16 秒后被主机断开 |
| 其他主机 | 未实测。仓库带一个探针 `tools/bt-service-probe.ps1`，可在 Windows 上检查"主机是否登记了本机的 HID 服务" |

> ⚠️ 另外**部分厂商固件没有向应用开放 HID Device profile**。仓库里的能力探针（应用内"能力探针"页面）
> 就是用来在目标机型上先确认这一点的：`registerApp` 成功才谈得上后续。
> 在 OPPO K12 Plus / Android 16 上实测：**支持**。

### 已知边界

- **非 ASCII 无法通过标准键盘协议送达**：HID 键盘只传按键扫描码，中文、emoji 没有对应扫描码。
  因此将来的"输入法模式"定位为**文本转发**——手机端负责录入与编辑，
  目标设备只收到可映射的 ASCII 按键；中文要靠**目标设备自己的输入法**（如 iPad 已装拼音）才能上屏。
- 本项目**不需要 root**，也不会要求 root。

## 使用

### 键盘页（手机自己当键盘）

1. 安装 APK，打开应用 → 「能力探针」会检查本机是否支持（不支持会直接说明原因）。
2. 点「打开键盘」进入键盘页，用系统蓝牙与主机配对。
3. 点「连接设备」选择主机 → 显示"已连接"后即可打字。
4. 需要保持连接时直接切走应用即可（前台服务会维持会话；通知栏可断开或停止）。
5. 设置里的「**全屏键盘**」打开后键盘占满整屏（顶部状态条有「退出全屏」）。

### 输入法模式（在别的应用里打字，输出到主机）

1. 系统设置 → 语言与输入法 → 启用「**键铸输入法**」，并切换为当前输入法
   （或在任意输入框弹出的输入法切换器里选它）。
2. 先在键盘页连好主机（输入法与键盘页共用同一个连接会话）。
3. 在输入框里输入文本 → 点「发送」，按键流会发到主机。
4. 中文/emoji 等无法发送的字符会**逐字列出原因**（HID 只有扫描码），其余字符照常发送。

## 环境要求（构建）

| 组件 | 版本 |
| --- | --- |
| JDK | 17+（本机用 Microsoft OpenJDK 21） |
| Android SDK | compileSdk 36、build-tools 36.x；`local.properties` 里 `sdk.dir` 指向本机 SDK |
| Gradle | 8.13（wrapper 已随仓库提供） |
| 最低运行版本 | Android 13（API 33） |

```powershell
$env:JAVA_HOME = "<你的 JDK 17+ 目录>"
.\gradlew.bat assembleDebug        # 产物：app\build\outputs\apk\debug\app-debug.apk
```

单元测试（纯逻辑：键码映射 / 报文 / 状态机 / 布局数据 / 文本转发）：

```powershell
.\gradlew.bat testDebugUnitTest
```

> ⚠️ 作者本机的 wrapper 发行包下载被本地代理的 TLS 解密阻断（见开发副本 `ERROR.md` E1），
> 可直接用已解包的 Gradle 构建：`<GRADLE_USER_HOME>\wrapper\dists\gradle-8.13-bin\<hash>\gradle-8.13\bin\gradle.bat assembleDebug`。

## 版本

版本号单一来源：`app/build.gradle.kts` 的 `versionName`（当前 `1.0.2`，`1.0.1` 为已发布版本）。
打 tag / 发 Release 由维护者按需触发。

> 说明：目前的 release 构建用**调试密钥**签名（`tools/sign-apk.ps1`），
> 面向自用/测试；正式发布前会换成正式 keystore。

## 许可证

[Apache License 2.0](LICENSE)

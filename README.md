# keyforge · 键铸

> 把安卓手机变成蓝牙键盘：启动即横屏，多套布局（电脑全键盘 / 手机键盘 / 输入法文本转发），
> 经蓝牙 HID 连接 iPad、电脑、电视直接打字。**无需 root。**

Turn your Android phone into a Bluetooth HID keyboard — landscape layouts (full PC keyboard,
phone-style, IME text passthrough) that type on iPad, PC, or TV over Bluetooth. No root required.

---

## 状态：开发中（能力探针阶段）

本项目当前处于**第一步：真机能力验证**。原因是一个硬门槛必须先确认：

> 应用的键盘能力来自 Android 的 `BluetoothHidDevice`（HID Device 角色，Android 9 / API 28+ 提供）。
> **部分厂商固件没有向应用开放该 profile**（社区已有为 Android 16+ 补开该 profile 的 Magisk 模块，
> 说明"默认不可用"确有实例）。
> 所以第一件事是在目标机型上跑探针，确认 `registerApp` 能否成功；**不通过就要换方案，而不是先写一大堆 UI**。

## 目标能力（规划）

| 能力 | 说明 |
| --- | --- |
| 蓝牙键盘 | 手机注册为 HID 键盘，与 iPad / PC / TV 配对后直接输入 |
| 电脑全键盘 | 字母区 + 数字行 + 常用符号 + 修饰键（Shift/Ctrl/Alt/Win） |
| 手机风格键盘 | 手机习惯的紧凑布局 |
| 输入法文本转发 | 用手机输入法录入/联想，把**可映射的 ASCII** 按键流送到目标设备 |

## 已知边界（请先读）

- **非 ASCII 无法通过标准键盘协议送达**：HID 键盘只传按键扫描码。中文、emoji 没有对应扫描码，
  因此"输入法模式"定位为**文本转发**：手机端负责录入与编辑，目标设备只收到可映射的 ASCII 按键。
  中文要靠**目标设备自己的输入法**（如 iPad 已装拼音）才能上屏。
- **主机兼容性依赖厂商实现**：能否作为键盘被目标设备接受，取决于手机的蓝牙协议栈。
- 本项目**不需要 root**，也不会要求 root。

## 环境要求（构建）

| 组件 | 版本 |
| --- | --- |
| JDK | 17+（本机用 Microsoft OpenJDK 21） |
| Android SDK | compileSdk 36、build-tools 36.x；`local.properties` 里 `sdk.dir` 指向本机 SDK |
| Gradle | 8.13（wrapper 已随仓库提供） |
| 最低运行版本 | Android 13（API 33） |

构建：

```powershell
$env:JAVA_HOME = "<你的 JDK 17+ 目录>"
.\gradlew.bat assembleDebug        # 产物：app\build\outputs\apk\debug\app-debug.apk
```

> ⚠️ 本机（作者机器）的 wrapper 发行包下载被本地代理的 TLS 解密阻断（见开发副本 `ERROR.md` E1），
> 可直接用已解包的 Gradle 构建：`<GRADLE_USER_HOME>\wrapper\dists\gradle-8.13-bin\<hash>\gradle-8.13\bin\gradle.bat assembleDebug`。

## 版本

版本号单一来源：`app/build.gradle.kts` 的 `versionName`（当前 `1.0.1`）。
打 tag / 发 Release 由维护者按需触发。

## 许可证

[Apache License 2.0](LICENSE)

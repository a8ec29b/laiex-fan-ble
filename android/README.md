# Android App

Kotlin + Jetpack Compose + Material 3。最低 Android 9（API 28），编译与目标 API 35。

Android 12 及以上直接使用系统 Monet 动态配色，Android 9–11 使用 Material 3 固定配色。跟随系统明暗主题。

## 使用

安装 APK，打开「设备设置」，自行填写风扇蓝牙地址及 24 位十六进制 token。首次安装时两项均为空。保存后返回主页，点击「连接风扇」。

获取 token 请参考[小米官方 Home Assistant 插件](https://github.com/XiaoMi/ha_xiaomi_home/)。

- 风扇开关，风速 1–5 挡。
- 灯开关，亮度 1–3 挡。
- 电量读取，手动刷新，连接期间每 15 秒自动刷新。

每次修改都等待设备确认并回读验证，随后更新界面。蓝牙连接超时或断开会显示错误，可手动重新连接。App 退到后台时主动断开；返回后点击连接。

Android 12 及以上会请求「附近的设备」蓝牙连接权限。App 直接连接填写的地址，不扫描设备，不申请定位或网络权限。风扇需通电，手机在蓝牙范围内，米家 App 等其他客户端断开与风扇的连接。

token 使用 Android Keystore 的 AES-GCM 密钥加密后保存，应用禁止备份。卸载 App 或点击「清除设备信息」后需要重新填写。

## 构建

需要 JDK 17 或 21、Android SDK 35 和 build-tools 35.0.0。Gradle Wrapper 已随项目提交。

在本目录建立 `local.properties` 指定自己的 SDK 目录，例如：

```properties
sdk.dir=/path/to/Android/Sdk
```

编译可安装的调试 APK：

```bash
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`，使用本机构建环境的调试密钥签名。

通过 USB 安装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 验证

```bash
./gradlew testDebugUnitTest lintDebug
```

密码学测试使用确定性的合成数据，对照 Python cryptography 独立生成的 HKDF 与 AES-CCM 向量。测试数据不是设备凭据。

已通过 APK 编译、4 项密码学测试及 Android lint（无错误）。在 Android 13 redroid 上检查了初始空白配置、密码隐藏、保存与重启恢复、清除配置、系统 Monet 配色及深色界面。

Linux 版本已在实际风扇上验证协议；Android 版本的认证、写入回读和断线行为仍需真机验证。redroid 可用于界面检查，不能替代蓝牙真机测试。

建议真机验证顺序：填写配置并连接，核对五项状态，逐项测试开关和挡位，再检查刷新、退到后台后断开及重新连接。

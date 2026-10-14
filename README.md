# laiex-fan-ble

Android App 位于 [`android/`](android/)，使用 Kotlin、Jetpack Compose 和 Material 3，最低支持 Android 9（API 28）。Android 12 及以上使用 Monet 动态取色；较早版本使用固定 Material 3 配色，跟随系统明暗主题。

首次启动时蓝牙地址和 token 留空，在「设备设置」中自行填写。App 提供风扇开关、五挡风速、灯开关、三挡亮度及电量读取。配置中的 token 使用 Android Keystore 加密保存。

[Android 构建、安装和验证说明](android/README.md)。Android 的实际蓝牙连接和控制需在真机上验证。

## Linux 控制程序

通过 Linux BlueZ 本地蓝牙控制方频风扇 `laiex.fan.lesfs`。使用已有设备 token 完成蓝牙认证和加密通信，运行时无需米家 App 或 Home Assistant。

| 功能 | 属性名称 | 取值 |
|---|---|---|
| 风扇开关 | `fan` | `on` / `off` |
| 风速 | `speed` | 1–5 |
| 灯开关 | `light` | `on` / `off` |
| 灯亮度 | `brightness` | 1–3 |
| 电量 | `battery` | 0–100，只读 |

## 依赖

需要 Python 3、BlueZ、dbus-python、PyGObject 和 cryptography。Debian / Armbian 可安装：

```bash
pkexec apt-get install bluez python3-dbus python3-gi python3-cryptography
```

机器需要可用的 BLE 适配器、正在运行的 BlueZ，以及当前用户访问系统蓝牙的权限。使用系统 Python 执行下面的命令。

## 获取 token

请参考[小米官方 Home Assistant 插件](https://github.com/XiaoMi/ha_xiaomi_home/)。

控制程序从 `--credentials` 指定的本地 JSON 文件读取 `token` 字段，值为 24 个十六进制字符，解码为 12 字节。请将该文件放在仓库之外，权限设为 `0600`。

## 命令行

在仓库目录执行，将 `AA:BB:CC:DD:EE:FF` 替换为风扇的蓝牙地址，将凭据路径替换为实际路径：

```bash
python3 fan_ble.py --mac AA:BB:CC:DD:EE:FF --credentials /path/to/fan-credentials.json status
python3 fan_ble.py --mac AA:BB:CC:DD:EE:FF --credentials /path/to/fan-credentials.json set fan on
python3 fan_ble.py --mac AA:BB:CC:DD:EE:FF --credentials /path/to/fan-credentials.json set fan off
python3 fan_ble.py --mac AA:BB:CC:DD:EE:FF --credentials /path/to/fan-credentials.json set speed 3
python3 fan_ble.py --mac AA:BB:CC:DD:EE:FF --credentials /path/to/fan-credentials.json set light on
python3 fan_ble.py --mac AA:BB:CC:DD:EE:FF --credentials /path/to/fan-credentials.json set light off
python3 fan_ble.py --mac AA:BB:CC:DD:EE:FF --credentials /path/to/fan-credentials.json set brightness 2
```

`status` 返回五项属性的 JSON。每次命令会连接、认证、执行、断开。设置命令会等待设备确认，再读取该属性，读回一致才报告成功。可通过 `--adapter hci0` 指定蓝牙适配器。

风扇保持通电，手机断开与风扇的蓝牙连接，避免争用。连接超时后可重新执行命令。

## Python 接口

可在一次连接内顺序调用：

```python
from fan_ble import FanBLE

with FanBLE('/path/to/fan-credentials.json', 'AA:BB:CC:DD:EE:FF') as fan:
    state = fan.status()
    speed = fan.get('speed')
    fan.set('speed', 3)
    fan.set('light', True)
    fan.set('brightness', 2)
```

Python 接口的开关值使用 `bool`，挡位使用 `int`。同一个连接的调用须在同一线程顺序执行。

## 协议与实测

使用 FE95 服务的 `00000010/00000019` 认证通道，以及 `0000001a/0000001b` 业务通道。认证通过 token、HKDF-SHA256 和双方 HMAC 建立会话，业务数据使用 AES-CCM 加密，支持编号分包和内联短包。

| siid | piid | 属性 | 线类型 |
|---|---|---|---|
| 2 | 1 | 风扇开关 | `0x00`，1 字节布尔值 |
| 2 | 2 | 风速 | `0x50`，4 字节无符号整数 |
| 2 | 3 | 灯开关 | `0x00`，1 字节布尔值 |
| 2 | 4 | 灯亮度 | `0x50`，4 字节无符号整数 |
| 2 | 6 | 电量 | `0x50`，4 字节无符号整数 |

已在型号 `laiex.fan.lesfs`、固件 `2.1.1_0004` 上实测认证、五项属性读取、开关控制、全部五挡风速与全部三挡亮度。每次写入均通过回读验证，测试后恢复了初始状态。

Linux 程序的长期连接与自动重连尚未验证或实现。

协议参考：[MiAuth](https://github.com/dnandha/miauth)、[CUKTECH BLE Controller](https://github.com/zhyzhaogit/cuktech-ble-controller)、[mijia_ble_libs 归档](https://github.com/scooterhacking/mijia_ble_libs)。

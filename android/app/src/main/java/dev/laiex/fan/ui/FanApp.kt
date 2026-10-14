package dev.laiex.fan.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.laiex.fan.*
import dev.laiex.fan.ble.Property
import kotlin.math.roundToInt

@Composable
fun FanApp(model: FanViewModel, onConnect: () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val state by model.state.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            if (settings) DeviceSettings(state.config,
                onBack = { settings = false },
                onSave = { model.save(it); settings = false },
                onForget = { model.forget(); settings = false })
            else ControlScreen(state,
                onSettings = { settings = true },
                onConnect = { if (state.config.valid) onConnect() else settings = true },
                onDisconnect = model::disconnect,
                onRefresh = model::refresh,
                onSet = model::set,
                onDismissError = model::dismissError)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlScreen(
    state: ScreenState,
    onSettings: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onSet: (Property, Int) -> Unit,
    onDismissError: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); onDismissError() }
    }
    val values = state.fan
    val enabled = state.connection == Connection.CONNECTED && !state.busy
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("方频风扇") }, actions = {
                IconButton(onClick = onRefresh, enabled = enabled) { Icon(Icons.Default.Refresh, "刷新状态") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设备设置") }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Bluetooth, null, Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(when (state.connection) {
                                Connection.DISCONNECTED -> if (state.config.valid) "未连接" else "未配置设备"
                                Connection.CONNECTING -> "正在连接…"
                                Connection.CONNECTED -> "已连接"
                            }, style = MaterialTheme.typography.titleMedium)
                            if (state.config.valid) Text(state.config.address,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    when (state.connection) {
                        Connection.DISCONNECTED -> Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.config.valid) "连接风扇" else "配置设备")
                        }
                        Connection.CONNECTING -> OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("取消连接") }
                        Connection.CONNECTED -> OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("断开连接") }
                    }
                }
            }
            ControlCard(Icons.Default.Air, "风扇", values?.fan, enabled,
                onSwitch = { onSet(Property.FAN, if (it) 1 else 0) }) {
                LevelControl("风速", values?.speed, 5, enabled, { onSet(Property.SPEED, it) })
            }
            ControlCard(Icons.Default.Lightbulb, "灯", values?.light, enabled,
                onSwitch = { onSet(Property.LIGHT, if (it) 1 else 0) }) {
                LevelControl("亮度", values?.brightness, 3, enabled, { onSet(Property.BRIGHTNESS, it) })
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Default.BatteryFull, null, tint = MaterialTheme.colorScheme.primary)
                    Text("电量", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(values?.battery?.let { "$it%" } ?: "—", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

@Composable
private fun ControlCard(icon: ImageVector, name: String, on: Boolean?, enabled: Boolean,
                        onSwitch: (Boolean) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Switch(checked = on == true, onCheckedChange = onSwitch, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "$name 开关" })
            }
            content()
        }
    }
}

@Composable
private fun LevelControl(name: String, current: Int?, maximum: Int, enabled: Boolean, onSet: (Int) -> Unit) {
    var drag by remember(current) { mutableFloatStateOf((current ?: 1).toFloat()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(if (current == null) "—" else "${drag.roundToInt()} 挡", style = MaterialTheme.typography.titleMedium)
    }
    Slider(value = drag, onValueChange = { drag = it }, valueRange = 1f..maximum.toFloat(), steps = maximum - 2,
        onValueChangeFinished = { if (drag.roundToInt() != current) onSet(drag.roundToInt()) }, enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "$name 挡位" })
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        (1..maximum).forEach { Text("$it", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSettings(config: DeviceConfig, onBack: () -> Unit, onSave: (DeviceConfig) -> Unit, onForget: () -> Unit) {
    val context = LocalContext.current
    var address by remember { mutableStateOf(config.address) }
    var token by remember { mutableStateOf(config.token) }
    var visible by remember { mutableStateOf(false) }
    val normalized = DeviceConfig(address.trim().uppercase(), token.trim())
    val addressError = address.isNotEmpty() && !address.trim().matches(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))
    val tokenError = token.isNotEmpty() && !token.trim().matches(Regex("[0-9a-fA-F]{24}"))
    Scaffold(topBar = {
        TopAppBar(title = { Text("设备设置") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("蓝牙地址") },
                singleLine = true, isError = addressError, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                supportingText = { Text(if (addressError) "请填写完整的蓝牙地址" else "格式：AA:BB:CC:DD:EE:FF") })
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Token") },
                singleLine = true, isError = tokenError, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            if (visible) "隐藏 token" else "显示 token")
                    }
                },
                supportingText = { Text(if (tokenError) "请填写 24 位十六进制 token" else "12 字节，共 24 位十六进制字符") })
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/XiaoMi/ha_xiaomi_home/")))
            }) { Text("token 获取方式：参考小米官方 HA 插件") }
            Button(onClick = { onSave(normalized) }, enabled = normalized.valid, modifier = Modifier.fillMaxWidth()) { Text("保存") }
            if (config.valid) OutlinedButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) { Text("清除设备信息") }
        }
    }
}

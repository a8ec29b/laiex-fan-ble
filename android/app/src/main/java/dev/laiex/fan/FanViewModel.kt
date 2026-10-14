package dev.laiex.fan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.laiex.fan.ble.FanProtocol
import dev.laiex.fan.ble.FanState
import dev.laiex.fan.ble.Property
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Connection { DISCONNECTED, CONNECTING, CONNECTED }
data class ScreenState(
    val config: DeviceConfig = DeviceConfig(),
    val connection: Connection = Connection.DISCONNECTED,
    val fan: FanState? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class FanViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DeviceStore(application)
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var client: FanProtocol? = null
    private var action: Job? = null
    private var polling: Job? = null

    init {
        try { mutable.update { it.copy(config = store.load()) } }
        catch (_: Exception) { showError("无法解密已保存的凭据，请重新填写设备设置") }
    }
    fun showError(message: String) { mutable.update { it.copy(error = message) } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun save(config: DeviceConfig) {
        require(config.valid)
        try {
            store.save(config)
            disconnect()
            mutable.update { it.copy(config = config, error = null) }
        } catch (_: Exception) { showError("无法保存设备凭据") }
    }
    fun forget() {
        disconnect()
        store.clear()
        mutable.update { it.copy(config = DeviceConfig(), error = null) }
    }
    fun connect() {
        val config = state.value.config
        if (!config.valid || state.value.connection != Connection.DISCONNECTED) return
        mutable.update { it.copy(connection = Connection.CONNECTING, busy = true, error = null, fan = null) }
        lateinit var newClient: FanProtocol
        newClient = FanProtocol(getApplication()) {
            viewModelScope.launch {
                if (client === newClient) {
                    disconnect()
                    showError("蓝牙连接已断开，可以重新连接")
                }
            }
        }
        client = newClient
        action = viewModelScope.launch {
            try {
                newClient.connect(config.address, config.token)
                val values = newClient.status()
                mutable.update { it.copy(connection = Connection.CONNECTED, busy = false, fan = values) }
                polling = viewModelScope.launch {
                    while (true) {
                        delay(15_000)
                        if (!state.value.busy) refresh()
                    }
                }
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                newClient.close()
                client = null
                mutable.update { it.copy(connection = Connection.DISCONNECTED, busy = false, fan = null, error = readable(e)) }
            }
            catch (e: Exception) {
                if (client === newClient) {
                    newClient.close()
                    client = null
                    mutable.update { it.copy(connection = Connection.DISCONNECTED, busy = false, fan = null, error = readable(e)) }
                }
            }
        }
    }
    fun refresh() = operate { it.status() }
    fun set(property: Property, value: Int) = operate { it.set(property, value) }
    private fun operate(operation: suspend (FanProtocol) -> FanState) {
        val current = client ?: return
        if (state.value.busy || state.value.connection != Connection.CONNECTED) return
        mutable.update { it.copy(busy = true, error = null) }
        action = viewModelScope.launch {
            try {
                val values = operation(current)
                mutable.update { it.copy(fan = values, busy = false) }
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                polling?.cancel()
                current.close()
                client = null
                mutable.update { it.copy(connection = Connection.DISCONNECTED, busy = false, fan = null, error = readable(e)) }
            }
            catch (e: Exception) {
                polling?.cancel()
                current.close()
                client = null
                mutable.update { it.copy(connection = Connection.DISCONNECTED, busy = false, fan = null, error = readable(e)) }
            }
        }
    }
    private fun readable(e: Exception): String = when (e) {
        is kotlinx.coroutines.TimeoutCancellationException -> "蓝牙响应超时，请确认风扇通电且手机在范围内"
        else -> e.message ?: "蓝牙通信失败，请重新连接"
    }
    fun disconnect() {
        action?.cancel()
        polling?.cancel()
        client?.close()
        client = null
        mutable.update { it.copy(connection = Connection.DISCONNECTED, busy = false, fan = null) }
    }
    override fun onCleared() { client?.close() }
}

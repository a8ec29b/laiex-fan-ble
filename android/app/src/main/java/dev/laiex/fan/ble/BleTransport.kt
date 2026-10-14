package dev.laiex.fan.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleTransport(private val context: Context, private val onLost: () -> Unit) {
    private var gatt: BluetoothGatt? = null
    @Volatile private var closing = false
    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<Unit>()
    @Volatile private var operation: CompletableDeferred<Unit>? = null
    private val notifications = ConcurrentHashMap<UUID, Channel<ByteArray>>()

    private fun result(status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) operation?.complete(Unit)
        else operation?.completeExceptionally(IllegalStateException("蓝牙操作失败（$status）"))
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val error = IllegalStateException("蓝牙连接已断开（$status）")
                connected.completeExceptionally(error)
                discovered.completeExceptionally(error)
                operation?.completeExceptionally(error)
                notifications.values.forEach { it.close(error) }
                if (!closing) onLost()
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
            else discovered.completeExceptionally(IllegalStateException("服务发现失败（$status）"))
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = result(status)
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = result(status)
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            notifications[characteristic.uuid]?.trySend(characteristic.value.copyOf())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            notifications[characteristic.uuid]?.trySend(value.copyOf())
        }
    }

    suspend fun connect(address: String) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            ?: error("这台设备不支持蓝牙")
        check(adapter.isEnabled) { "请先开启蓝牙" }
        gatt = adapter.getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: error("无法创建蓝牙连接")
        withTimeout(30_000) { connected.await() }
        check(gatt!!.discoverServices()) { "无法开始服务发现" }
        withTimeout(20_000) { discovered.await() }
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
        gatt?.getService(UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb"))?.getCharacteristic(uuid)
            ?: error("设备缺少所需蓝牙特征，请检查设备地址和型号")

    suspend fun subscribe(uuid: UUID) {
        val g = gatt ?: error("尚未连接")
        val ch = characteristic(uuid)
        notifications[uuid] = Channel(Channel.UNLIMITED)
        check(g.setCharacteristicNotification(ch, true)) { "无法开启蓝牙通知" }
        val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            ?: error("设备缺少通知描述符")
        val done = CompletableDeferred<Unit>()
        operation = done
        try {
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
            check(accepted) { "无法订阅蓝牙通知" }
            withTimeout(8_000) { done.await() }
        } finally { operation = null }
    }

    suspend fun write(uuid: UUID, value: ByteArray) {
        val g = gatt ?: error("尚未连接")
        val ch = characteristic(uuid)
        val done = CompletableDeferred<Unit>()
        operation = done
        try {
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = value
                g.writeCharacteristic(ch)
            }
            check(accepted) { "无法发送蓝牙数据" }
            withTimeout(8_000) { done.await() }
        } finally { operation = null }
    }

    suspend fun receive(uuid: UUID): ByteArray = withTimeout(8_000) {
        notifications.getValue(uuid).receive()
    }

    fun close() {
        closing = true
        operation?.cancel()
        notifications.values.forEach { it.cancel() }
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}

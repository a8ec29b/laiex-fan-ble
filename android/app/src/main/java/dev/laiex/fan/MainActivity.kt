package dev.laiex.fan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dev.laiex.fan.ui.FanApp

class MainActivity : ComponentActivity() {
    private val model: FanViewModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) connectWithBluetooth() else model.showError("需要允许蓝牙连接权限才能控制风扇")
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) model.connect() else model.showError("请开启蓝牙后再连接")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FanApp(model, ::requestConnect) }
    }
    private fun requestConnect() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else connectWithBluetooth()
    }
    @SuppressLint("MissingPermission")
    private fun connectWithBluetooth() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null) model.showError("这台设备不支持蓝牙")
        else if (!adapter.isEnabled) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        else model.connect()
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) model.disconnect()
    }
}

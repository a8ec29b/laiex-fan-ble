package dev.laiex.fan.ble

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
internal fun le(value: Long, size: Int) = ByteArray(size) { (value ushr (it * 8)).toByte() }
internal fun ByteArray.number(offset: Int, size: Int): Long =
    (0 until size).fold(0L) { n, i -> n or ((this[offset + i].toLong() and 255) shl (8 * i)) }
internal fun ByteArray.u(index: Int) = this[index].toInt() and 255

internal object Crypto {
    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
    }
    fun derive(token: ByteArray, salt: ByteArray): ByteArray {
        val key = hmac(salt, token)
        val info = "mible-login-info".toByteArray(Charsets.US_ASCII)
        val first = hmac(key, info + bytes(1))
        return first + hmac(key, first + info + bytes(2))
    }
    fun ccm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val cipher = CCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 32, nonce))
        val output = ByteArray(cipher.getOutputSize(input.size))
        val written = cipher.processBytes(input, 0, input.size, output, 0)
        val total = written + cipher.doFinal(output, written)
        return output.copyOf(total)
    }
}

data class FanState(val fan: Boolean, val speed: Int, val light: Boolean, val brightness: Int, val battery: Int)

enum class Property(val id: Int, val boolean: Boolean = false) {
    FAN(1, true), SPEED(2), LIGHT(3, true), BRIGHTNESS(4), BATTERY(6)
}

class FanProtocol(context: Context, onLost: () -> Unit) {
    private val transport = BleTransport(context, onLost)
    private val mutex = Mutex()
    private val ctrl = uuid("10")
    private val auth = uuid("19")
    private val tx = uuid("1a")
    private val rx = uuid("1b")
    private val ready = bytes(0, 0, 1, 1)
    private val ack = bytes(0, 0, 1, 0)
    private lateinit var deviceKey: ByteArray
    private lateinit var appKey: ByteArray
    private lateinit var deviceIv: ByteArray
    private lateinit var appIv: ByteArray
    private var sendCounter = 0L
    private var receiveCounter = 0L
    private var sequence = 1

    private fun uuid(short: String) = UUID.fromString("000000$short-0000-1000-8000-00805f9b34fb")

    suspend fun connect(address: String, token: String) = mutex.withLock {
        require(token.matches(Regex("[0-9a-fA-F]{24}"))) { "token 需为 24 位十六进制字符" }
        val tokenBytes = token.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        transport.connect(address)
        for (uuid in listOf(ctrl, auth, tx, rx)) transport.subscribe(uuid)
        val clientRandom = ByteArray(16).also { SecureRandom().nextBytes(it) }
        transport.write(ctrl, bytes(0x24, 0, 0, 0))
        send(auth, 11, clientRandom)
        val challenge = receive(auth)
        check(challenge.first == 13 && challenge.second.size == 16) { "设备认证挑战格式不匹配" }
        val deviceRandom = challenge.second
        val derived = Crypto.derive(tokenBytes, clientRandom + deviceRandom)
        deviceKey = derived.copyOfRange(0, 16)
        appKey = derived.copyOfRange(16, 32)
        deviceIv = derived.copyOfRange(32, 36)
        appIv = derived.copyOfRange(36, 40)
        val proof = receive(auth)
        check(proof.first == 12 && MessageDigest.isEqual(proof.second, Crypto.hmac(deviceKey, deviceRandom + clientRandom))) {
            "设备认证失败，请检查 token 是否属于这台风扇"
        }
        send(auth, 10, Crypto.hmac(appKey, clientRandom + deviceRandom))
        check(transport.receive(ctrl).contentEquals(bytes(0x21, 0, 0, 0))) { "风扇拒绝登录" }
    }

    private suspend fun send(uuid: UUID, kind: Int, payload: ByteArray) {
        val count = (payload.size + 17) / 18
        transport.write(uuid, bytes(0, 0, 0, kind) + le(count.toLong(), 2))
        check(transport.receive(uuid).contentEquals(ready)) { "设备未接受数据传输" }
        payload.asList().chunked(18).forEachIndexed { index, part ->
            transport.write(uuid, le(index + 1L, 2) + part.toByteArray())
        }
        check(transport.receive(uuid).contentEquals(ack)) { "设备未确认数据传输" }
    }

    private suspend fun receive(uuid: UUID): Pair<Int, ByteArray> {
        val header = transport.receive(uuid)
        if (header.size >= 4 && header.take(3).toByteArray().contentEquals(bytes(0, 0, 2))) {
            transport.write(uuid, bytes(0, 0, 3, 0))
            return header.u(3) to header.copyOfRange(4, header.size)
        }
        check(header.size == 6 && header.take(3).all { it == 0.toByte() }) { "蓝牙分包头格式不匹配" }
        val count = header.number(4, 2).toInt()
        transport.write(uuid, ready)
        val parts = mutableMapOf<Int, ByteArray>()
        repeat(count) {
            val frame = transport.receive(uuid)
            parts[frame.number(0, 2).toInt()] = frame.copyOfRange(2, frame.size)
        }
        val payload = (1..count).fold(byteArrayOf()) { data, index -> data + parts.getValue(index) }
        transport.write(uuid, ack)
        return header.u(3) to payload
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val counter = sendCounter++
        return le(counter, 2) + Crypto.ccm(true, appKey, appIv + ByteArray(4) + le(counter, 4), plain)
    }

    private fun decrypt(packet: ByteArray): ByteArray {
        val low = packet.number(0, 2)
        var high = receiveCounter and 0xffff0000L
        if ((receiveCounter and 65535) > 32767 && low < 32768) high += 65536
        val counter = high or low
        val plain = Crypto.ccm(false, deviceKey, deviceIv + ByteArray(4) + le(counter, 4), packet.copyOfRange(2, packet.size))
        receiveCounter = counter
        return plain
    }

    private suspend fun request(property: Property, value: Int? = null): Int {
        val seq = sequence
        sequence = (sequence + 1) and 65535
        var body = bytes(if (value == null) 2 else 0, 1, 2) + le(property.id.toLong(), 2)
        if (value != null) body += if (property.boolean) bytes(1, 0, value) else bytes(4, 0x50) + le(value.toLong(), 4)
        val plain = bytes(body.size + 4, 0x20) + le(seq.toLong(), 2) + body
        send(tx, 0, encrypt(plain))
        return withTimeout(8_000) {
            var answer: Int? = null
            while (answer == null) {
                val response = decrypt(receive(rx).second)
                check(response.size >= 11 && response.u(1) == 0x20) { "MIoT 响应格式不匹配" }
                if (response.u(4) == (if (value == null) 3 else 1) && response.number(2, 2).toInt() == seq
                    && response.u(6) == 2 && response.number(7, 2).toInt() == property.id) {
                    val code = response.number(9, 2).toShort().toInt()
                    check(code == 0) { "设备拒绝设置或读取（$code）" }
                    answer = if (value == null) decode(response) else value
                }
            }
            answer
        }
    }

    private fun decode(response: ByteArray): Int {
        val size = response.u(11)
        check(response.size >= 13 + size) { "属性响应不完整" }
        return when {
            response.u(12) == 0 && size == 1 -> response.u(13)
            response.u(12) == 0x50 && size == 4 -> response.number(13, 4).toInt()
            else -> error("设备返回了不支持的属性类型")
        }
    }

    private suspend fun readState() = FanState(
        request(Property.FAN) != 0, request(Property.SPEED), request(Property.LIGHT) != 0,
        request(Property.BRIGHTNESS), request(Property.BATTERY)
    )
    suspend fun status(): FanState = mutex.withLock { readState() }
    suspend fun set(property: Property, value: Int): FanState = mutex.withLock {
        require(when (property) {
            Property.FAN, Property.LIGHT -> value in 0..1
            Property.SPEED -> value in 1..5
            Property.BRIGHTNESS -> value in 1..3
            Property.BATTERY -> false
        }) { "属性取值超出范围" }
        request(property, value)
        check(request(property) == value) { "设备回读的设置值不一致" }
        readState()
    }
    fun close() = transport.close()
}

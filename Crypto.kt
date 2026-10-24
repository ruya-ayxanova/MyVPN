package com.example.myvpn

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Proto {
    const val T_INIT = 1
    const val T_RESP = 2
    const val T_DATA = 3
    val INFO = "myvpn-v1".toByteArray()
    fun be32(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()
    fun be64(v: Long): ByteArray = ByteBuffer.allocate(8).putLong(v).array()
}

object Crypto {
    fun nonce(ctr: Long): ByteArray {
        val n = ByteArray(12)
        System.arraycopy(Proto.be64(ctr), 0, n, 4, 8)
        return n
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    // HKDF-SHA256 (Go tarafındaki x/crypto/hkdf ile aynı)
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < len) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(len)
    }

    fun seal(key: ByteArray, ctr: Long, aad: ByteArray, pt: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce(ctr), aad))
        val out = ByteArray(c.getOutputSize(pt.size))
        var n = c.processBytes(pt, 0, pt.size, out, 0)
        n += c.doFinal(out, n)
        return out.copyOf(n)
    }

    fun open(key: ByteArray, ctr: Long, aad: ByteArray, ct: ByteArray): ByteArray? = try {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce(ctr), aad))
        val out = ByteArray(c.getOutputSize(ct.size))
        var n = c.processBytes(ct, 0, ct.size, out, 0)
        n += c.doFinal(out, n)
        out.copyOf(n)
    } catch (e: Exception) {
        null
    }
}

class Session(val sid: Int, val txKey: ByteArray, val rxKey: ByteArray) {
    val created = System.currentTimeMillis()
    val txCtr = java.util.concurrent.atomic.AtomicLong(0)
    private var max = 0L
    private var bm = 0L

    // 64 paketlik replay penceresi
    @Synchronized fun replayOk(c: Long): Boolean {
        if (c <= 0) return false
        if (c > max) return true
        val d = max - c
        if (d >= 64) return false
        return ((bm ushr d.toInt()) and 1L) == 0L
    }

    @Synchronized fun mark(c: Long) {
        if (c > max) {
            val s = c - max
            bm = if (s >= 64) 1L else (bm shl s.toInt()) or 1L
            max = c
        } else {
            bm = bm or (1L shl (max - c).toInt())
        }
    }
}

class Pending(val sid: Int, val priv: X25519PrivateKeyParameters, val pub: ByteArray, val t: Long, val pkt: ByteArray)

object Handshake {
    private val rnd = SecureRandom()

    fun buildInit(psk: ByteArray, clientId: ByteArray): Pending {
        val priv = X25519PrivateKeyParameters(rnd)
        val pub = priv.generatePublicKey().encoded
        val sid = rnd.nextInt()
        val pkt = ByteArray(61)
        pkt[0] = Proto.T_INIT.toByte()
        System.arraycopy(clientId, 0, pkt, 1, 8)
        System.arraycopy(Proto.be32(sid), 0, pkt, 9, 4)
        System.arraycopy(pub, 0, pkt, 13, 32)
        val mac = Crypto.hmac(psk, pkt.copyOfRange(0, 45))
        System.arraycopy(mac, 0, pkt, 45, 16)
        return Pending(sid, priv, pub, System.currentTimeMillis(), pkt)
    }
}

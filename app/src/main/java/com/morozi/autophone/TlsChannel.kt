package com.morozi.autophone

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.RSAKeyGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.security.auth.x500.X500Principal

/**
 * Drives a [SSLEngine] over a pair of plain read/write functions. This is the
 * standard way to put TLS on top of a transport that isn't a [java.net.Socket]
 * (here: AAP control-channel frames instead of raw TCP) -- the pattern is
 * generic Java SSLEngine usage, not specific to this protocol.
 *
 * `readCipherText` must block until it has at least one TLS record's worth of
 * bytes (callers in this app pull that from AAP control-channel frames).
 * `writeCipherText` sends exactly the bytes handed to it, in order.
 */
class TlsChannel(
    private val engine: SSLEngine,
    private val readCipherText: () -> ByteArray,
    private val writeCipherText: (ByteArray) -> Unit
) {
    private val netBufferSize = engine.session.packetBufferSize
    private val appBufferSize = engine.session.applicationBufferSize

    private var inboundCipher = ByteBuffer.allocate(netBufferSize)
    private var inboundPlain = ByteBuffer.allocate(appBufferSize)

    init {
        inboundCipher.flip() // start empty
    }

    /** Runs the handshake to completion. Throws on failure. */
    fun handshake() {
        engine.beginHandshake()
        var status = engine.handshakeStatus

        while (status != SSLEngineResult.HandshakeStatus.FINISHED &&
            status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            status = when (status) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> doWrap().handshakeStatus
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> doUnwrap().handshakeStatus
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks()
                    engine.handshakeStatus
                }
                else -> status
            }
        }
    }

    /** Encrypts [plain] and writes the resulting TLS record(s) out. */
    fun encryptAndSend(plain: ByteArray) {
        val src = ByteBuffer.wrap(plain)
        val out = ByteBuffer.allocate(netBufferSize)
        while (src.hasRemaining()) {
            out.clear()
            val result = engine.wrap(src, out)
            require(result.status == SSLEngineResult.Status.OK) { "TLS wrap failed: ${result.status}" }
            out.flip()
            val bytes = ByteArray(out.remaining())
            out.get(bytes)
            writeCipherText(bytes)
        }
    }

    /**
     * Encrypts [plain] into TLS record bytes without sending them. After the
     * handshake each encrypted AAP frame carries its own record(s), so the caller
     * frames the result itself.
     */
    fun encrypt(plain: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(plain)
        val collected = java.io.ByteArrayOutputStream()
        val out = ByteBuffer.allocate(netBufferSize)
        while (src.hasRemaining()) {
            out.clear()
            val result = engine.wrap(src, out)
            require(result.status == SSLEngineResult.Status.OK) { "TLS wrap failed: ${result.status}" }
            out.flip()
            collected.write(out.array(), 0, out.limit())
        }
        return collected.toByteArray()
    }

    /**
     * Decrypts the TLS record(s) in one encrypted AAP frame. May return an empty
     * array when the records carried no application data (e.g. a session ticket).
     */
    fun decrypt(cipher: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(cipher)
        val collected = java.io.ByteArrayOutputStream()
        val out = ByteBuffer.allocate(maxOf(appBufferSize, cipher.size))
        while (src.hasRemaining()) {
            out.clear()
            val result = engine.unwrap(src, out)
            require(result.status == SSLEngineResult.Status.OK) { "TLS unwrap failed: ${result.status}" }
            out.flip()
            collected.write(out.array(), 0, out.limit())
            if (result.bytesConsumed() == 0) break
        }
        return collected.toByteArray()
    }

    /** Reads one TLS record from the transport and returns its decrypted payload. */
    fun receiveAndDecrypt(): ByteArray {
        while (true) {
            refillInboundIfNeeded()
            inboundPlain.clear()
            val result = engine.unwrap(inboundCipher, inboundPlain)
            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    inboundPlain.flip()
                    if (inboundPlain.hasRemaining()) {
                        val bytes = ByteArray(inboundPlain.remaining())
                        inboundPlain.get(bytes)
                        return bytes
                    }
                    // Zero-length plaintext (e.g. a consumed control record) -- read the next one.
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    // Need more ciphertext than we have buffered; loop will refill.
                }
                else -> error("TLS unwrap failed: ${result.status}")
            }
        }
    }

    private fun refillInboundIfNeeded() {
        if (inboundCipher.hasRemaining()) return
        val fresh = readCipherText()
        inboundCipher = ByteBuffer.allocate(maxOf(netBufferSize, fresh.size)).apply {
            put(fresh)
            flip()
        }
    }

    private fun doWrap(): SSLEngineResult {
        val empty = ByteBuffer.allocate(0)
        val out = ByteBuffer.allocate(netBufferSize)
        val result = engine.wrap(empty, out)
        out.flip()
        if (out.hasRemaining()) {
            val bytes = ByteArray(out.remaining())
            out.get(bytes)
            writeCipherText(bytes)
        }
        return result
    }

    private fun doUnwrap(): SSLEngineResult {
        refillInboundIfNeeded()
        val out = ByteBuffer.allocate(appBufferSize)
        val result = engine.unwrap(inboundCipher, out)
        if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            refillInboundIfNeeded()
            return engine.unwrap(inboundCipher, out)
        }
        return result
    }

    private fun runDelegatedTasks() {
        while (true) {
            val task = engine.delegatedTask ?: break
            task.run()
        }
    }

    companion object {
        // RSA: the CarlinKit adapter's TLS rejected our first, EC P-256 key with
        // WRONG_CURVE; Android Auto peers expect RSA certificates.
        // v2: the CarlinKit adapter picks plain RSA key exchange, for which
        // BoringSSL asks the key for a raw (no padding) decrypt; v1 only allowed
        // PKCS#1 and failed with "RSA routines: internal error".
        private const val KEY_ALIAS = "aap_phone_identity_rsa2"

        /**
         * The head unit opens TLS as the client (captured: its first SSL_HANDSHAKE
         * payload is a ClientHello), so the phone side must be the TLS server and
         * present a certificate. We use our own self-signed EC key kept in the
         * Android keystore, generated once on first use.
         */
        /**
         * Built once and kept: loading the keystore took ~1 s on the Pi, and a
         * version answer that late made the CarlinKit adapter stop before TLS.
         */
        @Volatile private var cachedContext: SSLContext? = null

        fun serverEngine(): SSLEngine {
            val context = cachedContext ?: synchronized(this) {
                cachedContext ?: buildContext().also { cachedContext = it }
            }
            return context.createSSLEngine().apply {
                useClientMode = false
                // Captured ClientHello from the CarlinKit adapter (TLS 1.2) offers
                // only RSA key exchange and DHE-RSA suites, no ECDHE. Make sure the
                // plain-RSA ones we support are switched on alongside the defaults.
                val wanted = supportedCipherSuites.filter { it.startsWith("TLS_RSA_WITH_AES_") }
                enabledCipherSuites = (enabledCipherSuites.toList() + wanted).distinct().toTypedArray()            }
        }

        private fun buildContext(): SSLContext {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            // The key manager offers every key in the store; an older EC key
            // left there got picked over the RSA one and the CarlinKit adapter
            // (RSA-only suites, no curves offered) failed with WRONG_CURVE.
            keyStore.aliases().toList().filter { it != KEY_ALIAS }.forEach { keyStore.deleteEntry(it) }
            if (!keyStore.containsAlias(KEY_ALIAS)) generateIdentity()

            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, null) }
                .keyManagers
            val context = SSLContext.getInstance("TLS")
            context.init(keyManagers, null, null)
            return context
        }

        private fun generateIdentity() {
            // Sign for ECDHE_RSA suites, decrypt for plain RSA key exchange,
            // which older TLS 1.2 peers may still pick.
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                .setDigests(
                    KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512
                )
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setRandomizedEncryptionRequired(false)
                .setCertificateSubject(X500Principal("CN=Morozi Auto Phone"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(spec)
                generateKeyPair()
            }
        }
    }
}

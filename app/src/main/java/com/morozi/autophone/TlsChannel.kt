package com.morozi.autophone

import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

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
        fun defaultEngine(isClient: Boolean): SSLEngine {
            val context = SSLContext.getInstance("TLS")
            context.init(null, null, null)
            return context.createSSLEngine().apply {
                useClientMode = isClient
            }
        }
    }
}

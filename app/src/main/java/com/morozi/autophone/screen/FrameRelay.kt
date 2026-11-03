package com.morozi.autophone.screen

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Feeds the encoder at a fixed frame rate from a virtual display.
 *
 * A virtual display only produces a frame when the screen changes, so a still
 * screen sent almost no video and the car, which watches for video, could
 * give up. Here the mirrored screen lands in a GPU texture and [run] redraws
 * it into the encoder's input surface every 1/fps seconds, repeating the last
 * picture when nothing changed.
 *
 * [inputSurface] is what the virtual display should draw into. Everything GL
 * runs on the thread that calls [run].
 */
internal class FrameRelay(private val encoderSurface: Surface, private val width: Int, private val height: Int) {

    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var eglSurface: EGLSurface
    private var texture = 0
    private var program = 0
    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var inputSurface: Surface
        private set
    @Volatile private var running = true
    private val ready = java.util.concurrent.CountDownLatch(1)

    /** Waits until [inputSurface] exists (set up by [run] on its own thread). */
    fun awaitInputSurface(): Surface {
        ready.await()
        check(::inputSurface.isInitialized) { "frame relay could not start" }
        return inputSurface
    }

    fun stop() {
        running = false
    }

    fun run(fps: Int) {
        try {
            setUpEgl()
            setUpTexture()
        } finally {
            ready.countDown()  // never leave the caller waiting, even on failure
        }
        val frameNs = 1_000_000_000L / fps
        var next = System.nanoTime()
        val transform = FloatArray(16)
        var lastStamp = -1L
        var latched = 0
        var drawn = 0
        try {
            while (running) {
                // Latch the newest mirrored frame if there is one; otherwise
                // the texture keeps the previous picture and we draw it again.
                surfaceTexture.updateTexImage()
                if (surfaceTexture.timestamp != lastStamp) { lastStamp = surfaceTexture.timestamp; latched++ }
                if (++drawn % (fps * 5) == 0) {
                    android.util.Log.i("FrameRelay", "drawn $drawn, new screen frames $latched, gl error 0x${GLES20.glGetError().toString(16)}")
                }
                surfaceTexture.getTransformMatrix(transform)
                draw(transform)
                EGLExt.eglPresentationTimeANDROID(display, eglSurface, System.nanoTime())
                EGL14.eglSwapBuffers(display, eglSurface)
                next += frameNs
                val sleepNs = next - System.nanoTime()
                if (sleepNs > 0) Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                else next = System.nanoTime()
            }
        } finally {
            release()
        }
    }

    private fun setUpEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    private fun setUpTexture() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        surfaceTexture = SurfaceTexture(texture).apply { setDefaultBufferSize(width, height) }
        inputSurface = Surface(surfaceTexture)
        program = buildProgram()
    }

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        // x, y, u, v for a full-screen triangle strip
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

    private fun draw(transform: FloatArray) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, transform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(): Int {
        val vertex = """
            attribute vec4 aPos;
            attribute vec4 aUv;
            uniform mat4 uTex;
            varying vec2 vUv;
            void main() { gl_Position = aPos; vUv = (uTex * aUv).xy; }
        """.trimIndent()
        val fragment = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTex;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(sTex, vUv); }
        """.trimIndent()
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("FrameRelay", "shader: ${GLES20.glGetShaderInfoLog(it)}")
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(it)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("FrameRelay", "link: ${GLES20.glGetProgramInfoLog(it)}")
        }
    }

    private fun release() {
        runCatching { inputSurface.release() }
        runCatching { surfaceTexture.release() }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

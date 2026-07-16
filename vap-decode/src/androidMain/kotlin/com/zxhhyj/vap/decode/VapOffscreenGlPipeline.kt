@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapRect
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class VapGlFrame(
    val bitmap: Bitmap,
    private val onRelease: () -> Unit,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) onRelease()
    }
}

internal class VapOffscreenGlPipeline(
    private val config: VapConfig,
    private val frameChannel: Channel<VapGlFrame>,
    private val stopped: AtomicBoolean,
    private val onFrameComposited: () -> Unit,
    private val maxBufferedFrames: Int = FRAME_CHANNEL_CAPACITY,
) : SurfaceTexture.OnFrameAvailableListener {

    private data class Size(val w: Int, val h: Int)

    private data class Shader(
        val program: Int,
        val uTexture: Int,
        val aPosition: Int,
        val aTexAlpha: Int,
        val aTexRgb: Int,
    )

    private data class Mesh(
        val vertex: FloatBuffer,
        val alpha: FloatBuffer,
        val rgb: FloatBuffer,
    )

    private data class HwOutput(
        val reader: ImageReader,
        val eglSurface: EGLSurface,
        val size: Size,
    )

    private data class GlState(
        val oesTextureId: Int,
        val surfaceTexture: SurfaceTexture,
        val codecSurface: Surface,
        val shader: Shader,
        val mesh: Mesh,
        val output: HwOutput,
    )

    private val acquired = AtomicBoolean(false)
    private val gl = AtomicReference<GlState?>(null)
    private val bufferedFrames = AtomicInt(0)
    private val targetOut = AtomicReference(
        Size(config.width.coerceAtLeast(1), config.height.coerceAtLeast(1)),
    )

    val codecSurface: Surface?
        get() = gl.load()?.codecSurface

    fun setTargetOutputSize(width: Int, height: Int) {
        targetOut.store(Size(width.coerceAtLeast(1), height.coerceAtLeast(1)))
    }

    suspend fun start(): Boolean {
        val handler = VapSharedGlRuntime.acquire()
        acquired.store(true)
        var created: GlState? = null
        var initError: Throwable? = null
        val ran = VapSharedGlRuntime.withGl {
            try {
                created = initOnGlThread(handler)
            } catch (t: Throwable) {
                initError = t
            }
        }
        val state = created
        if (!ran || state == null) {
            VapSharedGlRuntime.release()
            acquired.store(false)
            if (initError != null) throw initError
            return false
        }
        gl.store(state)
        return true
    }

    fun release() {
        if (!acquired.exchange(false)) return
        VapSharedGlRuntime.withGlBlocking {
            gl.exchange(null)?.let(::releaseOnGlThread)
        }
        VapSharedGlRuntime.release()
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        VapSharedGlRuntime.launch(::compositeAvailableFrames)
    }

    private fun compositeAvailableFrames() {
        if (stopped.load()) return
        val state = gl.load() ?: return
        var permitReleased = false
        try {
            if (!updateTexImage(state)) return

            if (bufferedFrames.load() >= maxBufferedFrames) {
                onFrameComposited()
                return
            }

            val current = ensureOutputSurface(state)
            val frame = drawToHardwareBuffer(current, current.output)
                ?: error("VAP hardware composite failed")
            onFrameComposited()
            permitReleased = true

            if (stopped.load()) {
                frame.release()
                return
            }
            offerFrame(frame)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!permitReleased) onFrameComposited()
            throw e
        } catch (e: Throwable) {
            if (!permitReleased) onFrameComposited()
            throw e
        }
    }

    private fun updateTexImage(state: GlState): Boolean {
        return try {
            state.surfaceTexture.updateTexImage()
            true
        } catch (e: Exception) {
            onFrameComposited()
            if (stopped.load()) return false
            throw e
        }
    }

    private fun offerFrame(frame: VapGlFrame) {
        while (!frameChannel.trySend(frame).isSuccess) {
            val dropped = frameChannel.tryReceive().getOrNull()
            if (dropped == null) {
                frame.release()
                return
            }
            dropped.release()
        }
    }

    private fun trackBufferedFrame(bitmap: Bitmap, onRelease: () -> Unit): VapGlFrame {
        bufferedFrames.fetchAndAdd(1)
        return VapGlFrame(bitmap) {
            bufferedFrames.fetchAndAdd(-1)
            onRelease()
        }
    }

    private fun drawToHardwareBuffer(state: GlState, hw: HwOutput): VapGlFrame? {
        val w = hw.size.w
        val h = hw.size.h
        if (w <= 0 || h <= 0) return null
        if (!VapSharedGlRuntime.makeCurrent(hw.eglSurface)) return null

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        drawVapQuad(state, w, h)
        if (!VapSharedGlRuntime.swapBuffers(hw.eglSurface)) return null

        val image = acquireLatestImage(hw.reader) ?: return null
        val hardwareBuffer = image.hardwareBuffer
        if (hardwareBuffer == null) {
            image.close()
            return null
        }

        val bitmap = try {
            Bitmap.wrapHardwareBuffer(hardwareBuffer, SrgbColorSpace)
        } finally {
            hardwareBuffer.close()
            image.close()
        } ?: return null

        return trackBufferedFrame(bitmap) { bitmap.recycle() }
    }

    private fun acquireLatestImage(reader: ImageReader): Image? {
        var latest: Image? = null
        try {
            while (true) {
                val next = reader.acquireLatestImage() ?: break
                latest?.close()
                latest = next
            }
        } catch (e: IllegalStateException) {
            latest?.close()
            if (stopped.load()) return null
            throw e
        }
        return latest
    }

    private fun ensureOutputSurface(state: GlState): GlState {
        val wanted = targetOut.load()
        if (wanted == state.output.size) return state
        releaseOutput(state.output)
        val updated = state.copy(output = createHardwareOutput(wanted.w, wanted.h))
        gl.store(updated)
        return updated
    }

    private fun initOnGlThread(handler: Handler): GlState? {
        val oesTextureId = genOesTexture()
        val surfaceTexture = SurfaceTexture(oesTextureId).also { st ->
            st.setDefaultBufferSize(
                config.videoWidth.coerceAtLeast(1),
                config.videoHeight.coerceAtLeast(1),
            )
            st.setOnFrameAvailableListener(this, handler)
        }
        val codecSurface = Surface(surfaceTexture)

        val programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) {
            surfaceTexture.release()
            codecSurface.release()
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            return null
        }

        val size = targetOut.load()
        return GlState(
            oesTextureId = oesTextureId,
            surfaceTexture = surfaceTexture,
            codecSurface = codecSurface,
            shader = bindShader(programId),
            mesh = createMesh(),
            output = createHardwareOutput(size.w, size.h),
        )
    }

    private fun bindShader(programId: Int) = Shader(
        program = programId,
        uTexture = GLES20.glGetUniformLocation(programId, "texture"),
        aPosition = GLES20.glGetAttribLocation(programId, "vPosition"),
        aTexAlpha = GLES20.glGetAttribLocation(programId, "vTexCoordinateAlpha"),
        aTexRgb = GLES20.glGetAttribLocation(programId, "vTexCoordinateRgb"),
    )

    private fun createMesh() = Mesh(
        vertex = floatBuffer(FULL_SCREEN_QUAD),
        alpha = floatBuffer(texCoords(config.videoWidth, config.videoHeight, config.alphaFrame)),
        rgb = floatBuffer(texCoords(config.videoWidth, config.videoHeight, config.rgbFrame)),
    )

    private fun createHardwareOutput(w: Int, h: Int): HwOutput {
        val usage = HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        if (!HardwareBuffer.isSupported(w, h, HardwareBuffer.RGBA_8888, 1, usage)) {
            error("HardwareBuffer not supported for ${w}x$h RGBA_8888")
        }
        @Suppress("WrongConstant")
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3, usage)
        val window = VapSharedGlRuntime.createWindowSurface(reader.surface)
        if (window == EGL14.EGL_NO_SURFACE) {
            reader.close()
            error("Failed to create EGL window surface for ImageReader ${w}x$h")
        }
        return HwOutput(reader, window, Size(w, h))
    }

    private fun releaseOutput(output: HwOutput) {
        VapSharedGlRuntime.destroySurface(output.eglSurface)
        output.reader.close()
    }

    private fun drawVapQuad(state: GlState, w: Int, h: Int) {
        val shader = state.shader
        val mesh = state.mesh
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(shader.program)
        enableAttrib(shader.aPosition, mesh.vertex)
        enableAttrib(shader.aTexAlpha, mesh.alpha)
        enableAttrib(shader.aTexRgb, mesh.rgb)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, state.oesTextureId)
        GLES20.glUniform1i(shader.uTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun releaseOnGlThread(state: GlState) {
        state.codecSurface.release()
        state.surfaceTexture.setOnFrameAvailableListener(null)
        state.surfaceTexture.release()
        releaseOutput(state.output)
        GLES20.glDeleteTextures(1, intArrayOf(state.oesTextureId), 0)
        GLES20.glDeleteProgram(state.shader.program)
        VapSharedGlRuntime.makeCurrentPbuffer()
    }

    private fun genOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        val id = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        return id
    }

    private fun enableAttrib(location: Int, buffer: FloatBuffer) {
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, buffer)
    }

    companion object {
        internal const val FRAME_CHANNEL_CAPACITY = 2

        private val SrgbColorSpace: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

        private val FULL_SCREEN_QUAD = floatArrayOf(
            -1f, 1f,
            -1f, -1f,
            1f, 1f,
            1f, -1f,
        )

        private val VERTEX_SHADER = """
            attribute vec4 vPosition;
            attribute vec4 vTexCoordinateAlpha;
            attribute vec4 vTexCoordinateRgb;
            varying vec2 v_TexCoordinateAlpha;
            varying vec2 v_TexCoordinateRgb;
            void main() {
              v_TexCoordinateAlpha = vTexCoordinateAlpha.xy;
              v_TexCoordinateRgb = vTexCoordinateRgb.xy;
              gl_Position = vPosition;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES texture;
            varying vec2 v_TexCoordinateAlpha;
            varying vec2 v_TexCoordinateRgb;
            void main() {
              vec4 alphaColor = texture2D(texture, v_TexCoordinateAlpha);
              vec4 rgbColor = texture2D(texture, v_TexCoordinateRgb);
              gl_FragColor = vec4(rgbColor.rgb, alphaColor.r);
            }
        """.trimIndent()

        private fun texCoords(videoW: Int, videoH: Int, rect: VapRect): FloatArray {
            val w = videoW.coerceAtLeast(1).toFloat()
            val h = videoH.coerceAtLeast(1).toFloat()
            val x0 = rect.x / w
            val y0 = rect.y / h
            val x1 = (rect.x + rect.w) / w
            val y1 = (rect.y + rect.h) / h
            return floatArrayOf(x0, y0, x0, y1, x1, y0, x1, y1)
        }

        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(data)
                .also { it.position(0) }

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        private fun createProgram(vertex: String, fragment: String): Int {
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment)
            if (vs == 0 || fs == 0) return 0

            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)

            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                GLES20.glDeleteProgram(program)
                return 0
            }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return program
        }
    }
}

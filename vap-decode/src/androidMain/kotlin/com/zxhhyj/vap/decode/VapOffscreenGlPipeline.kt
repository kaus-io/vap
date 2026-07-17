@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import com.zxhhyj.vap.player.VapConfig
import com.zxhhyj.vap.player.VapRect
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * MediaCodec OES → GLES alpha merge → [eglSwapBuffers] on an attached window Surface.
 * Present is vsync-gated ([VapVsyncClock]).
 */
internal class VapOffscreenGlPipeline(
    private val config: VapConfig,
    private val presentedTicks: Channel<Unit>,
    private val stopped: AtomicBoolean,
    private val onFrameComposited: () -> Unit,
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

    private data class WindowOutput(
        val eglSurface: EGLSurface,
        val size: Size,
    )

    private sealed class FrameSink {
        data class Window(val window: WindowOutput) : FrameSink()
        data object None : FrameSink()
    }

    private data class GlState(
        val oesTextureId: Int,
        val surfaceTexture: SurfaceTexture,
        val codecSurface: Surface,
        val shader: Shader,
        val mesh: Mesh,
        val sink: FrameSink,
    )

    private sealed class WindowRequest {
        data class Attach(val surface: Surface, val size: Size) : WindowRequest()
        data class Resize(val size: Size) : WindowRequest()
        data object Detach : WindowRequest()
    }

    private val acquired = AtomicBoolean(false)
    private val gl = AtomicReference<GlState?>(null)
    private val pendingWindow = AtomicReference<WindowRequest?>(null)
    private val swapEnabled = AtomicBoolean(true)
    /** Texture updated, waiting for vsync (+ PTS / target FPS) to composite/swap. */
    private val pendingPresent = AtomicBoolean(false)
    /**
     * True while this pipeline still owes [onFrameComposited] for the decode slot acquired
     * before the current pending frame. Cleared on present, discard, or A2 early release.
     */
    private val decodeSlotHeld = AtomicBoolean(false)
    private val vsyncRegistered = AtomicBoolean(false)
    private val playStartMonoNs = AtomicLong(0L)
    private val playStartPtsNs = AtomicLong(0L)
    /** `> 0`: PAG-style wall-clock frame grid (see [isFrameDue]); `0`: follow media PTS. */
    private val targetFps = AtomicInt(0)
    /** Last presented logical frame index when [targetFps] > 0; `-1` = none yet. */
    private val lastPresentedLogicalFrame = AtomicLong(-1L)
    private val surfaceEpoch = AtomicInt(0)

    val codecSurface: Surface?
        get() = gl.load()?.codecSurface

    fun setSwapEnabled(enabled: Boolean) {
        swapEnabled.store(enabled)
        if (enabled) {
            registerVsync()
        } else {
            unregisterVsync()
            VapSharedGlRuntime.runGlBlocking {
                discardPendingPresentLocked(notify = false)
            }
        }
    }

    fun attachOutputSurface(surface: Surface, width: Int, height: Int) {
        pendingWindow.store(
            WindowRequest.Attach(
                surface = surface,
                size = Size(width.coerceAtLeast(1), height.coerceAtLeast(1)),
            ),
        )
        VapSharedGlRuntime.launchGl { applyPendingWindowRequest() }
    }

    fun resizeOutput(width: Int, height: Int) {
        pendingWindow.store(
            WindowRequest.Resize(Size(width.coerceAtLeast(1), height.coerceAtLeast(1))),
        )
        VapSharedGlRuntime.launchGl { applyPendingWindowRequest() }
    }

    suspend fun detachOutputSurface() {
        pendingWindow.store(WindowRequest.Detach)
        VapSharedGlRuntime.withGl {
            discardPendingPresentLocked(notify = false)
            applyPendingWindowRequest()
        }
    }

    fun detachOutputSurfaceBlocking() {
        pendingWindow.store(WindowRequest.Detach)
        VapSharedGlRuntime.runGlBlocking {
            discardPendingPresentLocked(notify = false)
            applyPendingWindowRequest()
        }
    }

    suspend fun start(): Boolean {
        VapSharedGlRuntime.acquire()
        acquired.store(true)
        var created: GlState? = null
        var initError: Throwable? = null
        val ran = VapSharedGlRuntime.withGl {
            try {
                created = initOnGlThread()
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
        registerVsync()
        return true
    }

    suspend fun release() {
        if (!acquired.exchange(false)) return
        unregisterVsync()
        VapSharedGlRuntime.withGl {
            discardPendingPresentLocked(notify = false)
            gl.exchange(null)?.let(::releaseOnGlThread)
        }
        VapSharedGlRuntime.release()
    }

    fun discardPendingPresent() {
        VapSharedGlRuntime.runGlBlocking {
            discardPendingPresentLocked(notify = false)
        }
    }

    fun resetPresentClock() {
        playStartMonoNs.set(0L)
        playStartPtsNs.set(0L)
        lastPresentedLogicalFrame.set(-1L)
    }

    /**
     * Target present FPS (PAG [maxFrameRate] analogue).
     * `> 0`: present only when wall-clock logical frame index advances (`floor(t * fps)`).
     * `<= 0`: follow media PTS.
     */
    fun setTargetFrameRate(fps: Int) {
        targetFps.store(fps.coerceAtLeast(0))
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        VapSharedGlRuntime.launchGl { compositeAvailableFrames() }
    }

    private fun registerVsync() {
        if (vsyncRegistered.load()) return
        if (!VapVsyncClock.register(this)) return
        vsyncRegistered.store(true)
    }

    private fun unregisterVsync() {
        if (!vsyncRegistered.compareAndSet(expectedValue = true, newValue = false)) return
        VapVsyncClock.unregister(this)
    }

    /** Invoked from the process-wide GL vsync dispatcher ([VapVsyncClock]). */
    internal fun onVsyncTick(frameTimeNanos: Long) {
        onVsync(frameTimeNanos)
    }

    private fun discardPendingPresentLocked(notify: Boolean) {
        if (!pendingPresent.exchange(false)) return
        releaseDecodeSlotLocked()
        if (notify) notifyPresented()
    }

    /** Release the MediaCodec/GL slot at most once per pending frame. */
    private fun releaseDecodeSlotLocked() {
        if (decodeSlotHeld.compareAndSet(expectedValue = true, newValue = false)) {
            onFrameComposited()
        }
    }

    /**
     * At most one swap per vsync when due.
     * A2: if a frame is pending but not yet due, release the decode slot immediately while
     * keeping [pendingPresent] so a later vsync can still present (newer textures may overwrite).
     */
    private fun onVsync(frameTimeNanos: Long) {
        if (stopped.load()) return
        if (!pendingPresent.load()) return

        applyPendingWindowRequest()
        val epochAtStart = surfaceEpoch.load()
        val state = gl.load()
        if (state == null) {
            discardPendingPresentLocked(notify = false)
            return
        }

        val ptsNs = state.surfaceTexture.timestamp
        // One load per vsync tick — shared by due-check, A2, and logical-frame stamp.
        val fps = targetFps.load()
        if (!isFrameDue(frameTimeNanos, ptsNs, fps)) {
            // A2 only for wall-clock FPS grids: release the decode slot while waiting for the
            // next cell so decode can run ahead. PTS mode must keep the slot — otherwise the
            // texture timestamp races into the future and the frame never becomes due.
            if (fps > 0) {
                releaseDecodeSlotLocked()
            }
            return
        }
        if (!pendingPresent.compareAndSet(expectedValue = true, newValue = false)) return

        if (surfaceEpoch.load() != epochAtStart) {
            releaseDecodeSlotLocked()
            return
        }

        val sink = state.sink
        if (sink is FrameSink.Window && swapEnabled.load()) {
            if (!drawToWindowSurface(state, sink.window)) {
                releaseDecodeSlotLocked()
                return
            }
        }
        if (fps > 0) {
            lastPresentedLogicalFrame.set(logicalFrameIndex(frameTimeNanos, fps))
        }
        releaseDecodeSlotLocked()
        notifyPresented()
    }

    private fun isFrameDue(frameTimeNanos: Long, ptsNs: Long, fps: Int): Boolean {
        if (fps > 0) {
            // PAG-style maxFrameRate: quantize wall clock onto an fps grid; same cell → not due.
            if (playStartMonoNs.get() == 0L) {
                playStartMonoNs.set(frameTimeNanos)
                return true
            }
            return logicalFrameIndex(frameTimeNanos, fps) > lastPresentedLogicalFrame.get()
        }

        if (ptsNs <= 0L) return true
        val startMono = playStartMonoNs.get()
        if (startMono == 0L) {
            playStartMonoNs.set(frameTimeNanos)
            playStartPtsNs.set(ptsNs)
            return true
        }
        val startPts = playStartPtsNs.get()
        // Loop rewind or broken/jumped media timestamps → resync.
        if (ptsNs + PRESENT_EARLY_NS < startPts) {
            playStartMonoNs.set(frameTimeNanos)
            playStartPtsNs.set(ptsNs)
            return true
        }
        val ptsDelta = ptsNs - startPts
        val monoElapsed = frameTimeNanos - startMono
        if (ptsDelta > monoElapsed + 1_000_000_000L) {
            playStartMonoNs.set(frameTimeNanos)
            playStartPtsNs.set(ptsNs)
            return true
        }
        val dueMono = startMono + ptsDelta
        return frameTimeNanos + PRESENT_EARLY_NS >= dueMono
    }

    /** `floor((t - start + early) * fps / 1e9)` — early absorbs vsync jitter into the next cell. */
    private fun logicalFrameIndex(frameTimeNanos: Long, fps: Int): Long {
        val startMono = playStartMonoNs.get()
        if (startMono == 0L) return 0L
        val elapsed = (frameTimeNanos - startMono + PRESENT_EARLY_NS).coerceAtLeast(0L)
        return elapsed * fps.toLong() / 1_000_000_000L
    }

    private fun compositeAvailableFrames() {
        if (stopped.load()) return
        applyPendingWindowRequest()
        val state = gl.load() ?: return
        try {
            if (!updateTexImage(state)) return
            // Consume decoder output into the OES texture; swap on a due vsync.
            pendingPresent.store(true)
            // Decoder already acquired glSlots for this frame; we own the matching release.
            decodeSlotHeld.store(true)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            releaseDecodeSlotLocked()
            throw e
        } catch (e: Throwable) {
            releaseDecodeSlotLocked()
            throw e
        }
    }

    private fun updateTexImage(state: GlState): Boolean {
        return try {
            state.surfaceTexture.updateTexImage()
            true
        } catch (e: Exception) {
            // Slot still owned by decoder acquire; we have not set decodeSlotHeld yet.
            onFrameComposited()
            if (stopped.load()) return false
            throw e
        }
    }

    private fun notifyPresented() {
        presentedTicks.trySend(Unit)
    }

    private fun drawToWindowSurface(state: GlState, window: WindowOutput): Boolean {
        val w = window.size.w
        val h = window.size.h
        if (w <= 0 || h <= 0) return false
        if (!VapSharedGlRuntime.makeCurrent(window.eglSurface)) return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        drawVapQuad(state, w, h)
        return VapSharedGlRuntime.swapBuffers(window.eglSurface)
    }

    private fun applyPendingWindowRequest() {
        // Fast path: avoid exchange write-barrier when no attach/resize/detach is pending.
        if (pendingWindow.load() == null) return
        val request = pendingWindow.exchange(null) ?: return
        val state = gl.load() ?: return
        when (request) {
            is WindowRequest.Attach -> {
                releaseSink(state.sink)
                val eglSurface = VapSharedGlRuntime.createWindowSurface(request.surface)
                if (eglSurface == EGL14.EGL_NO_SURFACE) {
                    gl.store(state.copy(sink = FrameSink.None))
                    surfaceEpoch.fetchAndAdd(1)
                    return
                }
                val window = WindowOutput(eglSurface, request.size)

                if (VapSharedGlRuntime.makeCurrent(eglSurface)) {
                    GLES20.glViewport(0, 0, request.size.w, request.size.h)
                    GLES20.glClearColor(0f, 0f, 0f, 0f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    VapSharedGlRuntime.swapBuffers(eglSurface)
                }
                // Keep window current — next updateTexImage/present can skip makeCurrent.
                gl.store(state.copy(sink = FrameSink.Window(window)))
                surfaceEpoch.fetchAndAdd(1)
            }

            is WindowRequest.Resize -> {
                val window = (state.sink as? FrameSink.Window)?.window ?: return
                if (window.size == request.size) return
                gl.store(state.copy(sink = FrameSink.Window(window.copy(size = request.size))))
                surfaceEpoch.fetchAndAdd(1)
            }

            WindowRequest.Detach -> {
                // destroySurface switches off this window if it was current.
                releaseSink(state.sink)
                gl.store(state.copy(sink = FrameSink.None))
                surfaceEpoch.fetchAndAdd(1)
            }
        }
    }

    private fun initOnGlThread(): GlState? {
        val oesTextureId = genOesTexture()
        val surfaceTexture = SurfaceTexture(oesTextureId).also { st ->
            st.setDefaultBufferSize(
                config.videoWidth.coerceAtLeast(1),
                config.videoHeight.coerceAtLeast(1),
            )
            VapSharedGlRuntime.setOnFrameAvailableListener(st, this)
        }
        val codecSurface = Surface(surfaceTexture)

        val programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programId == 0) {
            surfaceTexture.release()
            codecSurface.release()
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            return null
        }

        return GlState(
            oesTextureId = oesTextureId,
            surfaceTexture = surfaceTexture,
            codecSurface = codecSurface,
            shader = bindShader(programId),
            mesh = createMesh(),
            sink = FrameSink.None,
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

    private fun releaseWindow(output: WindowOutput) {
        VapSharedGlRuntime.destroySurface(output.eglSurface)
    }

    private fun releaseSink(sink: FrameSink) {
        when (sink) {
            is FrameSink.Window -> releaseWindow(sink.window)
            FrameSink.None -> Unit
        }
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
        discardPendingPresentLocked(notify = false)
        state.codecSurface.release()
        VapSharedGlRuntime.setOnFrameAvailableListener(state.surfaceTexture, null)
        state.surfaceTexture.release()
        releaseSink(state.sink)
        // Ensure a valid surface before deleting GL objects (window may have been destroyed).
        VapSharedGlRuntime.ensureContextCurrent()
        GLES20.glDeleteTextures(1, intArrayOf(state.oesTextureId), 0)
        GLES20.glDeleteProgram(state.shader.program)
    }

    private fun genOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        val id = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
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

        /** Allow presenting slightly before PTS to absorb vsync jitter (~half frame @60Hz). */
        private const val PRESENT_EARLY_NS = 8_000_000L

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

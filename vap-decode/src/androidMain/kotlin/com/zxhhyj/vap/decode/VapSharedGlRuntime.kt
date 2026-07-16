@file:OptIn(ExperimentalAtomicApi::class)

package com.zxhhyj.vap.decode

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Shared EGL + single-thread coroutine dispatcher.
 * HandlerThread/Handler stay private (SurfaceTexture listener + dispatcher backing only).
 */
internal object VapSharedGlRuntime {
    private data class Session(
        val thread: HandlerThread,
        /** Only for [SurfaceTexture.setOnFrameAvailableListener]; not for app logic. */
        val frameCallbackHandler: Handler,
        val dispatcher: CoroutineDispatcher,
        val scope: CoroutineScope,
    )

    private data class Egl(
        val display: EGLDisplay,
        val context: EGLContext,
        val pbuffer: EGLSurface,
        val config: EGLConfig,
    )

    private val mutex = Mutex()
    private val refCount = AtomicInt(0)
    private val session = AtomicReference<Session?>(null)
    private val egl = AtomicReference<Egl?>(null)

    suspend fun acquire() {
        mutex.withLock {
            if (refCount.fetchAndAdd(1) == 0) {
                startSessionLocked()
            }
        }
    }

    suspend fun release() {
        mutex.withLock {
            if (refCount.addAndFetch(-1) <= 0) {
                refCount.store(0)
                stopSessionLocked()
            }
        }
    }

    /** Fire-and-forget work on the GL dispatcher (makes pbuffer current first). */
    fun launchGl(block: suspend CoroutineScope.() -> Unit): Job? {
        val s = session.load() ?: return null
        return s.scope.launch(s.dispatcher) {
            if (!makeCurrentPbuffer()) return@launch
            block()
        }
    }

    /** Switch to the GL dispatcher and run [block] (suspend-friendly). */
    suspend fun withGl(block: suspend CoroutineScope.() -> Unit): Boolean {
        val s = session.load() ?: return false
        return withContext(s.dispatcher) {
            if (!makeCurrentPbuffer()) return@withContext false
            block()
            true
        }
    }

    /**
     * Bridge for non-coroutine Android callbacks (e.g. Surface.onDestroyed).
     * Prefer [withGl] / [launchGl] everywhere else.
     */
    fun <T> runGlBlocking(block: suspend CoroutineScope.() -> T): T? {
        val s = session.load() ?: return null
        // Avoid deadlock if already on the GL thread.
        return if (Looper.myLooper() == s.thread.looper) {
            runBlocking {
                if (!makeCurrentPbuffer()) null else block()
            }
        } else {
            runBlocking(s.dispatcher) {
                if (!makeCurrentPbuffer()) null else block()
            }
        }
    }

    fun setOnFrameAvailableListener(
        surfaceTexture: SurfaceTexture,
        listener: SurfaceTexture.OnFrameAvailableListener?,
    ) {
        val handler = session.load()?.frameCallbackHandler
        if (listener == null || handler == null) {
            surfaceTexture.setOnFrameAvailableListener(null)
        } else {
            surfaceTexture.setOnFrameAvailableListener(listener, handler)
        }
    }

    fun makeCurrent(surface: EGLSurface): Boolean {
        val state = egl.load() ?: return false
        if (surface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglMakeCurrent(state.display, surface, surface, state.context)
    }

    fun makeCurrentPbuffer(): Boolean {
        val state = egl.load() ?: return false
        return makeCurrent(state.pbuffer)
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val state = egl.load() ?: return EGL14.EGL_NO_SURFACE
        val attrs = intArrayOf(EGL14.EGL_NONE)
        return EGL14.eglCreateWindowSurface(state.display, state.config, surface, attrs, 0)
            ?: EGL14.EGL_NO_SURFACE
    }

    fun destroySurface(surface: EGLSurface) {
        val display = egl.load()?.display ?: return
        if (surface != EGL14.EGL_NO_SURFACE && display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(display, surface)
        }
    }

    fun swapBuffers(surface: EGLSurface): Boolean {
        val display = egl.load()?.display ?: return false
        if (surface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglSwapBuffers(display, surface)
    }

    private suspend fun startSessionLocked() {
        val thread = HandlerThread("vap-gl-shared").also { it.start() }
        val frameCallbackHandler = Handler(thread.looper)
        val dispatcher = frameCallbackHandler.asCoroutineDispatcher("vap-gl-shared")
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        session.store(Session(thread, frameCallbackHandler, dispatcher, scope))
        val ready = withContext(dispatcher) { initEgl() }
        if (!ready) {
            stopSessionLocked()
            error("Failed to init shared EGL")
        }
    }

    private suspend fun stopSessionLocked() {
        val s = session.exchange(null) ?: return
        withContext(s.dispatcher) { releaseEgl() }
        s.scope.cancel()
        s.thread.quitSafely()
    }

    private fun initEgl(): Boolean {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return false
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, num, 0)) return false
        val config = configs[0] ?: return false
        val ctxAttrib = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttrib, 0)
        val pbAttrib = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val pbuffer = EGL14.eglCreatePbufferSurface(display, config, pbAttrib, 0)
        if (pbuffer == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) return false
        egl.store(Egl(display, context, pbuffer, config))
        return true
    }

    private fun releaseEgl() {
        val state = egl.exchange(null) ?: return
        if (state.display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                state.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (state.pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(state.display, state.pbuffer)
            }
            if (state.context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(state.display, state.context)
            }
            EGL14.eglTerminate(state.display)
        }
    }
}

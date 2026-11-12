package com.alejandro.tvlauncher

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20

/**
 * Finds out which GPU the box has, so the launcher can switch to a lighter look on weak ones
 * (see "Performance mode" in settings). Call off the main thread.
 */
object GpuInfo {

    /** GL_RENDERER string such as "Mali-G52 MC2", or null if it can't be read. */
    fun renderer(): String? = runCatching { readRenderer() }.getOrNull()

    /**
     * Older / entry-level Mali parts that struggle with blurred glass, glow rings and full-screen
     * overdraw: Mali-4xx, the T-series, and G31 / G51 / G52. Newer Mali (G57 and up) run the full look.
     */
    fun isWeakGpu(renderer: String?): Boolean {
        if (renderer == null) return false
        val r = renderer.lowercase()
        if (!r.contains("mali")) return false
        return Regex("mali-?4\\d\\d").containsMatchIn(r) ||
            Regex("mali-?t\\d+").containsMatchIn(r) ||
            Regex("mali-?g(31|51|52)\\b").containsMatchIn(r)
    }

    private fun readRenderer(): String? {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) return null
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return null

        var ctx = EGL14.EGL_NO_CONTEXT
        var surf = EGL14.EGL_NO_SURFACE
        try {
            val attrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(dpy, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            val cfg = configs[0] ?: return null

            ctx = EGL14.eglCreateContext(
                dpy, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            surf = EGL14.eglCreatePbufferSurface(
                dpy, cfg,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return null
            return GLES20.glGetString(GLES20.GL_RENDERER)
        } finally {
            // Release only what we created. We deliberately do NOT call eglTerminate: the display is
            // shared with the app's own renderer.
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
        }
    }
}

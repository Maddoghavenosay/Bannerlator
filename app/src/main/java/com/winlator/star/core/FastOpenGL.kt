package com.winlator.star.core

import android.content.Context
import android.util.Log
import com.winlator.star.contents.ContentsManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * The "Fast OpenGL" setting: OpenGL (and WineD3D's DirectDraw / older Direct3D, which sit on it)
 * through Wine's EGL backend and Zink, presented by a Vulkan swapchain, instead of XMesa's
 * finish + readback + XPutImage per frame. See [X11Egl] for the X11 half and the measured numbers.
 *
 *  - Wayland → always on: winewayland has no GLX path, it always takes EGL. Nothing is applied and the
 *              editors show the switch locked on.
 *  - X11     → on when asked for AND [unavailableReason] is null: the layer's unix win32u.so knows
 *              WINE_USE_EGL (every Wine 11 layer; GE-Proton 10.0-34 v9 does not), the graphics driver
 *              is an installed Turnip (not "System", not Qualcomm's blob: Zink needs Mesa's Vulkan),
 *              and the APK carries the X11 EGL.
 *
 * Stored as the extra [EXTRA] on the container ("1" / "0", absent = on) and on a shortcut only as a
 * per-game override (absent or "" = follow the container). EGL vs GLX is chosen when the game process
 * starts, so there is no in-game drawer item. BANNER_X11_EGL=0/1 in the environment variables
 * overrides both ([X11Egl.ENV_OVERRIDE]).
 */
object FastOpenGL {
    private const val TAG = "FastOpenGL"

    const val EXTRA = "fastOpenGL"
    const val TITLE = "Fast OpenGL"
    const val HINT = "Faster OpenGL games, and DirectDraw / older Direct3D games through WineD3D."
    const val WAYLAND_ALWAYS_ON = "Always on with Wayland"
    const val NEEDS_LAYER = "Needs a Wine 11 layer"
    const val NEEDS_TURNIP = "Needs a Turnip driver"
    const val NOT_BUNDLED = "Not in this build"

    // Two plain OpenGL launch switches that sit with Fast OpenGL in the editors. Independent of it:
    // both reach Mesa on either path (GLX or EGL) and on Wayland, so they are never greyed.
    /** "Show OpenGL FPS": GALLIUM_HUD=fps, Mesa's fps counter in the game's top-left corner. */
    @JvmField val FPS_HUD = GlLaunchSwitch(
        "glFpsHud", "Show OpenGL FPS", "Mesa's fps counter in the corner of OpenGL games.",
        defaultOn = false, envName = "GALLIUM_HUD", envValue = "fps")

    /** "OpenGL vsync off": vblank_mode=0, so a GL game's SwapBuffers stops waiting for the refresh. */
    @JvmField val VSYNC_OFF = GlLaunchSwitch(
        "glVsyncOff", "OpenGL vsync off",
        "Lets OpenGL games run above the screen's refresh rate. Very old games that tie game speed to " +
            "frame rate may run too fast: use the FPS limiter.",
        defaultOn = false, envName = "vblank_mode", envValue = "0")

    // ── Pure mapping (unit-tested) ───────────────────────────────────────────────────────────────

    /** "1" / "0" for a stored or imported value we understand, else null (= no choice). */
    @JvmStatic
    fun normalize(value: String?): String? = value?.trim()?.takeIf { it == "1" || it == "0" }

    /** The container's choice: on unless it says "0". */
    @JvmStatic
    fun containerOn(extra: String?): Boolean = normalize(extra) != "0"

    /** What a launch asks for: the shortcut's override, else the container's (default on). */
    @JvmStatic
    fun requested(containerExtra: String?, shortcutExtra: String?): Boolean =
        normalize(shortcutExtra)?.let { it == "1" } ?: containerOn(containerExtra)

    /**
     * The one reason an X11 launch can't use it, or null when it can. [layerHasEgl] null = the layer is
     * still being probed: not held against it (the launch re-checks). First that applies wins.
     */
    @JvmStatic
    fun unavailableReason(layerHasEgl: Boolean?, driverUsable: Boolean, bundled: Boolean): String? = when {
        layerHasEgl == false -> NEEDS_LAYER
        !driverUsable -> NEEDS_TURNIP
        !bundled -> NOT_BUNDLED
        else -> null
    }

    // ── Driver + APK ─────────────────────────────────────────────────────────────────────────────

    /**
     * Whether [driverId] (graphicsDriverConfig's "version") is a driver Zink can run on: an installed
     * Turnip. "System" / empty is the system Vulkan, i.e. the Qualcomm blob on Adreno, which is what
     * [WaylandAdapter.isProprietaryBlob] catches for an imported blob (v819) too.
     */
    @JvmStatic
    fun driverUsable(context: Context, driverId: String?): Boolean =
        !driverId.isNullOrEmpty() && driverId != DefaultVersion.WRAPPER &&
            !WaylandAdapter.isProprietaryBlob(context, driverId)

    /** Blocking (one byte scan per layer, then cached): the reason for an X11 launch, or null. */
    @JvmStatic
    fun unavailableReason(context: Context, layerPath: String?, driverId: String?): String? =
        unavailableReason(layerHasEgl(layerPath), driverUsable(context, driverId), X11Egl.isBundled(context))

    // ── Layer detection ──────────────────────────────────────────────────────────────────────────

    // The getenv in win32u's egl_init. Only a layer whose winex11 has the EGL backend carries it.
    private const val MARKER = "WINE_USE_EGL"

    private class Entry(val stamp: String, val hasEgl: Boolean)
    private val byPath = HashMap<String, Entry>()
    private val byIdentifier = HashMap<String, Boolean>()

    /** The last answer for this wine version identifier, or null if it was never probed (no I/O). */
    @JvmStatic
    fun peek(identifier: String?): Boolean? =
        if (identifier.isNullOrEmpty()) null else synchronized(byIdentifier) { byIdentifier[identifier] }

    /**
     * Whether the layer a wine version identifier resolves to has Wine's EGL backend (same resolution
     * as [SyncSupport.capsFor]). Blocking file I/O: call off the main thread.
     */
    @JvmStatic
    fun layerHasEglFor(context: Context, contentsManager: ContentsManager?, identifier: String?): Boolean {
        if (identifier.isNullOrEmpty()) return false
        val has = try {
            val cm = contentsManager ?: ContentsManager(context).also { it.syncContents() }
            layerHasEgl(WineInfo.fromIdentifier(context, cm, identifier).path)
        } catch (e: Exception) {
            Log.w(TAG, "layer $identifier: ${e.message}")
            false
        }
        synchronized(byIdentifier) { byIdentifier[identifier] = has }
        return has
    }

    /**
     * Whether the layer installed at [layerPath] has Wine's EGL backend: its unix win32u.so contains
     * "WINE_USE_EGL". Cached per directory and re-probed when win32u.so changes (mtime/size). An
     * unreadable or missing win32u.so reads as false (the launch stays on GLX).
     */
    @JvmStatic
    fun layerHasEgl(layerPath: String?): Boolean {
        if (layerPath.isNullOrEmpty()) return false
        val win32u = unixWin32u(File(layerPath))
        val stamp = "${win32u?.lastModified() ?: 0}:${win32u?.length() ?: 0}"
        synchronized(byPath) {
            byPath[layerPath]?.takeIf { it.stamp == stamp }?.let { return it.hasEgl }
        }
        val has = if (win32u == null) false else try {
            BufferedInputStream(FileInputStream(win32u)).use {
                SyncSupport.scanForMarkers(it, listOf(MARKER.toByteArray(Charsets.US_ASCII)))[0]
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan ${win32u.path}: ${e.message}")
            false
        }
        Log.i(TAG, "layer $layerPath: egl=$has (${win32u?.path ?: "no win32u.so"})")
        synchronized(byPath) { byPath[layerPath] = Entry(stamp, has) }
        return has
    }

    /** Drop every cached verdict — after a layer is installed or removed. */
    @JvmStatic
    fun invalidate() {
        synchronized(byPath) { byPath.clear() }
        synchronized(byIdentifier) { byIdentifier.clear() }
    }

    private fun unixWin32u(dir: File): File? =
        listOf("lib/wine/aarch64-unix/win32u.so", "lib/wine/x86_64-unix/win32u.so")
            .map { File(dir, it) }.firstOrNull { it.isFile }
}

/**
 * One on/off OpenGL launch switch stored like Fast OpenGL: the container extra [extra] ("1" / "0",
 * absent = [defaultOn]) and the same-named shortcut extra as a per-game override (absent or "" =
 * follow the container). When on, the launch exports [envName]=[envValue] unless the environment
 * variables already set [envName] (the user's value wins).
 */
class GlLaunchSwitch(
    @JvmField val extra: String,
    @JvmField val title: String,
    @JvmField val hint: String,
    @JvmField val defaultOn: Boolean,
    @JvmField val envName: String,
    @JvmField val envValue: String,
) {
    /** The container's choice: [defaultOn] unless it says "1" or "0". */
    fun containerOn(stored: String?): Boolean = FastOpenGL.normalize(stored)?.let { it == "1" } ?: defaultOn

    /** What a launch asks for: the shortcut's override, else the container's. */
    fun requested(containerExtra: String?, shortcutExtra: String?): Boolean =
        FastOpenGL.normalize(shortcutExtra)?.let { it == "1" } ?: containerOn(containerExtra)
}

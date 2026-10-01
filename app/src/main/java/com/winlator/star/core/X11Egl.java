package com.winlator.star.core;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * OpenGL on X11 through Wine's EGL backend instead of GLX.
 *
 * <p>On X11 a GL game presents through imagefs' xlib {@code libGL.so.1.5.0} (XMesa): every
 * SwapBuffers waits for the GPU, maps the frame back and XPutImages it. Measured on a Pocket FIT
 * (AIO standalone GL cube, the same 310 MHz GPU cap for both): 222 fps, against 2023 on Wayland,
 * where Wine reaches Mesa's EGL and Zink presents through Vulkan. winex11 has an EGL backend too
 * (win32u {@code egl_init}, gated by {@code WINE_USE_EGL=1}); it only needs an EGL built with the
 * X11 platform, which the Proton layers' Wayland EGL is not. With this one, Zink presents through
 * kopper (a Vulkan swapchain on the X11 window, the wrapper's X11 WSI, the same path DXVK takes):
 * 1250 fps on the same cube, 401 to 1712 in SGI's GLUT skyfly, DirectDraw (WineD3D on GL) 351 to 1086.
 *
 * <p>Ships inside the APK under {@value #ASSET_DIR} (Mesa EGL + Zink built with
 * {@code -Dplatforms=x11,wayland} for the Termux-style bionic userland, plus the two libraries it
 * links that imagefs lacks) and is installed on first use to
 * <pre>
 *   files/x11_egl/&lt;first 12 hex of libEGL.so.1's sha256&gt;/
 * </pre>
 * so a new build lands in a new folder by itself (older folders are removed).
 *
 * <p>Opt-in for now: {@code BANNER_X11_EGL=1} in the container's or the shortcut's environment
 * variables. {@link #apply} then exports the rest; any of those variables the user set is kept.
 */
public final class X11Egl {
    private X11Egl() {}

    private static final String TAG = "X11Egl";

    public static final String ASSET_DIR = "x11/egl";
    public static final String ASSET_META = ASSET_DIR + "/x11egl.json";
    public static final String DIR_NAME = "x11_egl";
    public static final String ENV_OPT_IN = "BANNER_X11_EGL";
    private static final String KEY_LIB = "libEGL.so.1";

    private static String cachedDir;

    /**
     * On an X11 launch whose environment carries {@value #ENV_OPT_IN}=1: install the bundled EGL and
     * point Wine's OpenGL at it. Runs after both user environment merges, so a value the user typed
     * still wins. Returns a one-line summary for the log, or null when it did nothing.
     *
     * <ul>
     *   <li>{@code WINE_USE_EGL=1}: win32u loads libEGL and winex11 takes its EGL surfaces.</li>
     *   <li>{@code MESA_LOADER_DRIVER_OVERRIDE=zink}: EGL's X11 platform picks Zink.</li>
     *   <li>{@code LIBGL_KOPPER_DRI2=1}: Mesa only takes Zink's kopper path on X11 when the server
     *       has DRI3 1.2 + Present 1.2 multibuffers; ours does not, and without this Mesa falls back
     *       to its software copy path, whose frames the X server never shows.</li>
     *   <li>{@code GALLIUM_THREAD=0}: Mesa's threaded-context thread has no Wine TEB, and a fault on
     *       it kills the process with no trace (DirectDraw through WineD3D died this way on EGL, on
     *       both backends). Costs ~5% in a GL-bound test; the same default as Wayland's GL safe mode.</li>
     *   <li>{@code LD_LIBRARY_PATH}: this folder first. It must come before imagefs/usr/lib, which may
     *       hold an older {@code libgallium-26.3.0-devel.so} (the freedreno GL driver) under the same
     *       name.</li>
     * </ul>
     */
    public static String apply(Context context, EnvVars envVars, String imageFsLibDir) {
        if (!"1".equals(envVars.get(ENV_OPT_IN))) return null;
        String dir = ensureInstalled(context);
        if (dir == null) return "X11 EGL requested but the bundled EGL could not be installed - staying on GLX";
        StringBuilder said = new StringBuilder("X11 EGL on (" + version(context) + "):");
        putIfUnset(envVars, "WINE_USE_EGL", "1", said);
        putIfUnset(envVars, "MESA_LOADER_DRIVER_OVERRIDE", "zink", said);
        putIfUnset(envVars, "LIBGL_KOPPER_DRI2", "1", said);
        putIfUnset(envVars, "GALLIUM_THREAD", "0", said);
        envVars.put("LD_LIBRARY_PATH", dir + ":" + imageFsLibDir + ":/system/lib64");
        said.append(" LD_LIBRARY_PATH=").append(dir).append(":...");
        return said.toString();
    }

    private static void putIfUnset(EnvVars envVars, String name, String value, StringBuilder said) {
        if (envVars.has(name)) {
            said.append(' ').append(name).append('=').append(envVars.get(name)).append(" (yours, kept)");
        } else {
            envVars.put(name, value);
            said.append(' ').append(name).append('=').append(value);
        }
    }

    /** The build's label from {@value #ASSET_META} ("" when the asset carries none). */
    public static String version(Context context) {
        try {
            return new JSONObject(FileUtils.readString(context, ASSET_META)).optString("version", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Install the bundled EGL if this APK's copy isn't in place yet and return the folder's absolute
     * path, or null when the APK carries none or the install failed (logged). File I/O on the first
     * call per process: the launch path is already off the UI thread.
     */
    public static synchronized String ensureInstalled(Context context) {
        if (cachedDir != null && new File(cachedDir, KEY_LIB).isFile()) return cachedDir;
        File root = new File(context.getFilesDir(), DIR_NAME);
        try {
            String[] files = files(context);
            String id = assetId(context);
            if (id == null || files.length == 0) return null;
            File dir = new File(root, id);
            boolean complete = true;
            for (String f : files) if (!new File(dir, f).isFile()) complete = false;
            if (!complete) {
                if (!root.isDirectory() && !root.mkdirs()) throw new IOException("cannot create " + root);
                File tmp = new File(root, ".tmp-" + System.currentTimeMillis());
                FileUtils.delete(tmp);
                if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
                try {
                    for (String f : files) {
                        File out = new File(tmp, f);
                        try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + f);
                             OutputStream os = new FileOutputStream(out)) {
                            byte[] buf = new byte[1 << 16];
                            int r;
                            while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                        }
                        // The guest dlopen()s them: readable + executable for the app's uid.
                        out.setReadable(true, false);
                        out.setExecutable(true, false);
                    }
                    FileUtils.delete(dir);
                    if (!tmp.renameTo(dir)) throw new IOException("cannot move into " + dir);
                } finally {
                    FileUtils.delete(tmp);
                }
                Log.i(TAG, "installed X11 EGL " + version(context) + " (" + id + ") -> " + dir);
            }
            File[] old = root.listFiles();
            if (old != null) for (File f : old) if (!f.getName().equals(id)) FileUtils.delete(f);
            cachedDir = dir.getAbsolutePath();
            return cachedDir;
        } catch (Exception e) {
            Log.e(TAG, "could not install X11 EGL", e);
            return null;
        }
    }

    private static String[] files(Context context) {
        try {
            JSONArray a = new JSONObject(FileUtils.readString(context, ASSET_META)).getJSONArray("files");
            String[] out = new String[a.length()];
            for (int i = 0; i < a.length(); i++) out[i] = a.getString(i);
            return out;
        } catch (Exception e) {
            Log.w(TAG, "no bundled X11 EGL (" + ASSET_META + ")");
            return new String[0];
        }
    }

    /** First 12 hex of libEGL.so.1's sha256, or null when the APK has no X11 EGL asset. */
    private static String assetId(Context context) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + KEY_LIB)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        } catch (IOException e) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        byte[] d = md.digest();
        for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i] & 0xff));
        return sb.toString();
    }
}

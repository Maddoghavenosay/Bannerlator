package com.winlator.star.core

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.util.Log
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerLayerUpdater
import com.winlator.star.container.Shortcut
import com.winlator.star.linux.LinuxShortcuts
import com.winlator.star.store.xbox.XboxCloudShortcuts
import java.io.File
import java.io.IOException

/**
 * Moves a Wine game's entry to another container, or copies it there ([keepCopy]). What travels: the
 * .desktop with all its per-game settings, its icon PNGs and its cover art. The exe is re-pointed
 * through the target's drive map (a drive letter is added to the target when none reaches the game's
 * folder); Z: is the same for every container and is left as it is. A pinned home-screen icon follows
 * a move. The original is deleted only after the new entry is written and loads.
 *
 * Not moved: game files on the source container's own C: drive (refused), and saves the game keeps
 * inside the source container, which stay where they are.
 *
 * Blocking file work — call off the main thread.
 */
object MoveGameToContainer {
    private const val TAG = "MoveGameToContainer"
    private val ICON_SIZES = intArrayOf(64, 48, 32, 16)

    sealed class Result {
        /** [file] is the entry in the target; [addedDrive] is the letter added to the target's drives, if any. */
        class Done(val file: File, val addedDrive: String?) : Result()
        class Refused(val reason: String) : Result()
    }

    /** Why [shortcut] can't go to [target] right now, or null when it can. [run] checks this again. */
    fun problem(context: Context, shortcut: Shortcut, target: Container, keepCopy: Boolean): String? {
        val source = shortcut.container
        if (target.id == source.id) return "\"${shortcut.name}\" is already in \"${target.name}\"."
        if (AndroidGames.isAndroidEntry(shortcut) || LinuxShortcuts.isLinuxEntry(shortcut)) {
            return "Only Windows games can change container."
        }
        if (XboxCloudShortcuts.isNotInstalled(shortcut)) return "Finish downloading \"${shortcut.name}\" first."
        val info = CopyGameToDriveC.parse(shortcut)
        if (info.onDriveC) {
            return "\"${shortcut.name}\" is installed inside \"${source.name}\" (drive C:). Moving its files isn't supported yet."
        }
        if (info.exeAndroid == null) return "Couldn't find where \"${shortcut.name}\" is installed."
        val dir = target.desktopDir
        if (File(dir, shortcut.file.name).exists() || File(dir, "${shortcut.name}.lnk").exists()) {
            return "\"${target.name}\" already has a game called \"${shortcut.name}\"."
        }
        // A running session keeps writing to its own .desktop, which would bring the moved one back.
        if (!keepCopy) {
            val updater = ContainerLayerUpdater(context)
            if (updater.isRunning(source) || updater.isRunning(target)) return "Close the running game first."
        }
        return null
    }

    fun run(context: Context, shortcut: Shortcut, target: Container, keepCopy: Boolean): Result {
        problem(context, shortcut, target, keepCopy)?.let { return Result.Refused(it) }
        val source = shortcut.container
        val destDir = target.desktopDir
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            return Result.Refused("Couldn't open the games folder of \"${target.name}\".")
        }
        val text = runCatching { shortcut.file.readText() }.getOrNull()
            ?: return Result.Refused("Couldn't read \"${shortcut.name}\".")
        val newFile = File(destDir, shortcut.file.name)
        if (!FileUtils.writeString(newFile, text)) return Result.Refused("Couldn't write to \"${target.name}\".")

        // Everything written into the target, removed again if any step fails.
        val written = arrayListOf(newFile)
        var oldCover: File? = null
        var addedDrive: String? = null
        try {
            iconOf(text)?.let { icon ->
                for (size in ICON_SIZES) {
                    val src = File(source.getIconsDir(size), "$icon.png")
                    val dst = File(target.getIconsDir(size), "$icon.png")
                    if (src.isFile && !dst.exists()) {
                        dst.parentFile?.mkdirs()
                        src.copyTo(dst)
                        written += dst
                    }
                }
            }

            val moved = Shortcut(target, newFile)
            // Only a cover stored in the source container is copied; one elsewhere is shared as it is.
            shortcut.customCoverArtPath?.takeIf { it.isNotEmpty() }?.let { File(it) }
                ?.takeIf { it.isFile && it.path.startsWith(source.rootDir.path + "/") }
                ?.let { src ->
                    val dst = File(target.rootDir, "app_data/cover_arts/${src.name}")
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                    written += dst
                    moved.putExtra("customCoverArtPath", dst.path)
                    oldCover = src
                }
            // A copy is a separate game: it gets its own home-screen id when it is pinned.
            if (keepCopy) moved.putExtra("uuid", null)
            moved.saveData()

            val info = CopyGameToDriveC.parse(shortcut)
            val exe = info.exeAndroid ?: throw IOException("the game's folder isn't reachable")
            val sameFile = runCatching { WinePath.resolveAndroidPath(target, info.exeWin) }.getOrNull()
                ?.absolutePath == exe.absolutePath
            if (!sameFile) {
                val drivesBefore = target.drives
                val newWin = CopyGameToDriveC.setShortcutExe(Shortcut(target, newFile), exe, info.argsSuffix)
                    ?: throw IOException("no free drive letter in \"${target.name}\" for the game's folder")
                if (target.drives != drivesBefore) addedDrive = newWin.take(2)
            }

            // The new entry must load like any other before the original goes.
            if (Shortcut(target, newFile).path.isBlank()) throw IOException("the new entry has no program to run")
        } catch (e: Exception) {
            Log.e(TAG, "move of '${shortcut.name}' to container ${target.id} failed", e)
            written.forEach { it.delete() }
            return Result.Refused("Couldn't ${if (keepCopy) "copy" else "move"} \"${shortcut.name}\": ${e.message ?: "unknown error"}.")
        }
        // Keeps its place in "Recently added".
        newFile.setLastModified(shortcut.file.lastModified())

        if (!keepCopy) {
            shortcut.file.delete()
            // A leftover .lnk would bring the game back into the source container.
            File(shortcut.file.parentFile, "${shortcut.name}.lnk").delete()
            oldCover?.delete()
            updatePin(context, Shortcut(target, newFile))
        }
        Log.i(TAG, "${if (keepCopy) "copied" else "moved"} '${shortcut.name}' from container ${source.id} to ${target.id}" +
            (addedDrive?.let { ", added drive $it" } ?: ""))
        return Result.Done(newFile, addedDrive)
    }

    /** What to tell the user about [result]. */
    fun message(result: Result, gameName: String, targetName: String, keepCopy: Boolean): String = when (result) {
        is Result.Refused -> result.reason
        is Result.Done -> (if (keepCopy) "Copied" else "Moved") + " \"$gameName\" to \"$targetName\"." +
            (result.addedDrive?.let { " Added drive $it to \"$targetName\" for it." } ?: "")
    }

    /** The intent a pinned home-screen icon of a Wine game opens. */
    fun pinIntent(context: Context, shortcut: Shortcut): Intent =
        Intent(context, XServerDisplayActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("container_id", shortcut.container.id)
            putExtra("shortcut_path", shortcut.file.path)
        }

    /** Points a pinned home-screen icon of [moved] (same id) at its new place. Nothing when it isn't pinned. */
    private fun updatePin(context: Context, moved: Shortcut) {
        val id = moved.getExtra("uuid").ifEmpty { return }
        try {
            val sm = context.getSystemService(ShortcutManager::class.java) ?: return
            if (sm.pinnedShortcuts.none { it.id == id }) return
            val info = ShortcutInfo.Builder(context, id)
                .setShortLabel(moved.name)
                .setLongLabel(moved.name)
                .setIntent(pinIntent(context, moved))
                .build()
            if (!sm.updateShortcuts(listOf(info))) Log.w(TAG, "home-screen icon of '${moved.name}' not updated (rate limited)")
        } catch (e: Exception) {
            Log.w(TAG, "home-screen icon of '${moved.name}' not updated", e)
        }
    }

    private fun iconOf(desktopText: String): String? = desktopText.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("Icon=") }
        ?.removePrefix("Icon=")
        ?.takeIf { it.isNotEmpty() }
}

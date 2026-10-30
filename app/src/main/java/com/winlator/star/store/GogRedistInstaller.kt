package com.winlator.star.store

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.winlator.star.R
import com.winlator.star.components.Component
import com.winlator.star.components.ComponentCatalog
import com.winlator.star.components.ComponentExecInstaller
import com.winlator.star.components.ComponentInstallRecords
import com.winlator.star.components.ComponentInstaller
import com.winlator.star.components.ComponentStep
import com.winlator.star.components.DependencyDetector
import com.winlator.star.components.StoreRedists
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.core.unpack.ReadBuffer
import com.winlator.star.core.unpack.SevenZip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GOG gap#3 P1 — auto-install a gen2 game's required GOG "dependencies" (VC++/.NET/DirectX/…
 * redistributables) into the container's Wine prefix so DRM/steam-free titles that link those
 * runtimes actually launch.
 *
 * This is the orchestrator that bridges two things we already have:
 *   - the game's required dep ids, captured at download time by
 *     {@link GogDownloadManager} into {@code gog_deps_<gameId>};
 *   - {@link GogDependencyRepository}, which resolves+assembles each redist installer; and
 *   - {@link ComponentExecInstaller}, the existing exec-installer plan/restart engine that RUNS an
 *     installer inside the container (so Windows registers the runtime) and drives multi-installer
 *     sequences across the app-restart a container session forces.
 *
 * A dep that maps to Win Components ([StoreRedists.gog]) which install straight into the prefix
 * (Visual C++, .NET, DirectX, PhysX, XNA, OpenAL…) is installed from those instead, with no
 * container session; only the rest go to GOG's own installers.
 *
 * Redists install into the CONTAINER's prefix (runtimes register system-wide, not per-game), so the
 * only valid trigger is at/after add-to-container — never at download time (there is no target yet).
 *
 * Clean-room: behaviour mirrors GameNative's {@code GOGDependencyFix} by NAME only; no GN Kotlin was
 * read. Everything here is reconstructed from the live GOG protocol + our own machinery.
 *
 * Fail-soft throughout: a redist that won't resolve/download/stage is logged and skipped; it never
 * hard-blocks the game or the other redists.
 */
object GogRedistInstaller {

    private const val PREFS = "bh_gog_prefs"

    /** Parsed {@code gog_deps_<gameId>} record. */
    data class DepsInfo(val deps: List<String>, val scriptInterpreter: Boolean)

    /**
     * Redists we know need the extraction fallback (self-extracting wrapper won't run cleanly under
     * our Wine/FEX — crack it open and run the inner .msi/.exe instead). EMPTY by default in P1: the
     * primary path always RUNS the real installer (which is what registers the runtime). The
     * extraction machinery below is wired and callable, but auto-triggering it on a runtime failure
     * needs the P3 mid-plan exit-code work (completion is currently inferred from session end).
     */
    private val NEEDS_EXTRACTION: Set<String> = emptySet()

    private fun mainHandler() = Handler(Looper.getMainLooper())

    private fun debug(ctx: Context, msg: String) = GogCloudSaveManager.debug(ctx, "REDIST: $msg")

    // ── Dep record --------------------------------------------------------------------------------

    /** Reads the persisted dep list for a game, or null when none was captured (gen1 / no deps). */
    @JvmStatic
    fun readGameDeps(ctx: Context, gameId: String): DepsInfo? {
        val raw = ctx.getSharedPreferences(PREFS, 0).getString("gog_deps_$gameId", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("deps") ?: JSONArray()
            val list = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            DepsInfo(list, o.optBoolean("scriptInterpreter", false))
        }.getOrNull()
    }

    /** True when this game declared at least one GOG dependency (redist). */
    @JvmStatic
    fun hasPrereqs(ctx: Context, gameId: String): Boolean =
        readGameDeps(ctx, gameId)?.deps?.isNotEmpty() == true

    // ── Triggers ----------------------------------------------------------------------------------

    /**
     * Post-add prompt: offered right after the game is added to a container (the moment we know the
     * target prefix). Shows what will be installed, warns a brief setup window will appear, and on
     * confirm drives the install. No-op (silent) when the game declared no deps.
     */
    @JvmStatic
    fun promptAndInstall(activity: Activity, container: Container, gameId: String) {
        val info = readGameDeps(activity, gameId)
        if (info == null || info.deps.isEmpty()) return
        mainHandler().post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val listStr = info.deps.joinToString(", ")
            AlertDialog.Builder(activity, R.style.StoreAlertDialogDark)
                .setTitle("Install prerequisites?")
                .setMessage(
                    "This game needs: $listStr.\n\n" +
                        "They'll be installed into \"${container.name}\" from Win Components, without " +
                        "opening the container. One with no matching component opens a brief setup " +
                        "window that closes on its own."
                )
                .setPositiveButton("Install") { _, _ -> installPrereqs(activity, container, gameId) }
                .setNegativeButton("Skip", null)
                .show()
        }
    }

    /**
     * Manual "Install prerequisites" entry (GOG detail page): picks a container (add the game to one
     * first if there are none), then runs {@link #promptAndInstall}.
     */
    @JvmStatic
    fun promptContainerAndInstall(activity: Activity, gameId: String) {
        if (!hasPrereqs(activity, gameId)) {
            mainHandler().post { toast(activity, "No prerequisites needed for this game.") }
            return
        }
        Thread {
            val containers = runCatching { ContainerManager(activity).containers }.getOrNull()
            mainHandler().post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (containers.isNullOrEmpty()) {
                    toast(activity, "Add this game to a container first.")
                    return@post
                }
                if (containers.size == 1) {
                    promptAndInstall(activity, containers[0], gameId)
                    return@post
                }
                val names = containers.map { it.name?.ifEmpty { "Container ${it.id}" } ?: "Container ${it.id}" }
                    .toTypedArray()
                AlertDialog.Builder(activity, R.style.StoreAlertDialogDark)
                    .setTitle("Install prerequisites into…")
                    .setItems(names) { _, which -> promptAndInstall(activity, containers[which], gameId) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.also { it.name = "gog-redist-picker" }.start()
    }

    // ── Install orchestration ---------------------------------------------------------------------

    /** One prerequisites run at a time: a second tap while one runs only says so. */
    private val running = AtomicBoolean(false)

    /**
     * Installs the game's deps from Win Components ([installFromComponents]) in a progress window.
     * Deps with no matching component, or whose install failed, are listed there with a button that
     * runs GOG's own installers for them ([installWithGogInstallers]), which opens the container.
     */
    @JvmStatic
    fun installPrereqs(activity: Activity, container: Container, gameId: String) {
        val info = readGameDeps(activity, gameId) ?: return
        if (info.deps.isEmpty()) return
        if (!running.compareAndSet(false, true)) {
            toast(activity, "Prerequisites are already being installed.")
            return
        }
        val app = activity.applicationContext
        debug(app, "start game=$gameId container=${container.id} deps=${info.deps} " +
            "scriptInterpreter=${info.scriptInterpreter}")
        val window = PrereqWindow(activity)
        Thread {
            try {
                val left = installFromComponents(app, container, info.deps, window)
                if (left.isEmpty()) {
                    window.finished("All prerequisites are installed.", null)
                } else {
                    window.finished(
                        "${left.joinToString(", ")} still needs GOG's own installer, which opens the container.",
                    ) { installWithGogInstallers(activity, container, gameId, left) }
                }
            } catch (e: Exception) {
                debug(app, "installPrereqs exception: $e")
                window.finished("Prerequisite install error: ${e.message ?: e.javaClass.simpleName}", null)
            } finally {
                running.set(false)
            }
        }.also { it.name = "gog-redist-install" }.start()
    }

    /**
     * Resolve → download → stage → build a plan → drive the exec-installer for [deps], the ones the
     * Win Components could not cover. Runs its network/assembly on a background thread, then launches
     * the container session on the main thread.
     */
    private fun installWithGogInstallers(activity: Activity, container: Container, gameId: String, deps: List<String>) {
        if (!running.compareAndSet(false, true)) {
            toast(activity, "Prerequisites are already being installed.")
            return
        }
        val app = activity.applicationContext
        Thread {
            val cancelled = AtomicBoolean(false)
            val log = ConcurrentLinkedQueue<String>()
            val steps = ArrayList<ComponentStep>()
            try {
                val repo = GogDependencyRepository.fetchRepository()
                debug(app, "repository build_id=${GogDependencyRepository.lastBuildId()} entries=${repo.size}")
                if (repo.isEmpty()) {
                    finish(activity, "Couldn't reach GOG's redist repository — launch anyway.")
                    return@Thread
                }
                val stageRoot = File(app.cacheDir, "gog_redist/$gameId").apply { mkdirs() }

                // Install in the game's declared dependency order (order matters for some stacks).
                for (depId in deps) {
                    val entry = repo[depId]
                    if (entry == null) { debug(app, "dep '$depId' not in repository — skip"); continue }
                    if (entry.internal) { debug(app, "dep '$depId' internal — skip"); continue }
                    val marker = "GOG:$depId"

                    val destDir = File(stageRoot, depId).apply { mkdirs() }
                    var installer = GogDependencyRepository.downloadRedist(entry, destDir, cancelled, log)
                    if (installer == null) {
                        debug(app, "dep '$depId' download/assemble FAILED — skip (game proceeds)")
                        continue
                    }
                    var action = if (entry.isMsi()) "install_msi" else "install_exe"
                    var args = entry.arguments

                    // Fallback: crack a stubborn self-extracting wrapper and run the inner installer.
                    if (depId in NEEDS_EXTRACTION) {
                        val inner = extractInnerInstaller(app, installer, log)
                        if (inner != null) {
                            installer = inner.first
                            action = if (inner.first.extension.equals("msi", true)) "install_msi" else "install_exe"
                            args = inner.second
                            debug(app, "dep '$depId' extraction fallback → ${installer.name}")
                        } else {
                            debug(app, "dep '$depId' extraction fallback failed — using original installer")
                        }
                    }

                    steps.add(buildStep(action, installer, args, marker))
                    debug(app, "resolved '$depId' → ${installer.name} args='$args' " +
                        "size=${entry.size} staged=${installer.absolutePath}")
                }

                for (line in log) debug(app, line)

                if (steps.isEmpty()) {
                    finish(activity, "Prerequisites already installed (or nothing to do).")
                    return@Thread
                }

                // ONE umbrella component with an ordered install_exe/msi step per redist. The
                // exec-installer's plan engine drives each as its own container session and resumes
                // across the app-restart a session forces — so a multi-redist game (e.g. Witcher 2 EE)
                // is driven to completion automatically. Each step carries its own GOG:<depId> marker.
                val component = Component(
                    name = "GOG Prerequisites",
                    description = "Auto-installed GOG redistributables",
                    provider = "gog",
                    status = "ready",
                    dependencies = emptyList(),
                    steps = steps,
                )
                debug(app, "plan launched: ${steps.size} redist step(s) into container ${container.id}")
                mainHandler().post {
                    if (activity.isFinishing || activity.isDestroyed) {
                        // Activity gone: the plan is persisted only once startInstall runs, so if we
                        // can't launch, just report. (Manual re-run from the detail page recovers.)
                        return@post
                    }
                    val res = ComponentExecInstaller.startInstall(app, container, component) {}
                    when (res) {
                        is ComponentExecInstaller.Result.Launched ->
                            toast(activity, "Installing prerequisites — a setup window will appear.")
                        is ComponentExecInstaller.Result.Done ->
                            toast(activity, "Prerequisites installed.")
                        is ComponentExecInstaller.Result.Error -> {
                            debug(app, "startInstall error: ${res.message}")
                            toast(activity, "Couldn't start prerequisite install: ${res.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                debug(app, "installPrereqs exception: $e")
                finish(activity, "Prerequisite install error — launch anyway.")
            } finally {
                running.set(false)
            }
        }.also { it.name = "gog-redist-install" }.start()
    }

    /** A component to install for a dep, and its row in the progress window. */
    private class Planned(val depId: String, val name: String, val component: Component?, val row: Int)

    /**
     * Installs, in order, the Win Components each of [deps] maps to ([StoreRedists.gog]; the
     * file-drop `_dll` variant first, as the Win Components screens do) when all of them install
     * without opening the container, showing each in [window]. Returns the deps left for GOG's own
     * installers: no matching component, one that needs its installer, or an install that failed.
     * A dep counts as done when its components are installed, not by its `GOG:<depId>` marker (a
     * marker can be recorded before GOG's installer has run). Runs on the caller's background thread.
     */
    private fun installFromComponents(app: Context, container: Container, deps: List<String>, window: PrereqWindow): List<String> {
        if (ComponentCatalog.byName.isEmpty()) runCatching { ComponentCatalog.load() }
        val installed = ComponentInstallRecords.verified(app, container)
        val lines = ArrayList<String>()
        val plan = LinkedHashMap<String, List<Planned>?>()
        for (depId in deps) {
            val names = StoreRedists.gog(depId)
            val components = names.map { ComponentCatalog.byName["${it}_dll"] ?: ComponentCatalog.byName[it] }
            if (names.isEmpty() || components.any { it == null || !ComponentInstaller.isInstallable(it) }) {
                debug(app, "dep '$depId' → ${names.ifEmpty { listOf("no component") }}: GOG's installer")
                plan[depId] = null
                lines += "$depId — needs GOG's installer"
                continue
            }
            plan[depId] = names.zip(components).map { (name, c) ->
                lines += "$depId → ${DependencyDetector.labelOf(name)} — " + if (name in installed) "already installed" else "waiting"
                Planned(depId, name, c, lines.size - 1)
            }
        }
        val todo = plan.values.filterNotNull().flatten().filter { it.name !in installed }
        window.show(lines, todo.size)

        val left = ArrayList<String>()
        var done = 0
        for ((depId, items) in plan) {
            if (items == null) { left += depId; continue }
            var ok = true
            for (p in items) {
                if (p.name in installed) continue
                val label = "$depId → ${DependencyDetector.labelOf(p.name)}"
                if (!ok) { window.row(p.row, "$label — skipped"); continue }
                window.row(p.row, "$label — installing")
                val c = p.component ?: continue
                val error = ComponentInstaller.install(app, container, c, { f ->
                    window.progress(done, f, "$label: ${(f * 100).toInt()} %")
                }, { stage -> window.progress(done, -1f, "$label: $stage") })
                done++
                if (error == null) {
                    ComponentInstallRecords.add(app, container.id, p.name)
                    window.row(p.row, "$label — done")
                } else {
                    debug(app, "dep '$depId': ${c.name} failed ($error)")
                    window.row(p.row, "$label — failed: $error")
                    ok = false
                }
                window.progress(done, 0f, "")
            }
            if (ok) {
                ComponentInstallRecords.add(app, container.id, "GOG:$depId")
                debug(app, "dep '$depId' installed from Win Components: ${items.map { it.name }}")
            } else {
                left += depId
            }
        }
        return left
    }

    /**
     * The prerequisites window: one line per dep → component with its state, the current step, and
     * an overall bar. [finished] turns Hide into Close and, when [finished]'s action is given, shows
     * the button that runs GOG's installers. Safe to call from any thread.
     */
    private class PrereqWindow(private val activity: Activity) {
        private var dialog: AlertDialog? = null
        private var hidden = false
        private val rows = ArrayList<TextView>()
        private lateinit var stage: TextView
        private lateinit var bar: ProgressBar
        private var total = 1

        private fun ui(block: () -> Unit) = mainHandler().post {
            if (!activity.isFinishing && !activity.isDestroyed) block()
        }

        private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        fun show(lines: List<String>, count: Int) = ui {
            total = count.coerceAtLeast(1)
            val ctx = ContextThemeWrapper(activity, R.style.StoreAlertDialogDark)
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
            }
            for (line in lines) {
                val t = TextView(ctx).apply { text = line; setTextColor(0xFFE6E6EA.toInt()); setPadding(0, dp(4), 0, dp(4)) }
                rows += t
                box.addView(t)
            }
            bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
            box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) })
            stage = TextView(ctx).apply { textSize = 12f; setTextColor(0xFFB4B4BE.toInt()); setPadding(0, dp(4), 0, dp(8)) }
            box.addView(stage)
            dialog = AlertDialog.Builder(activity, R.style.StoreAlertDialogDark)
                .setTitle("Installing prerequisites")
                .setView(ScrollView(ctx).apply { addView(box) })
                .setCancelable(false)
                .setPositiveButton("Hide") { _, _ -> hidden = true }
                .setNeutralButton("Use GOG's installer", null)
                .show()
            dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
        }

        fun row(i: Int, text: String) = ui { rows.getOrNull(i)?.text = text }

        /** [done] components finished, the current one at [fraction] (negative = unchanged), [text] the step. */
        fun progress(done: Int, fraction: Float, text: String) = ui {
            if (!::bar.isInitialized) return@ui
            if (fraction >= 0) bar.progress = ((done + fraction.coerceIn(0f, 1f)) / total * 1000).toInt()
            stage.text = text
        }

        fun finished(message: String, gog: (() -> Unit)?) = ui {
            val d = dialog
            if (d == null || hidden || !d.isShowing) {
                toast(activity, message)
                return@ui
            }
            if (::bar.isInitialized) bar.progress = 1000
            stage.text = message
            d.setTitle(if (gog == null) "Prerequisites installed" else "Prerequisites")
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "Close"
            if (gog != null) {
                d.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                    visibility = View.VISIBLE
                    setOnClickListener { d.dismiss(); gog() }
                }
            }
        }
    }

    /** Build one exec-installer step pointing at a pre-staged local installer + its idempotency marker. */
    private fun buildStep(action: String, installer: File, arguments: String, marker: String): ComponentStep {
        val env = JSONObject()
            .put("local_file", installer.absolutePath)
            .put("file_name", installer.name)
            .put("arguments", arguments ?: "")
            .put("component_marker", marker)
        val obj = JSONObject().put("action", action).put("environment", env)
        return ComponentStep(action, obj)
    }

    // ── Extraction fallback -----------------------------------------------------------------------

    /**
     * Cracks a self-extracting redist wrapper with the File Manager's 7-Zip engine and returns the
     * inner installer to run instead ({@code file} to {@code arguments}). Prefers an inner {@code .msi}
     * (→ msiexec /qn), else an inner {@code .exe}. Returns null if extraction fails or yields nothing
     * usable — the caller then falls back to running the original wrapper.
     *
     * This is the plan's "reuse the in-app File Manager extract methods to pull the inner installer";
     * running still does the registering, extraction is only the unpack tool.
     */
    private fun extractInnerInstaller(
        ctx: Context, wrapper: File, log: ConcurrentLinkedQueue<String>,
    ): Pair<File, String>? {
        if (!SevenZip.isAvailable(ctx)) { log.add("7z unavailable — no extraction fallback"); return null }
        val dest = File(ctx.cacheDir, "gog_redist_extract/${wrapper.nameWithoutExtension}").apply {
            deleteRecursively(); mkdirs()
        }
        val res = runCatching {
            SevenZip.extract(ctx, wrapper, dest, mmt = 1, bufferBytes = ReadBuffer.MB1.bytes,
                listener = object : SevenZip.Listener {
                    override fun onProgress(percent: Int, currentFile: String?) {}
                    override fun onFile(name: String) {}
                }, onProcess = {})
        }.getOrNull()
        if (res == null || res.exitCode > 1) { log.add("extraction exit=${res?.exitCode}"); return null }

        // Run the inner installer with no extra args (Wine associates .msi with msiexec and runs an
        // .exe directly); the exec-installer shows its wizard, matching the visible-wizard model.
        val files = dest.walkTopDown().filter { it.isFile }.toList()
        files.firstOrNull { it.extension.equals("msi", true) }?.let { return it to "" }
        files.firstOrNull {
            it.extension.equals("exe", true) && !it.name.equals(wrapper.name, ignoreCase = true)
        }?.let { return it to "" }
        log.add("extraction produced no inner installer")
        return null
    }

    // ── UI helpers --------------------------------------------------------------------------------

    private fun finish(activity: Activity, msg: String) = mainHandler().post {
        if (!activity.isFinishing && !activity.isDestroyed) toast(activity, msg)
    }

    /**
     * Activities that host redist install feedback implement this so messages render on the shared
     * themed bar instead of a system Toast (which draws as an unreadable black box on this ROM,
     * targetSDK 28). GogGameDetailActivity implements it; other callers fall back to Toast.
     */
    interface Host { fun onRedistMessage(msg: String) }

    private fun toast(ctx: Context, msg: String) {
        if (ctx is Host) ctx.onRedistMessage(msg)
        else Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
}

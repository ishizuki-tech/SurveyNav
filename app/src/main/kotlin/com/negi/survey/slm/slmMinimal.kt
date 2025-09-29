@file:Suppress("MemberVisibilityCanBePrivate", "unused") // some members are intentionally public for tests/integration
package com.negi.survey.slm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/* ============================================================
   SLM — Small Language Model helper (text-only)
   - Thin, safe wrapper around MediaPipe LlmInference + session.
   - Focus: safe init/dispose, single streaming session per model instance,
     cancellation + deferred cleanup, and a simple streaming callback surface.
   - This refactor enforces "two-phase" resource ops: never close/create under a lock.
   ============================================================ */

/* -----------------------
   Config / Defaults
   ----------------------- */

/** Accelerator preference label used in model configuration. */
enum class Accelerator(val label: String) { CPU("CPU"), GPU("GPU") }

/** Keys used in the model's config map. */
enum class ConfigKey { MAX_TOKENS, TOP_K, TOP_P, TEMPERATURE, ACCELERATOR }

private const val DEFAULT_MAX_TOKEN = 256
private const val DEFAULT_TOP_K = 40
private const val DEFAULT_TOP_P = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f

/* -----------------------
   Model holder and helpers
   ----------------------- */

/**
 * Describes a model artifact and runtime configuration.
 *
 * @property name friendly model name used for logging and keys.
 * @property taskPath filesystem path to MediaPipe task file.
 * @property config map of configuration values indexed by ConfigKey.
 * @property instance runtime container (engine + session) once initialized; volatile because it
 *         is mutated by multiple threads.
 */
data class Model(
    val name: String,
    private val taskPath: String,
    val config: Map<ConfigKey, Any> = emptyMap(),
    @Volatile var instance: LlmModelInstance? = null
) {
    /** Return the configured task path for this model. */
    fun getPath(): String = taskPath

    /** Safely read an Int-valued config (accepts Number or numeric String). */
    fun getIntConfigValue(key: ConfigKey, defaultValue: Int): Int =
        (config[key] as? Number)?.toInt()
            ?: (config[key] as? String)?.toIntOrNull()
            ?: defaultValue

    /** Safely read a Float-valued config (accepts Number or numeric String). */
    fun getFloatConfigValue(key: ConfigKey, defaultValue: Float): Float =
        when (val v = config[key]) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: defaultValue
            else -> defaultValue
        }

    /** Safely read a String-valued config. */
    fun getStringConfigValue(key: ConfigKey, defaultValue: String): String =
        (config[key] as? String) ?: defaultValue
}

/* -----------------------
   Internal types and utilities
   ----------------------- */

private const val TAG = "SLM"

/**
 * If true, the helper concatenates all partials and emits the full text on done=true.
 * If false, it emits partials as-is and only the last chunk at done=true.
 */
private const val EMIT_FULL_TEXT_ON_DONE = true

/** Callback invoked for every partial update and once on completion. */
typealias ResultListener = (partialResult: String, done: Boolean) -> Unit

/** Callback invoked when the model has been fully cleaned up (immediate or deferred). */
typealias CleanUpListener = () -> Unit

/** Execution state for a Model session. */
enum class RunState { IDLE, RUNNING, CANCELLING }

/**
 * Runtime container for an initialized engine + session.
 *
 * - `state` guards concurrent runInference calls (at most one concurrent generator).
 * - `pendingCleanup` is set when cleanup() is requested while RUNNING; actual destruction
 *    is performed when the streaming callback observes done=true.
 */
data class LlmModelInstance(
    val engine: LlmInference,
    @Volatile var session: LlmInferenceSession,
    val state: AtomicReference<RunState> = AtomicReference(RunState.IDLE),
    val pendingCleanup: AtomicBoolean = AtomicBoolean(false)
)

/** Normalize MediaPipe task error messages to a short, user-visible string. */
private fun cleanUpMediapipeTaskErrorMessage(msg: String): String =
    msg.replace("INTERNAL:", "").replace(Regex("\\s+"), " ").trim()

/* Param sanitizers */
private fun sanitizeTopK(k: Int): Int = if (k < 1) 1 else k
private fun sanitizeTopP(p: Float): Float = when { p.isNaN() -> DEFAULT_TOP_P; p < 0f -> 0f; p > 1f -> 1f; else -> p }
private fun sanitizeTemperature(t: Float): Float = when { t.isNaN() -> DEFAULT_TEMPERATURE; t < 0f -> 0f; t > 2f -> 2f; else -> t }

/** Create a stable key for maps using model identity — avoids collisions for same-name models. */
private fun keyOf(model: Model) = "${model.name}#${System.identityHashCode(model)}"

/* =======================
   SLM - public helper object
   ======================= */

/**
 * SLM is a concurrency-safe helper that manages:
 *  - creating an LlmInference engine and LlmInferenceSession,
 *  - running a single streaming generation at a time per Model,
 *  - cancelling and deferring cleanup safely while streaming,
 *  - notifying callers via ResultListener and CleanUpListener.
 *
 * Design rule:
 *  - Never call session/engine close() under a lock. Use two-phase resource ops.
 */
object SLM {

    // modelKey -> cleanup listener
    private val cleanUpListeners: MutableMap<String, CleanUpListener> = ConcurrentHashMap()

    /** @return true if the model exists and is not IDLE. */
    fun isBusy(model: Model): Boolean =
        model.instance?.state?.get()?.let { it != RunState.IDLE } == true

    /* ------------------------
       initialize (two-phase close before re-init)
       ------------------------ */
    @Synchronized
    fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
        // Phase A (short sync): detach old instance if idle
        var toCloseEngine: LlmInference? = null
        var toCloseSession: LlmInferenceSession? = null

        model.instance?.let { inst ->
            val s = inst.state.get()
            if (s != RunState.IDLE) {
                onDone("Model '${model.name}' is busy ($s). Try again after done=true or call cancel().")
                return
            }
            toCloseSession = inst.session
            toCloseEngine = inst.engine
            cleanUpListeners.remove(keyOf(model))?.runCatching { invoke() }
            model.instance = null
        }

        // Phase B (no lock): physically close old resources
        tryCloseQuietly(toCloseSession)
        safeClose(toCloseEngine)

        // Build options
        val maxTokens = model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN)
        val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K))
        val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P))
        val temperature = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))
        val accelLabel = model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)

        val preferredBackend = when (accelLabel) {
            Accelerator.CPU.label -> LlmInference.Backend.CPU
            Accelerator.GPU.label -> LlmInference.Backend.GPU
            else -> LlmInference.Backend.GPU
        }

        val baseOptionsBuilder = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.getPath())
            .setMaxTokens(maxTokens)

        fun createEngine(backend: LlmInference.Backend): LlmInference {
            val opts = baseOptionsBuilder.setPreferredBackend(backend).build()
            return LlmInference.createFromOptions(context, opts)
        }

        val engine: LlmInference = try {
            createEngine(preferredBackend)
        } catch (e: Exception) {
            if (preferredBackend != LlmInference.Backend.CPU) {
                Log.w(TAG, "GPU init failed; falling back to CPU. reason=${e.message}")
                try {
                    createEngine(LlmInference.Backend.CPU)
                } catch (e2: Exception) {
                    onDone(cleanUpMediapipeTaskErrorMessage(e2.message ?: "Unknown error")); return
                }
            } else {
                onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error")); return
            }
        }

        try {
            val session = buildSessionFromModel(engine, topK, topP, temperature)
            synchronized(this) { model.instance = LlmModelInstance(engine, session) }
            Log.i(TAG, "initialized: backend=$preferredBackend maxTokens=$maxTokens")
            onDone("")
        } catch (e: Exception) {
            safeClose(engine)
            onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
        }
    }

    /* ------------------------
       resetSession (two-phase; reuse engine)
       ------------------------ */
    /**
     * Soft-reset the session (reuse engine). Two-phase to avoid closing under a lock.
     * @return true if reset performed; false if skipped or failed.
     */
    fun resetSession(model: Model): Boolean {
        // Phase A (sync): snapshot current refs & params
        val snap = synchronized(this) {
            val inst = model.instance ?: return false
            if (inst.state.get() != RunState.IDLE) return false
            data class Snap(
                val engine: LlmInference,
                val oldSession: LlmInferenceSession,
                val topK: Int,
                val topP: Float,
                val temperature: Float
            )
            Snap(
                engine = inst.engine,
                oldSession = inst.session,
                topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K)),
                topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P)),
                temperature = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))
            )
        } ?: return false

        // Phase B (no lock): close old & create new
        tryCloseQuietly(snap.oldSession)
        val newSession = try {
            buildSessionFromModel(snap.engine, snap.topK, snap.topP, snap.temperature)
        } catch (t: Throwable) {
            Log.e(TAG, "resetSession: create new session failed: ${t.message}", t)
            return false
        }

        // Phase C (sync): validate & swap
        synchronized(this) {
            val inst = model.instance ?: run { tryCloseQuietly(newSession); return false }
            if (inst.engine !== snap.engine || inst.state.get() != RunState.IDLE) {
                tryCloseQuietly(newSession); return false
            }
            inst.session = newSession
            Log.d(TAG, "resetSession: swapped to new session@${System.identityHashCode(newSession)}")
        }
        return true
    }

    /* ------------------------
       cleanUp (two-phase immediate when idle)
       ------------------------ */
    @Synchronized
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val inst = model.instance ?: return onDone()
        val state = inst.state.get()
        if (state != RunState.IDLE) {
            inst.pendingCleanup.set(true)
            runCatching { inst.session.cancelGenerateResponseAsync() }
            Log.i(TAG, "Clean up deferred (state=$state) for model='${model.name}'.")
            onDone(); return
        }

        // Phase A (sync): detach from public state
        val engineToClose = inst.engine
        val sessionToClose = inst.session
        cleanUpListeners.remove(keyOf(model))?.runCatching { invoke() }
        model.instance = null

        // Phase B (no lock): physically destroy
        tryCloseQuietly(sessionToClose)
        safeClose(engineToClose)
        Log.d(TAG, "Clean up done for model='${model.name}'.")
        onDone()
    }

    /** Internal helper used when deferred cleanup was requested and streaming just finished. */
    private fun doCleanupNow(model: Model) {
        val inst = model.instance ?: return
        // two-phase: detach then destroy
        val engineToClose = inst.engine
        val sessionToClose = inst.session
        cleanUpListeners.remove(keyOf(model))?.runCatching { invoke() }
        model.instance = null
        tryCloseQuietly(sessionToClose)
        safeClose(engineToClose)
        Log.d(TAG, "Deferred clean up executed for model='${model.name}'.")
    }

    /* ------------------------
       cancel
       ------------------------ */
    @Synchronized
    fun cancel(model: Model) {
        val inst = model.instance ?: return
        if (inst.state.compareAndSet(RunState.RUNNING, RunState.CANCELLING)) {
            runCatching { inst.session.cancelGenerateResponseAsync() }
            Log.i(TAG, "cancel(): requested; waiting for done=true to settle.")
        } else {
            Log.i(TAG, "cancel(): no-op (state=${inst.state.get()})")
        }
    }

    /* ------------------------
       runInference (streaming)
       ------------------------ */
    fun runInference(
        model: Model,
        input: String,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener
    ) {
        val inst = model.instance ?: run {
            Log.w(TAG, "runInference called before initialize; ignoring.")
            resultListener("Model '${model.name}' is not initialized.", true)
            return
        }

        // Enforce single concurrent run per model instance.
        if (!inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)) {
            Log.w(TAG, "runInference while busy; ignoring. state=${inst.state.get()}")
            resultListener("Previous invocation still processing. Wait for done=true.", true)
            cancel(model)
            inst.state.set(RunState.IDLE)
            return
        }

        // Register cleanup listener just before start
        cleanUpListeners[keyOf(model)] = cleanUpListener

        val text = input.trim()
        try {
            if (text.isNotEmpty()) inst.session.addQueryChunk(text)
        } catch (e: Exception) {
            inst.state.set(RunState.IDLE)
            resetSession(model)
            if (text.isNotEmpty()) model.instance?.session?.addQueryChunk(text)
        }

        val session = inst.session
        val buf = StringBuilder()

        try {
            Log.d(TAG, "streaming started (len=${text.length})")

            // MediaPipe callback likely runs on worker thread.
            session.generateResponseAsync { partial, done ->
                try {
                    if (!done) {
                        if (partial.isNotEmpty()) {
                            runCatching { resultListener(partial, false) }
                                .onFailure { Log.e(TAG, "Result listener threw: ${it.message}", it) }
                        }
                        buf.append(partial)
                    } else {
                        if (EMIT_FULL_TEXT_ON_DONE) {
                            runCatching { resultListener(buf.append(partial).toString(), true) }
                                .onFailure { Log.e(TAG, "Result listener threw at done: ${it.message}", it) }
                        } else {
                            runCatching { resultListener(partial, true) }
                                .onFailure { Log.e(TAG, "Result listener threw at done: ${it.message}", it) }
                        }
                    }
                } finally {
                    if (done) {
                        val prev = inst.state.getAndSet(RunState.IDLE)
                        try {
                            if (inst.pendingCleanup.compareAndSet(true, false)) doCleanupNow(model)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Deferred cleanup failed", t)
                        } finally {
                            cleanUpListeners.remove(keyOf(model))?.runCatching { invoke() }
                                ?.onFailure { Log.e(TAG, "CleanUpListener threw: ${it.message}", it) }
                        }
                        Log.d(TAG, "runInference done (prevState=$prev).")
                    }
                }
            }
        } catch (e: Exception) {
            // Failed to start streaming
            cleanUpListeners.remove(keyOf(model))
            inst.state.set(RunState.IDLE)
            resultListener(cleanUpMediapipeTaskErrorMessage(e.message ?: "Failed to start generation"), true)
        }
    }

    /* ------------------------
       Session construction helper
       ------------------------ */
    private fun buildSessionFromModel(
        engine: LlmInference,
        topK: Int,
        topP: Float,
        temperature: Float
    ): LlmInferenceSession =
        LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(topK)
                .setTopP(topP)
                .setTemperature(temperature)
                .build()
        )

    /* ------------------------
       Safe close helpers (must be called without holding SLM monitor)
       ------------------------ */
    private fun tryCloseQuietly(session: LlmInferenceSession?) {
        if (session == null) return
        try {
            runCatching { session.cancelGenerateResponseAsync() }
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "Session close failed: ${e.message}")
        }
    }

    private fun safeClose(engine: LlmInference?) {
        if (engine == null) return
        try {
            engine.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close engine: ${e.message}")
        }
    }
}

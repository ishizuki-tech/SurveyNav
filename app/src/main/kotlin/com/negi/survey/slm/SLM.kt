/*
 * A concurrency-safe helper for managing MediaPipe LLM inference sessions in Android.
 * Safe init, inference, cancel, and cleanup with session reuse.
 * - Generation token (runId) guards against stale callbacks
 * - Reset swaps sessions atomically (new->old) to avoid broken state
 * - BusyPolicy controls behavior when a run is in progress
 * - Supports text-only, audio-only (WAV mono), and text+audio prompts
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")
package com.negi.survey.slm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.AudioModelOptions
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Hardware accelerator options for inference (CPU or GPU)
enum class Accelerator(val label: String) { CPU("CPU"), GPU("GPU") }

// Configuration keys for LLM inference
enum class ConfigKey { MAX_TOKENS, TOP_K, TOP_P, TEMPERATURE, ACCELERATOR }

// Busy behavior when a run is in progress
enum class BusyPolicy { Reject, CancelAndStart }

// Default values for model parameters
private const val DEFAULT_MAX_TOKEN = 256
private const val DEFAULT_TOP_K = 40
private const val DEFAULT_TOP_P = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f

private const val TAG = "SLM"

// Callback to deliver partial or final inference results
typealias ResultListener = (partialResult: String, done: Boolean) -> Unit
// Callback to notify when the model is cleaned up
typealias CleanUpListener = () -> Unit

// Execution states of a model instance
enum class RunState { IDLE, RUNNING, CANCELLING }

/** Represents a loaded LLM model configuration and runtime instance. */
data class Model(
    val name: String,
    val taskPath: String,
    val config: Map<ConfigKey, Any> = emptyMap(),
    @Volatile var instance: LlmModelInstance? = null
) {
    fun getPath() = taskPath
    fun getIntConfigValue(key: ConfigKey, default: Int) =
        (config[key] as? Number)?.toInt()
            ?: (config[key] as? String)?.toIntOrNull()
            ?: default
    fun getFloatConfigValue(key: ConfigKey, default: Float) =
        when (val v = config[key]) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: default
            else -> default
        }
    fun getStringConfigValue(key: ConfigKey, default: String) =
        (config[key] as? String) ?: default
}

/** Holds the initialized engine and session for a model. */
data class LlmModelInstance(
    val engine: LlmInference,
    @Volatile var session: LlmInferenceSession,
    val state: AtomicReference<RunState> = AtomicReference(RunState.IDLE),
    val runId: AtomicLong = AtomicLong(0L) // generation token for stale-callback guard
)

/** SLM: Safe Language Model inference helper. */
object SLM {

    /** Audio input holder (mono WAV bytes). */
    data class AudioInput(val wavBytes: ByteArray)

    /** Keep onClean paired with runId to avoid double-firing or mix-up across generations. */
    private data class CleanEntry(val runId: Long, val listener: CleanUpListener)
    private val cleanUpEntries = ConcurrentHashMap<String, CleanEntry>()

    fun isBusy(model: Model): Boolean =
        model.instance?.state?.get()?.let { it != RunState.IDLE } == true

    /** Initialize or reinitialize the engine + session (tears down any previous idle instance). */
    @Synchronized
    fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
        val appCtx = context.applicationContext
        var oldEngine: LlmInference? = null
        var oldSession: LlmInferenceSession? = null

        // Tear down a previous idle instance if present
        model.instance?.let { inst ->
            if (inst.state.get() != RunState.IDLE) {
                onDone("Model '${model.name}' is busy. Try again after done=true or call cancel().")
                return
            }
            oldSession = inst.session
            oldEngine = inst.engine
            cleanUpEntries.remove(keyOf(model)) // Drop any stale onClean
            model.instance = null
        }
        tryCloseQuietly(oldSession)
        safeClose(oldEngine)

        val maxTokens = model.getIntConfigValue(ConfigKey.MAX_TOKENS, DEFAULT_MAX_TOKEN)
        val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K))
        val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P))
        val temp = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))
        val backendPref = model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)
        val backend = when (backendPref) {
            Accelerator.CPU.label -> LlmInference.Backend.CPU
            else -> LlmInference.Backend.GPU
        }

        val baseOpts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.getPath())
            .setAudioModelOptions(AudioModelOptions.builder().build())
            .setMaxTokens(maxTokens)

        val engine = try {
            LlmInference.createFromOptions(appCtx, baseOpts.setPreferredBackend(backend).build())
        } catch (e: Exception) {
            if (backend == LlmInference.Backend.GPU) {
                Log.w(TAG, "GPU init failed. Falling back to CPU. cause=${e.message}")
                try {
                    LlmInference.createFromOptions(
                        appCtx,
                        baseOpts.setPreferredBackend(LlmInference.Backend.CPU).build()
                    )
                } catch (e2: Exception) {
                    onDone(cleanError(e2.message)); return
                }
            } else {
                onDone(cleanError(e.message)); return
            }
        }

        try {
            val session = buildSessionFromModel(engine, topK, topP, temp)
            model.instance = LlmModelInstance(engine, session)
            onDone("")
        } catch (e: Exception) {
            safeClose(engine)
            onDone(cleanError(e.message))
        }
    }

    /** Text-only with explicit BusyPolicy. */
    fun runInference(
        model: Model,
        input: String,
        busyPolicy: BusyPolicy,
        listener: ResultListener,
        onClean: CleanUpListener
    ) = runInference(model, input, null, busyPolicy, listener, onClean)

    /** Text-only (backward-compatible overload; defaults to CancelAndStart). */
    fun runInference(
        model: Model,
        input: String,
        listener: ResultListener,
        onClean: CleanUpListener
    ) = runInference(model, input, null, BusyPolicy.CancelAndStart, listener, onClean)

    /** Audio-only (mono WAV). */
    fun runInferenceAudio(
        model: Model,
        audio: AudioInput,
        busyPolicy: BusyPolicy = BusyPolicy.CancelAndStart,
        listener: ResultListener,
        onClean: CleanUpListener
    ) = runInference(model, null, audio, busyPolicy, listener, onClean)

    /**
     * Unified entry point for Text+Audio / Text-only / Audio-only.
     * Enqueues text first (if any), then audio (if any), then starts async generation.
     */
    fun runInference(
        model: Model,
        text: String?,
        audio: AudioInput?,
        busyPolicy: BusyPolicy,
        listener: ResultListener,
        onClean: CleanUpListener
    ) {
        val inst = model.instance ?: return listener("Model not initialized.", true)

        // Reject if nothing to send
        if ((text == null || text.isBlank()) && (audio == null || audio.wavBytes.isEmpty())) {
            listener("Empty prompt: provide text and/or audio.", true)
            return
        }

        // Busy handling
        if (!inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)) {
            when (busyPolicy) {
                BusyPolicy.Reject -> {
                    listener("Model '${model.name}' is busy.", true)
                    return
                }
                BusyPolicy.CancelAndStart -> {
                    cancel(model) // Advance runId and invalidate previous callbacks
                    if (!inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)) {
                        listener("Model '${model.name}' still busy after cancel.", true)
                        return
                    }
                }
            }
        }

        Log.d(TAG,"runInference model='${model.name}' textLen=${text?.length ?: 0}, audioLen=${audio?.wavBytes?.size ?: 0}")

        // Generation token
        val token = inst.runId.incrementAndGet()
        cleanUpEntries[keyOf(model)] = CleanEntry(token, onClean)

        try {
            // Enqueue inputs (text -> audio)
            text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                inst.session.addQueryChunk(it)
            }
            audio?.wavBytes?.let { bytes ->
                inst.session.tryAddAudioWav(bytes)  // version-agnostic addAudio*
                inst.session.tryFinishAudioInput()  // signal EOS if required by the SDK
            }

            // Async generation
            inst.session.generateResponseAsync { partial, done ->
                // Guard against stale callbacks from older generations
                if (token != inst.runId.get()) return@generateResponseAsync

                val preview =
                    if (partial.length > 256) partial.take(128) + " … " + partial.takeLast(64) else partial
                Log.d(TAG, "partial[len=${partial.length}, done=$done]: $preview")

                if (!done) {
                    listener(partial, false)
                } else {
                    try {
                        listener(partial, true)
                    } finally {
                        inst.state.set(RunState.IDLE)
                        cleanUpEntries.remove(keyOf(model))?.let { entry ->
                            if (entry.runId == token) entry.listener.invoke()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "runInference failed: ${t.message}", t)
            inst.state.set(RunState.IDLE)
            cleanUpEntries.remove(keyOf(model))?.let { entry ->
                if (entry.runId == token) entry.listener.invoke()
            }
            listener(cleanError(t.message), true)
        }
    }

    /**
     * Rebuilds the session with updated options.
     * Creates the new session first, then atomically swaps if still IDLE; otherwise discards the new one.
     */
    fun resetSession(model: Model): Boolean {
        // Snapshot (must be IDLE)
        val inst = synchronized(this) {
            val i = model.instance ?: return false
            if (i.state.get() != RunState.IDLE) return false
            i
        }

        // Build new session first (keep old alive on failure)
        val newSession = try {
            val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K))
            val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P))
            val temp = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))
            buildSessionFromModel(inst.engine, topK, topP, temp)
        } catch (e: Exception) {
            Log.e(TAG, "Session reset build failed: ${e.message}")
            return false
        }

        var old: LlmInferenceSession? = null
        val swapped = synchronized(this) {
            val cur = model.instance
            if (cur == null || cur !== inst || cur.state.get() != RunState.IDLE) {
                false
            } else {
                old = cur.session
                cur.session = newSession
                true
            }
        }

        return if (swapped) {
            tryCloseQuietly(old) // Close old after a successful swap
            true
        } else {
            tryCloseQuietly(newSession) // Discard new if swap not possible
            false
        }
    }

    /**
     * Fully tears down the instance. If running, cancels and invalidates callbacks,
     * then closes session/engine and clears the model's instance.
     */
    @Synchronized
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val inst = model.instance ?: return onDone()
        if (inst.state.get() != RunState.IDLE) {
            inst.state.set(RunState.CANCELLING)
            // Capture previous generation id before incrementing
            val prev = inst.runId.getAndIncrement()
            inst.session.cancelGenerateResponseAsync()
            inst.state.set(RunState.IDLE)
            cleanUpEntries.remove(keyOf(model))?.let { entry ->
                if (entry.runId == prev) entry.listener.invoke()
            }
        } else {
            cleanUpEntries.remove(keyOf(model))
        }
        model.instance = null
        tryCloseQuietly(inst.session)
        safeClose(inst.engine)
        onDone()
    }

    /**
     * Cancels an in-flight generation. Advances runId to invalidate stale callbacks.
     * onClean is fired for the generation being canceled (if present).
     */
    @Synchronized
    fun cancel(model: Model) {
        val inst = model.instance ?: return
        if (inst.state.get() != RunState.IDLE) {
            inst.state.set(RunState.CANCELLING)
            // Capture previous generation id before incrementing
            val prev = inst.runId.getAndIncrement()
            inst.session.cancelGenerateResponseAsync()
            inst.state.set(RunState.IDLE)
            cleanUpEntries.remove(keyOf(model))?.let { entry ->
                if (entry.runId == prev) entry.listener.invoke()
            }
        }
    }

    /** Builds a session with current sampling options and audio modality enabled (if needed). */
    private fun buildSessionFromModel(
        engine: LlmInference,
        topK: Int,
        topP: Float,
        temp: Float
    ): LlmInferenceSession =
        LlmInferenceSession.createFromOptions(
            engine,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(topK)
                .setTopP(topP)
                .setTemperature(temp)
                // Enable audio modality (leave enabled if your model supports audio inputs)
                .setGraphOptions(
                    GraphOptions.builder()
                        .setEnableAudioModality(true)
                        .build()
                )
                .build()
        )

    // ───────────────────── version-agnostic audio helpers ───────────────────

    /**
     * Try to feed a mono WAV/PCM byte array into the session.
     * The actual method name differs across SDK versions; probe common variants.
     */
    private fun LlmInferenceSession.tryAddAudioWav(bytes: ByteArray) {
        val cls = this::class.java
        val candidates = listOf(
            "addAudio", "addAudioBytes", "addAudioChunk", "addWav", "addAudioWav", "addAudioInput"
        )
        val m = cls.methods.firstOrNull { m ->
            m.name in candidates &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == ByteArray::class.java
        } ?: throw NoSuchMethodError(
            "No suitable addAudio* method taking ByteArray was found on ${cls.name}"
        )
        m.invoke(this, bytes)
    }

    /**
     * Some SDKs require an explicit 'finish' / 'end' call after sending all audio.
     * If not found, it's safe to be a no-op.
     */
    private fun LlmInferenceSession.tryFinishAudioInput() {
        val cls = this::class.java
        val candidates = listOf(
            "finishAudio", "finishAudioInput", "endAudio", "endAudioInput", "flushAudio"
        )
        val m = cls.methods.firstOrNull { it.name in candidates && it.parameterTypes.isEmpty() }
        if (m != null) runCatching { m.invoke(this) }
    }

    // ────────────────────────── sanitizers & utils ──────────────────────────

    private fun sanitizeTopK(k: Int): Int {
        val v = k.coerceAtLeast(1)
        if (k != v) Log.w(TAG, "TopK($k) adjusted to $v")
        return v
    }

    private fun sanitizeTopP(p: Float): Float {
        val ok = p in 0f..1f
        if (!ok) Log.w(TAG, "TopP($p) out of range; using $DEFAULT_TOP_P")
        return if (ok) p else DEFAULT_TOP_P
    }

    private fun sanitizeTemperature(t: Float): Float {
        val ok = t in 0f..2f
        if (!ok) Log.w(TAG, "Temperature($t) out of range; using $DEFAULT_TEMPERATURE")
        return if (ok) t else DEFAULT_TEMPERATURE
    }

    private fun keyOf(model: Model) = "${model.name}#${System.identityHashCode(model)}"

    private fun cleanError(msg: String?) =
        msg?.replace("INTERNAL:", "")?.replace("\\s+".toRegex(), " ")?.trim() ?: "Unknown error"

    private fun tryCloseQuietly(session: LlmInferenceSession?) = runCatching {
        session?.cancelGenerateResponseAsync()
        session?.close()
    }

    private fun safeClose(engine: LlmInference?) = runCatching { engine?.close() }
}

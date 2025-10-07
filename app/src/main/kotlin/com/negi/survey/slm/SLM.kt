/*
 * A concurrency-safe helper for managing MediaPipe LLM inference sessions in Android.
 * This utility provides lifecycle-safe initialization, inference execution, cancellation,
 * and cleanup with session reuse and deferred resource disposal.
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")
package com.negi.survey.slm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

// Hardware accelerator options for inference (CPU or GPU)
enum class Accelerator(val label: String) { CPU("CPU"), GPU("GPU") }

// Configuration keys for LLM inference
enum class ConfigKey { MAX_TOKENS, TOP_K, TOP_P, TEMPERATURE, ACCELERATOR }

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

/**
 * Represents a loaded LLM model configuration and runtime instance.
 */
data class Model(
    val name: String,
    private val taskPath: String,
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

/**
 * Holds the initialized engine and session for a model.
 */
data class LlmModelInstance(
    val engine: LlmInference,
    @Volatile var session: LlmInferenceSession,
    val state: AtomicReference<RunState> = AtomicReference(RunState.IDLE),
)

/**
 * SLM: Safe Language Model inference helper.
 */
object SLM {

    private val cleanUpListeners = ConcurrentHashMap<String, CleanUpListener>()

    fun isBusy(model: Model): Boolean =
        model.instance?.state?.get()?.let { it != RunState.IDLE } == true

    @Synchronized
    fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
        var oldEngine: LlmInference? = null
        var oldSession: LlmInferenceSession? = null

        model.instance?.let { inst ->
            if (inst.state.get() != RunState.IDLE) {
                onDone("Model '${model.name}' is busy. Try again after done=true or call cancel().")
                return
            }
            oldSession = inst.session
            oldEngine = inst.engine
            cleanUpListeners.remove(keyOf(model))?.invoke()
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
            .setMaxTokens(maxTokens)

        val engine = try {
            LlmInference.createFromOptions(context, baseOpts.setPreferredBackend(backend).build())
        } catch (e: Exception) {
            if (backend == LlmInference.Backend.GPU) {
                Log.w(TAG, "GPU init failed. Falling back to CPU.")
                try {
                    LlmInference.createFromOptions(
                        context,
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

    fun resetSession(model: Model): Boolean {
        val snap = synchronized(this) {
            val inst = model.instance ?: return false
            if (inst.state.get() != RunState.IDLE) return false
            Snap(
                inst.engine,
                inst.session,
                sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOP_K)),
                sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOP_P)),
                sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))
            )
        }

        tryCloseQuietly(snap.oldSession)
        val newSession = try {
            buildSessionFromModel(snap.engine, snap.topK, snap.topP, snap.temperature)
        } catch (e: Exception) {
            Log.e(TAG, "Session reset failed: ${e.message}")
            return false
        }

        synchronized(this) {
            val inst = model.instance ?: return false.also { tryCloseQuietly(newSession) }
            if (inst.engine != snap.engine || inst.state.get() != RunState.IDLE) {
                tryCloseQuietly(newSession)
                return false
            }
            inst.session = newSession
        }
        return true
    }

    @Synchronized
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val inst = model.instance ?: return onDone()

        // If busy, force cancel and synthesize onClean to unblock any waiters.
        if (inst.state.get() != RunState.IDLE) {
            inst.session.cancelGenerateResponseAsync()
            inst.state.set(RunState.IDLE)
            cleanUpListeners.remove(keyOf(model))?.invoke()
        } else {
            // Idle: just remove any pending listener.
            cleanUpListeners.remove(keyOf(model))?.invoke()
        }

        model.instance = null
        tryCloseQuietly(inst.session)
        safeClose(inst.engine)
        onDone()
    }

    @Synchronized
    fun cancel(model: Model) {
        val inst = model.instance ?: return
        if (inst.state.get() != RunState.IDLE) {
            inst.state.set(RunState.CANCELLING)
            inst.session.cancelGenerateResponseAsync()
            inst.state.set(RunState.IDLE)
            // Synthesize onClean so higher layers always see a cleanup after cancel.
            cleanUpListeners.remove(keyOf(model))?.invoke()
        }
    }

    fun runInference(
        model: Model,
        input: String,
        listener: ResultListener,
        onClean: CleanUpListener
    ) {
        val inst = model.instance ?: return listener("Model not initialized.", true)

        if (!inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)) {
            cancel(model)
            inst.state.compareAndSet(RunState.IDLE, RunState.RUNNING)
        }

        Log.d(TAG, "runInference Called with model='${model.name}'\ninput.length=${input.length}")

        cleanUpListeners[keyOf(model)] = {
            inst.state.set(RunState.IDLE)
            onClean()
        }

        val text = input.trim()
        if (text.isNotEmpty()) {
            inst.session.addQueryChunk(text)
        }

        inst.session.generateResponseAsync { partial, done ->
            val preview =
                if (partial.length > 256) partial.take(128) + " … " + partial.takeLast(64) else partial
            Log.d(TAG, "partial[len=${partial.length}, done=$done]: $preview")

            if (!done) {
                listener(partial, false)
            } else {
                listener(partial, true)
                cleanUpListeners.remove(keyOf(model))?.invoke()
            }
        }
    }

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
                .build()
        )

    private fun sanitizeTopK(k: Int) = k.coerceAtLeast(1)
    private fun sanitizeTopP(p: Float) = p.takeIf { it in 0f..1f } ?: DEFAULT_TOP_P
    private fun sanitizeTemperature(t: Float) = t.takeIf { it in 0f..2f } ?: DEFAULT_TEMPERATURE

    private fun keyOf(model: Model) = "${model.name}#${System.identityHashCode(model)}"

    private fun cleanError(msg: String?) =
        msg?.replace("INTERNAL:", "")?.replace("\\s+".toRegex(), " ")?.trim() ?: "Unknown error"

    private fun tryCloseQuietly(session: LlmInferenceSession?) = runCatching {
        session?.cancelGenerateResponseAsync()
        session?.close()
    }

    private fun safeClose(engine: LlmInference?) = runCatching { engine?.close() }

    private data class Snap(
        val engine: LlmInference,
        val oldSession: LlmInferenceSession,
        val topK: Int,
        val topP: Float,
        val temperature: Float
    )
}

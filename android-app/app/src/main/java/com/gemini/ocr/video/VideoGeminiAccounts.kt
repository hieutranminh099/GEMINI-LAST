package com.gemini.ocr.video

import android.content.Context
import com.gemini.ocr.network.GeminiDispatchLimiter
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.AppSettings
import com.gemini.ocr.settings.GeminiKeyMode
import com.gemini.ocr.settings.GeminiKeySlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.MessageDigest

/** Credential selection for stateless video requests. Economy jobs retain their submission owner. */
internal object VideoGeminiAccounts {
    private val pools = mutableMapOf<String, GeminiAccountPool>()
    private val batchQuotas = mutableMapOf<String, VideoBatchGeminiQuota>()
    private val directBatchQuota = VideoBatchGeminiQuota(awaitReducedTurn = reducedBatchDispatch())
    @Volatile internal var overrideBatchQuotaForTest: VideoBatchGeminiQuota? = null

    suspend fun <T> requestBatch(
        context: Context?,
        primaryKey: String,
        affinity: String,
        beforeDispatch: suspend () -> Unit,
        operation: suspend (GeminiKeySlot) -> T
    ): T {
        val settings = context?.let(::AppSettings)
        val mode = settings?.geminiKeyMode ?: GeminiKeyMode.PRIMARY_ONLY
        val credentials = if (mode == GeminiKeyMode.PRIMARY_ONLY) listOf(GeminiKeySlot(1, primaryKey.trim()))
            else requireNotNull(settings).geminiVideoKeys()
        val selected = if (mode == GeminiKeyMode.BALANCED && credentials.isNotEmpty()) {
            credentials[Math.floorMod(affinity.hashCode(), credentials.size)]
        } else credentials.firstOrNull()
        val slot = selected?.takeIf { it.key.isNotBlank() }
            ?: throw VideoApiException("API_AUTH", detail = "Chưa có khóa Gemini cho video. Kiểm tra khóa và quyền API.")
        return withBatchQuota(context, beforeDispatch) { operation(slot) }
    }

    /** Economy keeps the key already bound to its durable job; it shares quota admission. */
    suspend fun <T> withBatchQuota(context: Context?, beforeDispatch: suspend () -> Unit,
                                   operation: suspend () -> T): T {
        val quota = overrideBatchQuotaForTest?.also { check(com.gemini.ocr.BuildConfig.DEBUG) }
            ?: if (context == null) directBatchQuota else batchQuota(context)
        return quota.request(beforeDispatch, operation)
    }

    private fun batchQuota(context: Context): VideoBatchGeminiQuota = synchronized(batchQuotas) {
        val app = context.applicationContext
        batchQuotas.getOrPut(app.filesDir.absolutePath) {
            val preferences = app.getSharedPreferences("gemini-video-batch-quota", Context.MODE_PRIVATE)
            VideoBatchGeminiQuota(
                awaitReducedTurn = reducedBatchDispatch(),
                load = {
                    val deadline = preferences.getLong("deadline", 0)
                    val code = preferences.getString("code", "").orEmpty()
                    val at = preferences.getLong("failedAt", 0)
                    val status = preferences.getInt("httpStatus", 0).takeIf { it > 0 }
                    val kind = VideoQuotaKind.entries.firstOrNull { it.name == preferences.getString("quotaKind", "") }
                    val wait = preferences.getLong("retryAfter", 0)
                    if (deadline <= 0 || code.isBlank()) null else when (status) {
                        429 -> VideoApiRecovery.quotaFailure(kind ?: VideoQuotaKind.UNKNOWN, wait, at,
                            preferences.getBoolean("inputTokenLimited", false))
                        null -> VideoApiRecovery.networkFailure(code)
                        else -> VideoApiRecovery.httpFailure(status, nowMs = at)
                    }.withRecovery(deadline, at, saved = true)
                },
                save = { failure ->
                    preferences.edit().putLong("deadline", failure.nextRetryAt).putString("code", failure.code)
                        .putLong("failedAt", failure.failedAtMs).putInt("httpStatus", failure.httpStatus ?: 0)
                        .putString("quotaKind", failure.quotaKind?.name.orEmpty())
                        .putBoolean("inputTokenLimited", failure.inputTokenLimited)
                        .putLong("retryAfter", failure.retryAfterMs).commit()
                }
            )
        }
    }

    private fun reducedBatchDispatch(): suspend () -> Unit {
        val limiter = GeminiDispatchLimiter(maximum = 1, windowMs = 8_000L)
        return { limiter.awaitTurn() }
    }

    suspend fun <T> request(
        context: Context,
        primaryKey: String,
        beforeDispatch: suspend () -> Unit,
        onRetry: suspend (Int, Long, String) -> Unit = { _, _, _ -> },
        operation: suspend (String) -> T
    ): T {
        val settings = AppSettings(context)
        val mode = settings.geminiKeyMode
        if (mode == GeminiKeyMode.PRIMARY_ONLY) {
            return VideoApiRecovery.run(beforeDispatch, onRetry = onRetry) { operation(primaryKey) }
        }
        return pool(context).request(settings.geminiVideoKeys(), mode, beforeDispatch, onRetry, operation)
    }

    private fun pool(context: Context): GeminiAccountPool = synchronized(pools) {
        val app = context.applicationContext
        pools.getOrPut(app.filesDir.absolutePath) {
            val preferences = app.getSharedPreferences("gemini-video-key-health", Context.MODE_PRIVATE)
            GeminiAccountPool(
                load = { identity ->
                    val deadline = preferences.getLong("$identity.until", 0)
                    val code = preferences.getString("$identity.code", "").orEmpty()
                    if (deadline > 0 && code.isNotBlank()) GeminiAccountCooldown(deadline, code) else null
                },
                save = { identity, cooldown ->
                    val edit = preferences.edit()
                    if (cooldown == null) edit.remove("$identity.until").remove("$identity.code")
                    else edit.putLong("$identity.until", cooldown.untilMs).putString("$identity.code", cooldown.code)
                    check(edit.commit()) { "Chưa lưu được thời gian chờ khóa Gemini. Tiến độ đã hoàn thành được giữ." }
                },
                awaitTurn = { slot, limiter ->
                    if (slot.slot == 1) StandaloneTranslator.awaitVideoDispatchTurn() else limiter.awaitTurn()
                }
            )
        }
    }
}

/** The settings contain no Google project IDs. All video keys share one conservative quota. */
internal class VideoBatchGeminiQuota(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val awaitTurn: suspend () -> Unit = { StandaloneTranslator.awaitVideoDispatchTurn() },
    load: () -> VideoApiException? = { null },
    private val save: (VideoApiException) -> Unit = {},
    private val awaitReducedTurn: suspend () -> Unit = {}
) {
    private val lane = Mutex()
    private var cooldown = load()
    private var limitedDispatches = if (cooldown?.let {
        it.code == "API_RATE_LIMIT" && it.nextRetryAt >= clockMs() - 60_000L
    } == true) 4 else 0

    suspend fun <T> request(beforeDispatch: suspend () -> Unit, operation: suspend () -> T): T =
        VideoApiRecovery.withAccountAdmission {
            lane.withLock {
                VideoApiRecovery.terminalProjectFailure()?.let { throw it.withRecovery(saved = true) }
                cooldown?.takeIf { it.nextRetryAt > clockMs() }?.let { throw it.withRecovery(saved = true) }
                if (limitedDispatches > 0) awaitReducedTurn()
                awaitTurn()
                currentCoroutineContext().ensureActive()
                beforeDispatch()
                currentCoroutineContext().ensureActive()
                try {
                    operation().also { if (limitedDispatches > 0) limitedDispatches-- }
                } catch (error: IOException) {
                    val failure = if (error is VideoApiException) error else VideoApiRecovery.networkFailure(error)
                    val failedAt = failure.failedAtMs.takeIf { it > 0 } ?: clockMs()
                    if (failure.transient) {
                        val wait = failure.retryAfterMs.takeIf { it > 0 } ?: 60_000L
                        val deadline = maxOf(failure.nextRetryAt, failedAt + wait.coerceAtMost(Long.MAX_VALUE - failedAt))
                        val waiting = failure.withRecovery(deadline, failedAt)
                        if (failure.code == "API_RATE_LIMIT") limitedDispatches = 4
                        cooldown = waiting
                        // The caller also checkpoints this failure. Keep the in-memory quota if the
                        // shared store is temporarily unavailable, and report the provider failure.
                        runCatching { save(waiting) }
                        throw waiting
                    }
                    if (failure.code == "API_AUTH" || failure.code == "API_QUOTA_EXHAUSTED") {
                        VideoApiRecovery.stopBatchProject(failure)
                    }
                    throw failure
                }
            }
        }
}

internal data class GeminiAccountCooldown(val untilMs: Long, val code: String)

/** At most one in-flight request per distinct credential, with durable failure admission. */
internal class GeminiAccountPool(
    private val load: (String) -> GeminiAccountCooldown? = { null },
    private val save: (String, GeminiAccountCooldown?) -> Unit = { _, _ -> },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val awaitTurn: suspend (GeminiKeySlot, GeminiDispatchLimiter) -> Unit = { _, limiter -> limiter.awaitTurn() }
) {
    private class State(var cooldown: GeminiAccountCooldown?, var active: Boolean = false) {
        val limiter = GeminiDispatchLimiter()
    }
    private data class Credential(val slot: GeminiKeySlot, val identity: String) {
        override fun toString(): String = "Credential(slot=${slot.slot})"
    }
    private sealed interface Admission {
        data class Lease(val credential: Credential, val state: State) : Admission
        data class Busy(val changed: CompletableDeferred<Unit>) : Admission
        data class Unavailable(val cooldown: GeminiAccountCooldown) : Admission
    }

    private val lock = Any()
    private val states = mutableMapOf<String, State>()
    private var changed = CompletableDeferred<Unit>()
    private var cursor = 0

    suspend fun <T> request(
        configured: List<GeminiKeySlot>,
        mode: GeminiKeyMode,
        beforeDispatch: suspend () -> Unit,
        onRetry: suspend (Int, Long, String) -> Unit = { _, _, _ -> },
        operation: suspend (String) -> T
    ): T {
        val credentials = configured.take(3).map { it.copy(key = it.key.trim()) }.filter { it.key.isNotBlank() }
            .distinctBy { it.key }.map { Credential(it, identity(it.key)) }
        if (credentials.isEmpty()) throw VideoApiException("API_AUTH", detail = "Chưa có khóa Gemini cho video.")
        var attempts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val admission = acquire(credentials, mode)) {
                is Admission.Busy -> admission.changed.await()
                is Admission.Unavailable -> {
                    val waiting = admission.cooldown
                    onRetry(maxOf(1, attempts), waiting.untilMs, waiting.code)
                    if (attempts in 1..2 && isTransportFailure(waiting.code)) {
                        sleep((waiting.untilMs - clockMs()).coerceAtLeast(1))
                    } else throw unavailable(waiting)
                }
                is Admission.Lease -> {
                    try {
                        val result = VideoApiRecovery.withAccountAdmission {
                            awaitTurn(admission.credential.slot, admission.state.limiter)
                            currentCoroutineContext().ensureActive()
                            beforeDispatch()
                            currentCoroutineContext().ensureActive()
                            attempts++
                            operation(admission.credential.slot.key)
                        }
                        record(admission, null)
                        return result
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) {
                        val failure = when (error) {
                            is VideoApiException -> error
                            is IOException -> VideoApiRecovery.networkFailure(error)
                            else -> throw error
                        }
                        val wait = cooldown(failure, attempts)
                        if (wait == null) throw failure
                        record(admission, wait)
                        if (attempts >= 3) {
                            val earliest = synchronized(lock) { nextCooldown(credentials) }
                            onRetry(attempts, earliest.untilMs, earliest.code)
                            throw unavailable(earliest)
                        }
                    } finally {
                        synchronized(lock) {
                            admission.state.active = false
                            changed.complete(Unit)
                            changed = CompletableDeferred()
                        }
                    }
                }
            }
        }
    }

    private fun acquire(credentials: List<Credential>, mode: GeminiKeyMode): Admission = synchronized(lock) {
        val now = clockMs()
        val ordered = if (mode == GeminiKeyMode.BALANCED) {
            List(credentials.size) { credentials[(cursor + it) % credentials.size] }
        } else credentials
        for (credential in ordered) {
            val state = states.getOrPut(credential.identity) { State(load(credential.identity)) }
            if (mode == GeminiKeyMode.FAILOVER && (state.cooldown?.untilMs ?: 0) <= now && state.active) {
                return@synchronized Admission.Busy(changed)
            }
            if (!state.active && (state.cooldown?.untilMs ?: 0) <= now) {
                state.active = true
                cursor = (credentials.indexOf(credential) + 1) % credentials.size
                return@synchronized Admission.Lease(credential, state)
            }
        }
        if (credentials.any { states.getValue(it.identity).active }) return@synchronized Admission.Busy(changed)
        Admission.Unavailable(nextCooldown(credentials))
    }

    private fun nextCooldown(credentials: List<Credential>): GeminiAccountCooldown {
        val cooldowns = credentials.mapNotNull { states[it.identity]?.cooldown }
        val retryable = cooldowns.filter { it.code != "API_AUTH" && it.code != "API_QUOTA_EXHAUSTED" }
        return (retryable.ifEmpty { cooldowns }).minBy { it.untilMs }
    }

    private fun record(lease: Admission.Lease, cooldown: GeminiAccountCooldown?) = synchronized(lock) {
        lease.state.cooldown = cooldown
        // Accepted translations must survive an inability to clear expired health metadata.
        if (cooldown == null) runCatching { save(lease.credential.identity, null) }
        else save(lease.credential.identity, cooldown)
    }

    private fun cooldown(failure: VideoApiException, attempts: Int): GeminiAccountCooldown? {
        val duration = when (failure.code) {
            "API_AUTH" -> 60_000L
            "API_RATE_LIMIT" -> maxOf(61_000L, failure.retryAfterMs)
            "API_QUOTA_EXHAUSTED" -> maxOf(86_400_000L, failure.retryAfterMs)
            else -> if (isTransportFailure(failure.code))
                maxOf(1_000L shl (attempts - 1).coerceIn(0, 1), failure.retryAfterMs) else return null
        }
        return GeminiAccountCooldown(maxOf(clockMs() + duration, failure.nextRetryAt), failure.code)
    }

    private fun unavailable(cooldown: GeminiAccountCooldown) = VideoApiException(
        cooldown.code,
        transient = cooldown.code != "API_AUTH" && cooldown.code != "API_QUOTA_EXHAUSTED",
        nextRetryAt = cooldown.untilMs,
        detail = "Các khóa Gemini đang chờ hoặc chưa dùng được. Đã lưu tiến độ và thời điểm tiếp tục."
    )

    private fun isTransportFailure(code: String) = code in setOf("API_NETWORK", "API_TIMEOUT") || code.startsWith("API_SERVICE_")

    companion object {
        internal fun identity(key: String): String = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

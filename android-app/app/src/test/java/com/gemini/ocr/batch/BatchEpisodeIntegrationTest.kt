package com.gemini.ocr.batch

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.gemini.ocr.SessionState
import com.gemini.ocr.network.StandaloneTranslator
import com.gemini.ocr.settings.GeminiKeyMode
import com.gemini.ocr.video.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real BatchJobs + VideoJobs + AtomicFile checkpoints. Synthetic media/text responses only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [com.gemini.ocr.movie.MovieSourceLibraryTest.WindowsFileUris::class])
class BatchEpisodeIntegrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val videoStore get() = VideoStore(context)
    private val batchStore get() = BatchStore(context)
    private val previousNetworkGuard = StandaloneTranslator.beforeNetworkCallForTest

    @Before fun setup() {
        SessionState.running.value = false
        StandaloneTranslator.beforeNetworkCallForTest = { throw AssertionError("Unexpected network in a synthetic batch test") }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("standalone_api_key", "synthetic-invalid-ciphertext")
            .putString("standalone_api_key_plain_fallback", "fixture-no-network").commit()
        VideoSmartBatchSettings(context).groupSize = 10
        VideoSmartBatchSettings(context).economyMode = false
        shadowOf(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .setMemoryInfo(ActivityManager.MemoryInfo().apply {
                availMem = 16L * 1024 * 1024 * 1024; totalMem = availMem; lowMemory = false
            })
    }

    @After fun restoreNetworkGuard() { StandaloneTranslator.beforeNetworkCallForTest = previousNetworkGuard }

    private fun project(index: Int, pending: Int = 1, id: String = UUID.randomUUID().toString()): VideoProject {
        val protected = VideoCue("$id-user", 10, 700, "原来的名字", "Tên tôi đã sửa",
            model = "user-edited")
        val cues = listOf(protected) + List(pending) { i ->
            VideoCue("$id-$i", 1000L + i * 1000, 1800L + i * 1000, "这是第${i}句话")
        }
        val duration = (pending + 2) * 1000L
        return videoStore.save(VideoProject(id, "Episode $index", "file:///never-decoded.mp4", duration, 640, 360,
            sourceMode = VideoSourceMode.SIDECAR, translationMode = VideoTranslationMode.GEMINI,
            scanMs = duration, cues = cues, stage = VideoStage.PAUSED, resumeStage = VideoStage.TRANSLATING,
            pipeline = VideoPipelineCheckpoint().record(BatchPipelineStage.CLEAN, "COMPLETE")))
    }

    private fun batch(projects: List<VideoProject>, mode: BatchExecutionMode = BatchExecutionMode.SEQUENTIAL): BatchProject {
        val seed = batchStore.create("Ten episodes", BatchKind.SERIES)
        return batchStore.save(seed.copy(delivery = BatchDelivery.SOURCE_LIBRARY, autoRetryEnabled = false,
            executionMode = mode, items = projects.mapIndexed { i, p ->
                BatchItem(p.id, "EP${i + 1}.mp4", episode = (i + 1).toString(), durationMs = p.durationMs)
            }))
    }

    private fun reply(ids: List<String>, translation: (String) -> String = { "Bản dịch hợp lệ" }): String {
        val text = JSONObject().put("translations", JSONArray(ids.map {
            JSONObject().put("id", it).put("translation", translation(it))
        })).toString()
        return JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))).toString()
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(15_000) {
        while (!condition()) delay(10)
    }

    @Test fun tenEpisodesFirstFinishesWhileSecondExtractorIsBlockedAndOtherEightAreUnextracted() = runBlocking {
        val extractionEntered = CountDownLatch(1)
        val releaseExtraction = CountDownLatch(1)
        val projects = (1..10).map { index ->
            val seed = project(index)
            val file = File(videoStore.folder(seed.id), "fixture.srt").apply {
                writeText("1\n00:00:00,100 --> 00:00:01,500\n这是第${index}句话\n\n", Charsets.UTF_8)
            }
            val uri = if (index == 2) Uri.parse("content://batch-fixture/second.srt") else Uri.fromFile(file)
            if (index == 2) {
                shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { object : ByteArrayInputStream(file.readBytes()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        extractionEntered.countDown()
                        check(releaseExtraction.await(15, TimeUnit.SECONDS)) { "First episode never finished" }
                        return super.read(b, off, len)
                    }
                } }
            }
            videoStore.save(seed.copy(cues = emptyList(), scanMs = 0, pipeline = VideoPipelineCheckpoint(),
                stage = VideoStage.READY, resumeStage = VideoStage.EXTRACTING,
                subtitleUri = uri.toString(), subtitleName = "fixture.srt"))
        }
        val b = batch(projects, BatchExecutionMode.RAM_8_GB)
        val jobs = BatchJobs(context, VideoJobs(context))
        val done = CompletableDeferred<Unit>()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                if (requests.isEmpty()) {
                    assertTrue("Extractor must run while the first API response is pending",
                        extractionEntered.await(10, TimeUnit.SECONDS))
                }
                // Imported SRT IDs are per project; the request context and workers own their timeline.
                requests += request.cueIds
                reply(request.cueIds)
            }
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
            awaitCondition {
                VideoStagedPipeline.completed(videoStore.load(projects.first().id), BatchPipelineStage.FINISH) &&
                    batchStore.load(b.id).items.first().state == "DONE"
            }
            assertTrue(projects.drop(1).any { !VideoStagedPipeline.extracted(videoStore.load(it.id)) })
            assertEquals("DONE", batchStore.load(b.id).items.first().state)
            val first = videoStore.load(projects.first().id)
            assertEquals(100L, first.cues.single().startMs)
            assertEquals(1500L, first.cues.single().endMs)
            assertTrue(videoStore.exportFile(first.id, "srt").readText().contains("00:00:00,100 --> 00:00:01,500"))
            releaseExtraction.countDown()
            withTimeout(20_000) { done.await() }
            assertEquals("REVIEW", batchStore.load(b.id).state)
            assertTrue(batchStore.load(b.id).items.all { it.state == "DONE" })
            assertEquals(10, requests.size)
        } finally {
            releaseExtraction.countDown(); jobs.cancel()
            if (!done.isCompleted && jobs.busy.value != null) withTimeout(20_000) { done.await() }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun pauseResumeSendsOnlyMissingGroupsAndKeepsUserEditsAndTimestamps() = runBlocking { interruptAndResume(false) }
    @Test fun cancelDrainsWorkersBeforeReservationIsReleasedAndCanResumeMissingWork() = runBlocking { interruptAndResume(true) }

    private suspend fun interruptAndResume(cancel: Boolean) {
        val originals = (1..10).map { project(it, if (it == 1) 12 else 1) }
        val b = batch(originals)
        val videos = VideoJobs(context)
        val jobs = BatchJobs(context, videos)
        val done = CompletableDeferred<Unit>()
        val secondRequest = CompletableDeferred<Unit>()
        val requests = Collections.synchronizedList(mutableListOf<List<String>>())
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                requests += request.cueIds
                if (requests.size == 2) { secondRequest.complete(Unit); awaitCancellation() }
                reply(request.cueIds)
            }
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
            withTimeout(15_000) { secondRequest.await() }
            if (cancel) { jobs.cancel(); jobs.pause() } else jobs.pause() // Service destruction must not downgrade Cancel.
            assertEquals(if (cancel) "CANCELLED" else "PAUSED", batchStore.load(b.id).state)
            withTimeout(15_000) { done.await() }
            assertEquals(if (cancel) "CANCELLED" else "PAUSED", batchStore.load(b.id).state)
            assertNull(videos.batchBusy.value)
            assertTrue(videos.parallelProjects.value.isEmpty())
            assertTrue(batchStore.load(b.id).items.none { it.stageStatus == "RUNNING" })
            val accepted = videoStore.load(originals.first().id).cues.filter { it.translation.isNotBlank() }.map { it.id }.toSet()
            assertTrue(accepted.size > 1)
            requests.clear()
            VideoTranslationClient.overrideResponseForTest = { request ->
                assertTrue(request.cueIds.none { it in accepted })
                requests += request.cueIds
                reply(request.cueIds)
            }
            val resumed = BatchJobs(context, VideoJobs(context))
            val resumedDone = CompletableDeferred<Unit>()
            try {
                resumed.start(b.id); resumed.execute(b.id) { resumedDone.complete(Unit) }
                withTimeout(20_000) { resumedDone.await() }
                assertEquals("REVIEW", batchStore.load(b.id).state)
                assertEquals(requests.flatten().size, requests.flatten().distinct().size)
                originals.forEach { original ->
                    val saved = videoStore.load(original.id)
                    assertEquals(original.cues.first(), saved.cues.first())
                    assertEquals(original.cues.map { it.startMs to it.endMs }, saved.cues.map { it.startMs to it.endMs })
                }
            } finally { resumed.cancel(); if (resumed.busy.value != null) withTimeout(15_000) { resumedDone.await() } }
        } finally {
            jobs.cancel(); if (jobs.busy.value != null) withTimeout(15_000) { done.await() }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun redeliveredActiveBatchUsesDurableAcceptedRowsAndIgnoresDuplicateServiceDispatch() = runBlocking {
        val original = project(1, 4)
        val saved = videoStore.save(original.copy(cues = original.cues.mapIndexed { index, cue ->
            if (index == 1) cue.copy(translation = "Đã lưu trước khi tiến trình dừng") else cue
        }, pipeline = original.pipeline.record(BatchPipelineStage.TRANSLATE, "RUNNING", 2)))
        val b = batchStore.save(batch(listOf(saved)).copy(state = "RUNNING", items = listOf(
            BatchItem(saved.id, "EP1.mp4", state = "PENDING", stage = BatchPipelineStage.TRANSLATE, stageStatus = "RUNNING"))))
        val jobs = BatchJobs(context, VideoJobs(context))
        val done = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                calls.incrementAndGet()
                assertTrue(request.cueIds.none { id -> saved.cues.any { it.id == id && it.translation.isNotBlank() } })
                entered.complete(Unit); release.await(); reply(request.cueIds)
            }
            jobs.execute(b.id) { done.complete(Unit) } // Fresh process: no in-memory start/reservation.
            withTimeout(15_000) { entered.await() }
            jobs.execute(b.id) { fail("Duplicate service intent must not acquire another worker") }
            release.complete(Unit)
            withTimeout(15_000) { done.await() }
            assertEquals(1, calls.get())
            assertEquals(saved.cues.first(), videoStore.load(saved.id).cues.first())
            val accepted = saved.cues[1]
            assertEquals(accepted.copy(draftVi = accepted.translation), videoStore.load(saved.id).cues[1])
            assertEquals("REVIEW", batchStore.load(b.id).state)
        } finally {
            release.complete(Unit); jobs.cancel()
            if (jobs.busy.value != null) withTimeout(15_000) { done.await() }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun staleServiceIntentDoesNotRestartUserPausedCancelledOrFinishedWork() {
        val p = project(1)
        val b = batch(listOf(p))
        for (state in listOf("PAUSED", "CANCELLED", "REVIEW")) {
            batchStore.save(b.copy(state = state))
            val jobs = BatchJobs(context, VideoJobs(context))
            var finished = false
            jobs.execute(b.id) { finished = true }
            assertTrue(finished)
            assertNull(jobs.busy.value)
            assertEquals(state, batchStore.load(b.id).state)
            assertEquals(p.cues, videoStore.load(p.id).cues)
        }
    }

    @Test fun responseLostBeforeDurableSaveRemainsPendingAndIsRetriedFromTheLastCheckpoint() = runBlocking {
        val original = project(1, 3)
        val b = batch(listOf(original))
        val jobs = BatchJobs(context, VideoJobs(context))
        val done = CompletableDeferred<Unit>()
        val previous = VideoTranslationClient.overrideResponseForTest
        val requested = mutableListOf<List<String>>()
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                requested += request.cueIds
                reply(request.cueIds) // Synthetic response exists, but no durable result has been committed.
                throw CancellationException("Process stopped before committing the response")
            }
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
            withTimeout(15_000) { done.await() }
            val checkpoint = videoStore.load(original.id)
            assertEquals(original.cues, checkpoint.cues)
            assertNotEquals("COMPLETE", checkpoint.pipeline.task(BatchPipelineStage.TRANSLATE).status)
            // Model a process killed before its batch-finally block: only the durable active marker survives.
            batchStore.save(batchStore.load(b.id).copy(state = "RUNNING"))
            val recovered = BatchJobs(context, VideoJobs(context))
            val recoveredDone = CompletableDeferred<Unit>()
            VideoTranslationClient.overrideResponseForTest = { request -> requested += request.cueIds; reply(request.cueIds) }
            try {
                recovered.execute(b.id) { recoveredDone.complete(Unit) }
                withTimeout(15_000) { recoveredDone.await() }
                assertEquals(requested[0], requested[1]) // Ambiguous, uncommitted request must be repeated safely.
                assertEquals(original.cues.first(), videoStore.load(original.id).cues.first())
                assertEquals("REVIEW", batchStore.load(b.id).state)
            } finally { recovered.cancel(); if (recovered.busy.value != null) withTimeout(15_000) { recoveredDone.await() } }
        } finally {
            jobs.cancel(); if (jobs.busy.value != null) withTimeout(15_000) { done.await() }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun transientNetworkFailureRetriesPendingIdsWithoutLosingTheOtherEpisodes() = runBlocking {
        val originals = (1..10).map { project(it) }
        val b = batchStore.save(batch(originals).copy(autoRetryEnabled = true))
        val clock = FakeClock()
        val jobs = BatchJobs(context, VideoJobs(context), waitBeforeRetry = clock::pauseForRetry, nowMs = clock::read)
        val done = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                if (calls.incrementAndGet() == 1) throw VideoApiException("API_NETWORK", transient = true)
                reply(request.cueIds)
            }
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
            withTimeout(20_000) { done.await() }
            assertEquals("REVIEW", batchStore.load(b.id).state)
            assertEquals(listOf(BatchRecovery.INTERVAL_MS), clock.waits)
            assertEquals(11, calls.get())
            originals.forEach { assertEquals(it.cues.first(), videoStore.load(it.id).cues.first()) }
        } finally {
            jobs.cancel(); if (jobs.busy.value != null) withTimeout(15_000) { done.await() }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun playbackBlocksNewExtractionAndResumesAfterTheRecoveryHold() = runBlocking {
        val p = project(1)
        val file = File(videoStore.folder(p.id), "source.srt").apply {
            writeText("1\n00:00:00,100 --> 00:00:01,500\n你好\n\n")
        }
        val pending = videoStore.save(p.copy(cues = emptyList(), scanMs = 0, pipeline = VideoPipelineCheckpoint(),
            subtitleUri = Uri.fromFile(file).toString(), subtitleName = "source.srt"))
        val b = batch(listOf(pending))
        val jobs = BatchJobs(context, VideoJobs(context))
        val done = CompletableDeferred<Unit>()
        val owner = Any()
        val calls = AtomicInteger()
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            BatchPlaybackPriority.current.setPlaying(owner, true)
            VideoTranslationClient.overrideResponseForTest = { calls.incrementAndGet(); reply(it.cueIds) }
            jobs.start(b.id); jobs.execute(b.id) { done.complete(Unit) }
            delay(150)
            assertFalse(done.isCompleted)
            assertTrue(videoStore.load(p.id).cues.isEmpty())
            assertEquals(0, calls.get())
            BatchPlaybackPriority.current.setPlaying(owner, false)
            withTimeout(15_000) { done.await() }
            assertEquals("REVIEW", batchStore.load(b.id).state)
            assertEquals(1, calls.get())
        } finally {
            BatchPlaybackPriority.current.setPlaying(owner, false); jobs.cancel()
            if (jobs.busy.value != null) withTimeout(15_000) { done.await() }
            withTimeout(3_000) { while (BatchPlaybackPriority.current.restricted()) delay(20) }
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun longOcrFilmPersistsPartialIdsAndResumesTheDurableTimeline() = runBlocking {
        val seed = project(1)
        val duration = 79 * 60_000L
        val cues = List(8_866) { index ->
            val start = duration * index / 8_866
            val source = "这是第${index + 1}句台词。" + if (index < 16) "他们正在讨论。".repeat(65) else ""
            VideoCue("ocr-${index + 1}", start, start + 450, source, origin = "OCR",
                rawZh = source, correctedZh = source, ocrConfidence = .95f)
        }
        fun translation(id: String) = "Bản dịch câu ${id.substringAfterLast('-')}"
        val original = videoStore.save(seed.copy(durationMs = duration, scanMs = duration, cues = cues,
            sourceMode = VideoSourceMode.OCR, translationMode = VideoTranslationMode.HYBRID))
        val b = batchStore.save(batch(listOf(original)).copy(
            translationMode = original.translationMode, sourceMode = original.sourceMode))
        val legacy = requireNotNull(VideoFullDialoguePlan.next(cues, duration, original.pipeline.contextProfile,
            VideoHybridRecovery.INITIAL_TARGETS) { candidate ->
            runCatching { VideoTranslationClient.buildRequest(candidate, original.profile, original.glossary) }.isSuccess
        })
        val legacyPayload = VideoTranslationClient.buildRequest(legacy, original.profile, original.glossary)
        val legacyRows = JSONObject(legacy.videoContext).getJSONArray("dialogue").length()
        val legacyInput = VideoBatchRequestBudget.estimateInputTokens(legacyPayload)
        assertTrue(legacyInput > VideoBatchRequestBudget.MAX_INPUT_TOKENS)
        println("Legacy first request on the same fixture: ${legacy.cueIds.size} targets, $legacyRows dialogue rows, " +
            "${legacyPayload.toByteArray(Charsets.UTF_8).size} bytes, $legacyInput estimated input tokens, " +
            "${legacy.maxOutputTokens} configured max output tokens")
        val previous = VideoTranslationClient.overrideResponseForTest
        val requested = mutableListOf<List<String>>()
        val clock = FakeClock()
        fun jobs() = BatchJobs(context, VideoJobs(context), waitBeforeRetry = clock::pauseForRetry, nowMs = clock::read)
        val maxTargets = AtomicInteger()
        val maxTokens = AtomicInteger()
        val maxContext = AtomicInteger()
        fun observe(request: VideoTranslationPlan.Batch) {
            assertTrue(request.fullDialogue)
            assertTrue(request.cueIds.size in 1..VideoHybridRecovery.MAX_TARGETS)
            assertTrue(request.maxOutputTokens <= VideoBatchRequestBudget.MAX_OUTPUT_TOKENS)
            val payload = VideoTranslationClient.buildRequest(request, original.profile, original.glossary)
            val tokens = VideoBatchRequestBudget.estimateInputTokens(payload)
            assertTrue(tokens <= VideoBatchRequestBudget.MAX_INPUT_TOKENS)
            val dialogue = JSONObject(request.videoContext).getJSONArray("dialogue")
            val indices = List(dialogue.length()) { dialogue.getJSONObject(it).getInt("index") }
            assertEquals(indices.sorted().distinct(), indices)
            assertTrue(indices.containsAll(request.indices))
            val extra = indices.toSet() - request.indices.toSet()
            assertTrue(extra.size <= VideoBatchRequestBudget.MAX_DIALOGUE_NEIGHBORS)
            if (extra.isNotEmpty()) {
                assertTrue(extra.any { it - 1 in request.indices || it + 1 in request.indices })
            }
            maxTargets.accumulateAndGet(request.cueIds.size, ::maxOf)
            maxTokens.accumulateAndGet(tokens, ::maxOf)
            maxContext.accumulateAndGet(extra.size, ::maxOf)
            requested += request.cueIds
        }
        try {
            VideoTranslationClient.overrideResponseForTest = { request ->
                observe(request)
                if (requested.size == 1) {
                    assertTrue(request.cueIds.size in 2 until VideoHybridRecovery.INITIAL_TARGETS)
                    reply(request.cueIds.drop(1).asReversed(), ::translation)
                } else throw CancellationException("Simulated stop before the next response was committed")
            }
            runBatch(b.id, jobs())
            assertEquals(2, requested.size)
            val firstTargets = requested[0]
            val interruptedTargets = requested[1]
            assertTrue(firstTargets.first() in interruptedTargets)
            val checkpoint = videoStore.load(original.id)
            val retained = checkpoint.cues.filter { VideoTranslationPlan.isComplete(it, true) }.associateBy { it.id }
            assertEquals(firstTargets.drop(1).toSet(), retained.keys)
            assertTrue(interruptedTargets.none { it in retained })
            for ((id, cue) in retained) assertEquals(translation(id), cue.translation)

            // Model redelivery after process death. The worker instances and in-memory reservation are new.
            batchStore.save(batchStore.load(b.id).copy(state = "RUNNING"))
            VideoTranslationClient.overrideResponseForTest = { request ->
                observe(request)
                assertTrue(request.cueIds.none { it in retained })
                reply(request.cueIds.asReversed(), ::translation)
            }
            runBatch(b.id, jobs(), start = false, timeoutMs = 180_000)
            val resumedTargets = requested.drop(2).flatten()
            val expectedPendingIds = cues.asSequence().map { it.id }.filterNot(retained::containsKey).toSet()
            assertEquals(expectedPendingIds.size, resumedTargets.size)
            assertEquals(expectedPendingIds, resumedTargets.toSet())
            val finished = videoStore.load(original.id)
            assertEquals("REVIEW", batchStore.load(b.id).state)
            assertEquals(duration, finished.scanMs)
            assertEquals(original.uri, finished.uri)
            assertEquals(cues.size, finished.cues.size)
            assertEquals(cues.map(::ocrIdentity), finished.cues.map(::ocrIdentity))
            assertEquals(cues.map { translation(it.id) }, finished.cues.map { it.translation })
            assertTrue(maxContext.get() > 0)
            assertTrue(clock.waits.isEmpty())
            assertEquals(VideoTimeline.srt(finished.cues, duration), videoStore.exportFile(original.id, "srt").readText())
            println("Long OCR fixture: ${requested.size} requests, max ${maxTargets.get()} targets, " +
                "max ${maxTokens.get()} estimated input tokens, ${VideoBatchRequestBudget.MAX_OUTPUT_TOKENS} max output tokens, " +
                "max ${maxContext.get()} additional context rows, " +
                "0 ms simulated retry wait, ${retained.size} kept across restart, ${finished.cues.size} valid translations")
        } finally {
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }

    @Test fun threeVideosAndTwoSlotsWaitForTheSame429Deadline() = runBlocking {
        val clock = FakeClock()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("gemini_video_key_mode", GeminiKeyMode.BALANCED.name)
            .putString("gemini_video_api_key_2", "synthetic-invalid-ciphertext")
            .putString("gemini_video_api_key_2_plain_fallback", "fixture-second-slot").commit()
        // A fixed final digit gives the first target ID each hash parity for the two configured slots.
        val stem = UUID.randomUUID().toString().dropLast(1)
        val originals = (1..3).map { project(it, id = "$stem$it") }
        val b = batchStore.save(batch(originals, BatchExecutionMode.RAM_8_GB).copy(autoRetryEnabled = true))
        val rpc = JSONObject().put("@type", "type.googleapis.com/google.rpc.RetryInfo").put("retryDelay", "7.250s")
        val body = quotaBody("GenerateRequestsPerMinutePerProjectPerModel", "10", rpc)
        val sends = mutableListOf<Send>()
        val jobs = BatchJobs(context, VideoJobs(context), waitBeforeRetry = { wait ->
            assertEquals(1, sends.size)
            val waiting = batchStore.load(b.id)
            assertEquals("RETRY_WAIT", waiting.state)
            assertEquals(clock.read() + 7_250, waiting.autoRetryAtMs)
            val failed = originals.mapNotNull { lastFailure(it) }.single { !it.fromSavedFailure }
            assertEquals(429, failed.httpStatus)
            assertEquals("SHORT_WINDOW", failed.quotaKind)
            assertEquals(clock.read(), failed.failedAtMs)
            clock.pauseForRetry(wait)
        }, nowMs = clock::read)
        withFakeExchange(clock, exchange = { request, slot ->
            sends += Send(slot, clock.read())
            if (sends.size == 1) throw VideoApiRecovery.httpFailure(429, body, "2", nowMs = clock.read())
            reply(request.cueIds)
        }) { runBatch(b.id, jobs) }
        assertEquals(listOf(7_250L), clock.waits)
        assertEquals(4, sends.size)
        assertEquals(setOf(1, 2), sends.map { it.slot }.toSet())
        assertEquals(listOf(FakeClock.START) + List(3) { FakeClock.START + 7_250 }, sends.map { it.timeMs })
        assertEquals("REVIEW", batchStore.load(b.id).state)
        originals.forEach { original ->
            assertEquals(original.cues.first(), videoStore.load(original.id).cues.first())
            assertTrue(videoStore.load(original.id).cues.all { it.translation.isNotBlank() })
        }
        println("Shared 429: ${sends.size} requests across ${originals.size} videos and two key slots; " +
            "${clock.waits.single()} ms wait; 3 saved edits kept, 6 valid translations")
    }

    @Test fun unknown429HasOneBoundedBudgetAnd503HonorsItsHeader() = runBlocking {
        for ((status, header, delayMs) in listOf(Triple(429, null, 60_000L), Triple(503, "3", 3_000L))) {
            val clock = FakeClock()
            val original = project(status)
            val b = batchStore.save(batch(listOf(original)).copy(autoRetryEnabled = true))
            var sends = 0
            val jobs = BatchJobs(context, VideoJobs(context), waitBeforeRetry = { wait ->
                val failure = requireNotNull(lastFailure(original))
                assertEquals(status, failure.httpStatus)
                assertEquals(if (status == 429) "UNKNOWN" else null, failure.quotaKind)
                assertEquals(clock.read(), failure.failedAtMs)
                assertFalse(failure.fromSavedFailure)
                clock.pauseForRetry(wait)
            }, nowMs = clock::read)
            withFakeExchange(clock, exchange = { request, _ ->
                sends++
                if (status == 429 || sends == 1) {
                    throw VideoApiRecovery.httpFailure(status, retryAfter = header, nowMs = clock.read())
                }
                reply(request.cueIds)
            }) { runBatch(b.id, jobs) }
            val retries = if (status == 429) BatchRecovery.MAX_ATTEMPTS else 1
            assertEquals(retries + 1, sends)
            assertEquals(List(retries) { delayMs }, clock.waits)
            assertEquals(if (status == 429) "PAUSED" else "REVIEW", batchStore.load(b.id).state)
            val saved = videoStore.load(original.id).cues
            assertEquals(original.cues.first(), saved.first())
            assertEquals(if (status == 429) 1 else 2, saved.count { it.translation.isNotBlank() })
            println("HTTP $status: $sends requests, ${clock.waits.sum()} ms total wait, " +
                "1 saved edit kept, ${saved.count { it.translation.isNotBlank() }} valid translations")
        }
    }

    @Test fun dailyZeroAndPermissionFailuresStaySavedWithoutSendingOnRedelivery() = runBlocking {
        val cases = listOf(
            Triple(429, quotaBody("GenerateRequestsPerDayPerProjectPerModel", "20"), "DAILY"),
            Triple(429, quotaBody("GenerateRequestsPerMinutePerProjectPerModel", "0"), "ZERO"),
            Triple(401, "", null), Triple(403, "", null)
        )
        for ((status, body, kind) in cases) {
            val clock = FakeClock()
            val original = project(status)
            val b = batchStore.save(batch(listOf(original)).copy(autoRetryEnabled = true))
            var sends = 0
            fun jobs() = BatchJobs(context, VideoJobs(context), waitBeforeRetry = clock::pauseForRetry, nowMs = clock::read)
            withFakeExchange(clock, exchange = { _, _ ->
                sends++
                throw VideoApiRecovery.httpFailure(status, body, retryAfter = "4", nowMs = clock.read())
            }) {
                runBatch(b.id, jobs())
                val saved = requireNotNull(lastFailure(original))
                assertEquals(status, saved.httpStatus)
                assertEquals(kind, saved.quotaKind)
                assertEquals(FakeClock.START, saved.failedAtMs)
                assertFalse(saved.fromSavedFailure)
                assertEquals(if (status == 429) "API_QUOTA_EXHAUSTED" else "API_AUTH", saved.code)
                assertEquals(0L, batchStore.load(b.id).autoRetryAtMs)
                clock.advance(3 * 24 * 60 * 60_000L)
                runBatch(b.id, jobs(), start = false)
                assertEquals(saved, lastFailure(original))

                // Redelivery after the video checkpoint committed, before the batch recorded the failure.
                val paused = batchStore.load(b.id)
                batchStore.save(paused.copy(state = "RUNNING", items = paused.items.map {
                    it.copy(state = "PENDING", stageStatus = "RUNNING", errorCode = "", error = "")
                }))
                runBatch(b.id, jobs(), start = false)
                assertEquals("PAUSED", batchStore.load(b.id).state)
                assertEquals(saved.code, batchStore.load(b.id).items.single().errorCode)
                assertEquals(saved, lastFailure(original))
            }
            assertEquals(1, sends)
            assertTrue(clock.waits.isEmpty())
            val savedCues = videoStore.load(original.id).cues
            assertEquals(original.cues, savedCues.map { it.copy(error = "") })
            assertEquals(1, savedCues.count { it.translation.isNotBlank() })
            println("HTTP $status ${kind.orEmpty()}: $sends request, 0 ms wait, 1 saved edit kept, no request on redelivery")
        }
    }

    @Test fun legacySavedRateLimitDoesNotSendUntilManualStart() = runBlocking {
        val clock = FakeClock()
        val original = project(1)
        videoStore.save(original.copy(pipeline = original.pipeline.record(BatchPipelineStage.TRANSLATE,
            "NEEDS_REVIEW", errorCode = "API_RATE_LIMIT", nextRetryAt = clock.read() - 1)))
        val seed = batch(listOf(original))
        val b = batchStore.save(seed.copy(state = "PAUSED", autoRetryEnabled = true, retryAfterMs = clock.read() - 1,
            items = seed.items.map { it.copy(state = "NEEDS_REVIEW", stage = BatchPipelineStage.TRANSLATE,
                stageStatus = "NEEDS_REVIEW", errorCode = "API_RATE_LIMIT", error = "[API_RATE_LIMIT] Đã lưu") }))
        var sends = 0
        fun jobs() = BatchJobs(context, VideoJobs(context), waitBeforeRetry = clock::pauseForRetry, nowMs = clock::read)
        withFakeExchange(clock, exchange = { request, _ ->
            sends++
            assertEquals(listOf(original.cues.last().id), request.cueIds)
            reply(request.cueIds)
        }) {
            runBatch(b.id, jobs(), start = false)
            assertEquals(0, sends)
            assertEquals("API_RATE_LIMIT", batchStore.load(b.id).items.single().errorCode)
            assertNull(lastFailure(original)) // Legacy data has no HTTP observation/timestamp.
            runBatch(b.id, jobs())
        }
        assertEquals(1, sends)
        assertTrue(clock.waits.isEmpty())
        assertEquals("REVIEW", batchStore.load(b.id).state)
        assertEquals(original.cues.first(), videoStore.load(original.id).cues.first())
        println("Legacy saved error: 0 requests on redelivery, 1 on manual start, 0 ms wait, 1 saved edit kept")
    }

    private fun ocrIdentity(cue: VideoCue) = cue.copy(translation = "", model = "", error = "", draftVi = "",
        polishedVi = "", refinementState = "", qualityFlags = emptyList(), version = 1)

    private suspend fun runBatch(id: String, jobs: BatchJobs, start: Boolean = true, timeoutMs: Long = 20_000) {
        val done = CompletableDeferred<Unit>()
        try {
            if (start) jobs.start(id)
            jobs.execute(id) { done.complete(Unit) }
            withTimeout(timeoutMs) { done.await() }
        } finally {
            jobs.cancel()
            if (jobs.busy.value != null) withTimeout(20_000) { done.await() }
        }
    }

    private fun lastFailure(project: VideoProject) =
        videoStore.load(project.id).pipeline.task(BatchPipelineStage.TRANSLATE).lastApiFailure

    private fun quotaBody(id: String, value: String, vararg extra: JSONObject): String {
        val violations = JSONArray().put(JSONObject().put("quotaId", id).put("quotaValue", value))
        val details = JSONArray().put(JSONObject().put("@type", "type.googleapis.com/google.rpc.QuotaFailure")
            .put("violations", violations))
        extra.forEach { details.put(it) }
        return JSONObject().put("error", JSONObject().put("details", details)).toString()
    }

    private suspend fun withFakeExchange(
        clock: FakeClock,
        exchange: suspend (VideoTranslationPlan.Batch, Int) -> String,
        block: suspend () -> Unit
    ) {
        val previousQuota = VideoGeminiAccounts.overrideBatchQuotaForTest
        val previousExchange = VideoTranslationClient.overrideBatchExchangeForTest
        try {
            VideoGeminiAccounts.overrideBatchQuotaForTest = VideoBatchGeminiQuota(clockMs = clock::read, awaitTurn = {})
            VideoTranslationClient.overrideBatchExchangeForTest = exchange
            block()
        } finally {
            VideoTranslationClient.overrideBatchExchangeForTest = previousExchange
            VideoGeminiAccounts.overrideBatchQuotaForTest = previousQuota
        }
    }

    private data class Send(val slot: Int, val timeMs: Long)

    private class FakeClock {
        private val now = AtomicLong(START)
        val waits = mutableListOf<Long>()
        fun read(): Long = now.get()
        fun advance(ms: Long) { now.addAndGet(ms) }
        fun pauseForRetry(ms: Long) { waits += ms; advance(ms) }
        companion object { const val START = 1_800_000_000_000L }
    }
}

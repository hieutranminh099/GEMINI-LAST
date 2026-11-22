package com.gemini.ocr.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BilingualProjectTest {
    private val old = VideoProject("fixture", "Film", "file:///fixture", 4000, 360, 640,
        scanMs=4000, cues=listOf(VideoCue("a",100,830,"你","Mẹ",approved=true),
            VideoCue("b",830,1600,"妈妈","cậu",approved=true), VideoCue("c",2000,3000,"刘老师","Cô Lưu.")))

    @Test fun legacyRoundTripAndManualJoinKeepBothTracksAndExactTiming() {
        val vi=VideoProject.from(old.json())
        assertEquals("vi",vi.targetLanguage)
        val en=vi.selectTarget("en")
        assertTrue(en.cues.all { it.translation.isEmpty() && !it.approved })
        val edited=en.copy(cues=en.cues.mapIndexed { i,c -> c.copy(translation=listOf("Your","mother","Ms. Liu.")[i]) })
        val restored=VideoProject.from(JSONObject(edited.json().toString()))
        assertEquals(vi.cues,restored.selectTarget("vi").cues)
        val joined=restored.joinNext("a")
        assertEquals(listOf("你妈妈","刘老师"),joined.cues.map { it.source })
        assertEquals(listOf("Your mother","Ms. Liu."),joined.cues.map { it.translation })
        assertEquals("Mẹ cậu",joined.selectTarget("vi").cues.first().translation)
        assertTrue(VideoTimeline.srt(joined.cues,4000).contains("00:00:00,100 --> 00:00:01,600"))
        assertTrue(VideoTimeline.ass(joined).contains("Ms. Liu."))
        assertTrue(VideoDownloads.resultName("Film.mp4","srt","en").contains("-en-"))
    }

    @Test fun selectedFlashModelUsesSupportedThinkingAndLeavesTheExistingDefaultIntact() {
        val options = com.gemini.ocr.settings.AppSettings
        assertEquals(com.gemini.ocr.network.StandaloneTranslator.FAST_MODEL, options.VIDEO_MODELS.first())
        val batch = VideoTranslationPlan.forCue(old.cues, 1).copy(targetLanguage = "en",
            requestModel = options.VIDEO_FLASH_MODEL, strictDialogue = true)
        assertTrue(batch.usesDialogueDeadline)
        assertFalse(batch.copy(strictDialogue = false).usesDialogueDeadline)
        assertEquals(240_000, VideoTranslationClient.requestClient(batch.usesDialogueDeadline).callTimeoutMillis)
        val config = JSONObject(VideoTranslationClient.buildRequest(batch, "document", "")).getJSONObject("generationConfig")
        assertEquals("low", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        val noted = VideoStagedPipeline.withContext(batch, "00:01 | The student addresses a teacher.")
        val noteConfig = JSONObject(VideoTranslationClient.buildRequest(noted, "document", "")).getJSONObject("generationConfig")
        assertEquals("low", noteConfig.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertEquals(1024, noteConfig.getInt("maxOutputTokens"))
        val item = noteConfig.getJSONObject("responseSchema").getJSONObject("properties")
            .getJSONObject("translations").getJSONObject("items")
        assertEquals("resolvedSource", item.getJSONArray("propertyOrdering").getString(1))
    }

    @Test fun timedSceneNotesUseTheSharedSourceClockAndDoNotLeakAcrossGaps() {
        val rows = JSONArray(old.cues.map { it.json() })
        val supplied = "The teacher is the host.\n00:00.829 | first\n00:00,830 | second\n00:01.800 | gap\n00:00:02 | third"
        assertEquals("The teacher is the host.", VideoDialoguePrompt.bindSceneNotes(rows, supplied))
        assertEquals(listOf("first", "second", "third"), (0 until rows.length()).map { rows.getJSONObject(it).getString("sceneNote") })
        assertFalse(rows.toString().contains("gap"))
    }

    @Test fun englishPromptUsesChineseAndNeighboringSpeechWithoutVietnameseOutputInstructions() {
        val batch=VideoTranslationPlan.forCue(old.selectTarget("en").cues,2)
            .copy(targetLanguage="en",strictDialogue=true)
        val request=JSONObject(VideoTranslationClient.buildRequest(batch,"document",""))
        val prompt=request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(prompt.contains("natural spoken English"))
        val text=request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(text.contains("刘老师") && text.contains("妈妈"))
        assertFalse(text.contains("Cô Lưu"))
    }

    @Test fun singleCueRetryPreservesBothTracksOnFailureAndProtectsReviewedText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VideoStore(context)
        val jobs = VideoJobs(context)
        val vi = old.copy(id = UUID.randomUUID().toString(), translationMode = VideoTranslationMode.GEMINI,
            dialogueContext = "00:02 | A student greets her teacher.",
            cues = old.cues.map { if (it.id == "b") it.copy(approved = false, model = "user-edited") else it })
        val en = vi.selectTarget("en").copy(cues = vi.selectTarget("en").cues.mapIndexed { i, cue ->
            cue.copy(translation = listOf("You", "Mother", "Teacher Liu.")[i], approved = i == 0,
                model = if (i == 1) "user-edited" else "")
        })
        fun reply(id: String?, translation: String): String {
            val rows = JSONArray()
            id?.let { rows.put(JSONObject().put("id", it).put("translation", translation).put("resolvedSource", "刘老师")) }
            val text = JSONObject().put("translations", rows).toString()
            val parts = JSONArray().put(JSONObject().put("text", text))
            return JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts", parts)))).toString()
        }
        val previous = VideoTranslationClient.overrideResponseForTest
        try {
            for ((language, revised) in listOf("vi" to "Chào cô Lưu.", "en" to "Hello, Ms. Liu.")) {
                store.save(en.selectTarget(language))
                val baseline = store.load(en.id)
                val before = baseline.json().toString()
                var calls = 0
                var errorCode: String? = null
                var returnedId: String? = null
                VideoTranslationClient.overrideResponseForTest = { batch ->
                    calls++
                    assertEquals(listOf("c"), batch.cueIds)
                    assertEquals(language, batch.targetLanguage)
                    errorCode?.let { throw VideoApiException(it, transient = true, detail = "Controlled request failure") }
                    reply(returnedId, revised)
                }
                for (id in listOf("a", "b")) {
                    assertTrue(runCatching { jobs.retryCue(en.id, id) }.exceptionOrNull() is IllegalArgumentException)
                    assertEquals(before, store.load(en.id).json().toString())
                }
                assertEquals(0, calls)
                for (failure in listOf("API_SERVICE_503", "API_TIMEOUT", "TEXT_INCOMPLETE_RESPONSE")) {
                    errorCode = failure.takeUnless { it == "TEXT_INCOMPLETE_RESPONSE" }
                    val error = runCatching { jobs.retryCue(en.id, "c") }.exceptionOrNull() as VideoApiException
                    assertEquals(failure, error.code)
                    assertEquals(before, store.load(en.id).json().toString())
                }
                returnedId = "c"
                jobs.retryCue(en.id, "c")
                val updated = store.load(en.id)
                assertEquals(revised, updated.cues.single { it.id == "c" }.translation)
                assertEquals(baseline.cues.take(2), updated.cues.take(2))
                assertEquals(baseline.otherCues, updated.otherCues)
                assertEquals(4, calls)
            }
        } finally {
            VideoTranslationClient.overrideResponseForTest = previous
        }
    }
}

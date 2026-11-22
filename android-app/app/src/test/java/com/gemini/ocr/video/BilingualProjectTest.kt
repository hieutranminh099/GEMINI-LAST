package com.gemini.ocr.video

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        assertEquals("medium", noteConfig.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        val item = noteConfig.getJSONObject("responseSchema").getJSONObject("properties")
            .getJSONObject("translations").getJSONObject("items")
        assertEquals("resolvedSource", item.getJSONArray("propertyOrdering").getString(1))
    }

    @Test fun timedSceneNotesUseTheSharedSourceClockAndDoNotLeakAcrossGaps() {
        val rows = org.json.JSONArray(old.cues.map { it.json() })
        val supplied = "The teacher is the host.\n00:00.829 | first\n00:00,830 | second\n00:01.800 | gap\n00:00:02 | third"
        assertTrue(VideoDialoguePrompt.attachSceneNotes(rows, supplied))
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
}

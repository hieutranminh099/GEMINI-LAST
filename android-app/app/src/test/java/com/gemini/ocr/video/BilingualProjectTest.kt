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

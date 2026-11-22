package com.gemini.ocr.video

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BilingualVideoEditsNativeTest {
    @Test(timeout = 30_000L)
    fun preservesBothTracksWhenEditingJoiningAndReopening(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("executeBilingualEdits") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        require(context.packageName == "com.gemini.ocr.codexbench")
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))

        val input = File(context.filesDir, "long-video-ocr-benchmark.mp4")
        val subtitles = File(context.filesDir, "bilingual-edits.srt").apply {
            writeText("""
                1
                00:36:17,000 --> 00:36:18,000
                老师，

                2
                00:36:18,000 --> 00:36:19,500
                请等一下。

                3
                00:36:21,000 --> 00:36:24,000
                我马上回来。
            """.trimIndent() + "\n")
        }
        val jobs = VideoJobs.get(context)
        val store = VideoStore(context)
        val video = jobs.create(Uri.fromFile(input), "Bilingual editing fixture")
        assertEquals(2_185_000L, video.durationMs)
        jobs.selectSubtitle(video.id, Uri.fromFile(subtitles), subtitles.name)
        val selected = store.load(video.id)
        val cues = VideoSubtitles.readSidecar(context, selected)
        assertEquals(listOf(2_177_000L, 2_178_000L, 2_181_000L), cues.map { it.startMs })

        // Seed both translation tracks so this verifies the editor and the production store offline.
        val viText = listOf("Thưa cô,", "xin đợi một chút.", "Em sẽ quay lại ngay.")
        val enText = listOf("Teacher,", "please wait a moment.", "I'll be right back.")
        val vi = cues.mapIndexed { i, cue -> cue.copy(translation = viText[i], approved = i == 2) }
        val en = cues.mapIndexed { i, cue -> cue.copy(translation = enText[i], approved = i == 2) }
        store.save(selected.copy(cues = vi, otherCues = en, scanMs = video.durationMs,
            stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING))

        jobs.updateCue(video.id, vi[0].copy(translation = "Cô ơi,"))
        val edited = VideoStore(context).load(video.id)
        assertEquals("Cô ơi,", edited.cues[0].translation)
        assertTrue(edited.cues[0].protectedText)
        assertEquals(en, edited.otherCues)

        jobs.joinNext(video.id, cues[0].id, edited.updatedAt)
        val reopened = VideoStore(context).load(video.id)
        assertEquals(listOf(cues[0].id, cues[2].id), reopened.cues.map { it.id })
        assertEquals("老师，请等一下。", reopened.cues.first().source)
        assertEquals(cues[0].startMs, reopened.cues.first().startMs)
        assertEquals(cues[1].endMs, reopened.cues.first().endMs)
        assertEquals("Cô ơi, xin đợi một chút.", reopened.cues.first().translation)
        assertFalse(reopened.cues.first().approved)
        assertEquals(vi.last(), reopened.cues.last())

        jobs.selectTarget(video.id, "en")
        val english = VideoStore(context).load(video.id)
        assertEquals(reopened.cues.map { it.id }, english.cues.map { it.id })
        assertEquals(reopened.cues.first().startMs, english.cues.first().startMs)
        assertEquals(reopened.cues.first().endMs, english.cues.first().endMs)
        assertEquals("Teacher, please wait a moment.", english.cues.first().translation)
        assertEquals(en.last(), english.cues.last())
        val joined = english.cues.first()
        assertEquals(joined, VideoTimeline.active(english.cues, cues[1].startMs))
        assertNull(VideoTimeline.active(english.cues, joined.endMs))
        requireNotNull(VideoDecoder.preview(context, Uri.parse(english.uri), cues[1].startMs)).recycle()

        jobs.selectTarget(video.id, "vi")
        val vietnamese = VideoStore(context).load(video.id)
        assertEquals(reopened.cues, vietnamese.cues)
        assertEquals(english.cues, vietnamese.otherCues)
        assertEquals(0, vietnamese.cloudCalls)
    }
}

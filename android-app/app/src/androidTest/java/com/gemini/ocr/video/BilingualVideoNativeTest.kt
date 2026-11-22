package com.gemini.ocr.video

import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt in only on a new, disposable emulator. Drive transport is tested separately. */
@RunWith(AndroidJUnit4::class)
class BilingualVideoNativeTest {
    @Test fun translateExportAndRecover() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("bilingualPhase") in setOf("source", "recovery"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && context.packageName == "com.gemini.ocr.bilingualtest")
        val settings = AppSettings(context)
        require(!settings.hasGeminiVideoKey())
        val passwordFile = File(context.filesDir, "passphrase")
        val password = passwordFile.readText().toCharArray()
        check(passwordFile.delete())
        val recoveryFile = File(context.filesDir, "sealed.json")
        val sourcePhase = args.getString("bilingualPhase") == "source"
        try {
            if (sourcePhase) {
                val credential = File(context.filesDir, "credential")
                settings.standaloneApiKey = credential.readText()
                check(credential.delete())
                settings.videoTextModel = "gemini-2.5-flash"
            } else {
                require(!File(context.filesDir, "credential").exists())
                settings.restoreGemini(JSONObject(recoveryFile.readText()), password)
                assertEquals("gemini-2.5-flash", settings.videoTextModel)
            }
            VideoTranslationClient(context).checkModel()
            val jobs = VideoJobs.get(context)
            val video = File(context.filesDir, "dialogue.mp4")
            val subtitle = File(context.filesDir, "zh.srt")
            val p = jobs.create(Uri.fromFile(video), "Dialogue.mp4")
            jobs.selectSubtitle(p.id, Uri.fromFile(subtitle), subtitle.name)
            val store = VideoStore(context)
            jobs.save(store.load(p.id).copy(translationMode = VideoTranslationMode.GEMINI, profile = "document",
                dialogueContext = "The student speaks to teacher Liu in her kitchen after dinner. They are clearing the dishes.", style = p.style.copy(coverOriginal = true)))
            suspend fun waitForJob() {
                withTimeout(150_000L) { while (jobs.busy.value != null) delay(100) }
            }
            ActivityScenario.launch<VideoActivity>(Intent(context, VideoActivity::class.java).putExtra("project", p.id)).use { activity ->
                val languages = if (sourcePhase) listOf("vi", "en") else listOf("en")
                var first = emptyList<VideoCue>()
                for (language in languages) {
                    jobs.selectTarget(p.id, language)
                    activity.onActivity { jobs.start(p.id) }
                    waitForJob()
                    val result = store.load(p.id)
                    assertEquals(result.message, VideoStage.REVIEW, result.stage)
                    assertTrue(result.cloudCalls > 0)
                    assertEquals(listOf(0L, 4000L, 8000L), result.cues.map { it.startMs })
                    val lines = result.cues.map { it.translation.lowercase() }
                    if (language == "en") {
                        assertTrue(lines[0].contains("mother") || lines[0].contains("mom") || lines[0].contains("mum"))
                        assertTrue(lines[2].contains("dish") || lines[2].contains("wash"))
                        if (sourcePhase) assertEquals(first, result.selectTarget("vi").cues)
                    } else {
                        assertTrue(lines[0].contains("mẹ") || lines[0].contains("má"))
                        assertTrue(lines[2].contains("rửa"))
                        first = result.cues
                    }
                    for (ass in listOf(false, true)) {
                        val download = VideoDownloads.saveSubtitle(context, result, ass)
                        assertTrue(download.name.contains("-$language-"))
                        val text = context.contentResolver.openInputStream(download.uri)!!.bufferedReader().use { it.readText() }
                        assertTrue(text.contains(result.cues.first().translation))
                    }
                    activity.onActivity { jobs.exportMp4(p.id) }
                    waitForJob()
                    val exported = store.load(p.id)
                    assertEquals(exported.message, VideoStage.COMPLETE, exported.stage)
                    val mp4 = VideoDownloads.saveRenderedMp4(context, exported)
                    assertTrue(mp4.name.contains("-$language-"))
                    MediaMetadataRetriever().use { reader ->
                        reader.setDataSource(context, mp4.uri)
                        assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                        val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                        assertTrue(kotlin.math.abs(duration - p.durationMs) <= 150)
                        for (time in listOf(1500L, 5500L, 9500L)) {
                            val frame = requireNotNull(reader.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST))
                            // The test source has a bright bilingual band inside this mask.
                            for (y in listOf(.76f, .92f)) {
                                val pixel = frame.getPixel((.09f * frame.width).toInt(), (y * frame.height).toInt())
                                assertTrue(Color.red(pixel) < 35 && Color.green(pixel) < 35 && Color.blue(pixel) < 35)
                            }
                            val yellow = (frame.height * 7 / 10 until frame.height).any { y ->
                                (0 until frame.width step 2).any { x ->
                                    val pixel = frame.getPixel(x, y)
                                    Color.red(pixel) > 160 && Color.green(pixel) > 140 && Color.blue(pixel) < 120
                                }
                            }
                            assertTrue("Visible translation at $time", yellow)
                            frame.recycle()
                        }
                    }
                }
            }
            if (sourcePhase) recoveryFile.writeText(settings.backupGemini(password).toString())
        } finally { password.fill('\u0000') }
    }
}

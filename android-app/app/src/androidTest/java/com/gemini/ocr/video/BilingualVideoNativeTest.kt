package com.gemini.ocr.video

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import com.gemini.ocr.video.sync.VideoCloudSync
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt in only on a new, disposable emulator. Drive transport is tested separately. */
@RunWith(AndroidJUnit4::class)
class BilingualVideoNativeTest {
    @Test fun translateExportAndRecover(): Unit = runBlocking {
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
        val simulate = args.getString("bilingualProvider") == "fixture"
        val evidence = File(context.filesDir, "verification.txt")
        if (sourcePhase && simulate) {
            val drive = VideoCloudSync.get(context)
            val outcome = try {
                withTimeoutOrNull(30_000L) {
                    if (drive.authorize() != null) "CONSENT_REQUIRED" else "GRANTED"
                } ?: "TIMEOUT"
            } catch (error: Exception) {
                // Persist only the app's sanitized error code, never an account or a token.
                Regex("""Google Drive \[request/([A-Za-z0-9_]+)]""")
                    .find(drive.state.value.message)?.groupValues?.get(1) ?: "UNAVAILABLE"
            }
            evidence.appendText("drive_authorization=$outcome\n")
        }
        if (simulate) {
            VideoTranslationClient.overrideResponseForTest = { batch ->
                val lines = if (batch.targetLanguage == "en") listOf("My mom is the homeroom teacher.",
                    "Ms. Liu, you made so many dishes.", "Let me help you with the dishes.")
                    else listOf("Mẹ tôi là giáo viên chủ nhiệm.", "Cô Lưu, cô nấu nhiều món quá.", "Để em rửa bát giúp cô ạ.")
                val rows = JSONArray(batch.indices.mapIndexed { i, index ->
                    JSONObject().put("id", batch.cueIds[i]).put("translation", lines[index])
                })
                val parts = JSONArray().put(JSONObject().put("text", JSONObject().put("translations", rows).toString()))
                JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
                    .put("content", JSONObject().put("parts", parts)))).toString()
            }
        }
        val player = AtomicReference<Player?>()
        val preview = AtomicReference<VideoPreviewFrame?>()
        try {
            if (sourcePhase) {
                val credential = File(context.filesDir, "credential")
                if (simulate) settings.standaloneApiKey = "synthetic-offline-fixture" else {
                    settings.standaloneApiKey = credential.readText()
                    check(credential.delete())
                }
                if (simulate) settings.videoTextModel = "gemini-2.5-flash" else {
                    // Only the acceptance test selects an accessible model, before any translation.
                    for (model in AppSettings.VIDEO_MODELS) {
                        settings.videoTextModel = model
                        try {
                            VideoTranslationClient(context).checkModel()
                            break
                        } catch (error: VideoApiException) {
                            if (error.code != "API_MODEL" || model == AppSettings.VIDEO_MODELS.last()) throw error
                        }
                    }
                }
            } else {
                require(!File(context.filesDir, "credential").exists())
                settings.restoreGemini(JSONObject(recoveryFile.readText()), password)
                assertEquals(File(context.filesDir, "expected-model").readText(), settings.videoTextModel)
                if (!simulate) VideoTranslationClient(context).checkModel()
            }
            if (!sourcePhase && !simulate) {
                verifyDialogueExcerpts(VideoTranslationClient(context), settings, evidence)
            }
            if (sourcePhase) File(context.filesDir, "expected-model").writeText(settings.videoTextModel)
            evidence.appendText("model=${settings.videoTextModel}\n")
            val jobs = VideoJobs.get(context)
            val video = File(context.filesDir, "dialogue.mp4")
            val subtitle = File(context.filesDir, "zh.srt")
            MediaMetadataRetriever().use { source ->
                source.setDataSource(video.absolutePath)
                for (ms in listOf(1500L, 5500L, 9500L)) {
                    val bitmap = requireNotNull(source.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST))
                    try {
                        assertTrue("Fixture must have a visible white bilingual band at ${ms}ms",
                            bandPercentages(bitmap).first >= 75)
                    } finally { bitmap.recycle() }
                }
            }
            val p = jobs.create(Uri.fromFile(video), "Dialogue.mp4")
            jobs.selectSubtitle(p.id, Uri.fromFile(subtitle), subtitle.name)
            val store = VideoStore(context)
            jobs.save(store.load(p.id).copy(translationMode = VideoTranslationMode.GEMINI, profile = "document",
                dialogueContext = "The student speaks to teacher Liu in her kitchen after dinner. They are clearing the dishes.", roi = listOf(.25f, .80f, .75f, .86f),
                style = p.style.copy(coverOriginal = true, coverFrame = VideoSubtitleFrame(.06f, .72f, .94f, .96f))))
            suspend fun waitForJob() {
                withTimeout(150_000L) { while (jobs.busy.value != null) delay(100) }
            }
            VideoPreviewDiagnostics.onPlayerForTest = { id, value -> if (id == p.id) player.set(value) }
            VideoPreviewDiagnostics.onFrameForTest = { value -> if (value.projectId == p.id) preview.set(value) }
            val intent = Intent(context, VideoActivity::class.java).putExtra("project", p.id)
            val languages = if (sourcePhase) listOf("vi", "en") else listOf("en")
            var first = emptyList<VideoCue>()
            for (language in languages) {
                jobs.selectTarget(p.id, language)
                ActivityScenario.launch<VideoActivity>(intent).use { activity ->
                    activity.onActivity { jobs.start(p.id) }
                    waitForJob()
                }
                val reviewIntent = Intent(intent).putExtra("ux_step", "review")
                ActivityScenario.launch<VideoActivity>(reviewIntent).use { activity ->
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
                    assertTrue("Teacher Liu must retain her name", lines[1].contains(if (language == "en") "liu" else "lưu"))
                    evidence.appendText("translated=$language\n")
                    withTimeout(30_000L) { while (player.get() == null) delay(100) }
                    for (cue in result.cues) {
                        val timeMs = cue.startMs + 1_500
                        activity.onActivity { player.get()!!.apply { pause(); seekTo(timeMs) } }
                        withTimeout(15_000L) {
                            while (preview.get()?.let { it.positionMs == timeMs && it.cueId == cue.id && it.text == cue.translation } != true) delay(50)
                        }
                    }
                    evidence.appendText("preview=$language\n")
                }
                val exportIntent = Intent(intent).putExtra("ux_step", "export")
                ActivityScenario.launch<VideoActivity>(exportIntent).use { activity ->
                    val result = store.load(p.id)
                    for (ass in listOf(false, true)) {
                        val download = VideoDownloads.saveSubtitle(context, result, ass)
                        assertTrue(download.name.contains("-$language-"))
                        val text = context.contentResolver.openInputStream(download.uri)!!.bufferedReader().use { it.readText() }
                        assertTrue(text.contains(result.cues.first().translation))
                    }
                    evidence.appendText("subtitles=$language\n")
                    activity.onActivity { jobs.exportMp4(p.id) }
                    waitForJob()
                    val exported = store.load(p.id)
                    assertEquals(exported.message, VideoStage.COMPLETE, exported.stage)
                    evidence.appendText("rendered=$language\n")
                    val mp4 = VideoDownloads.saveRenderedMp4(context, exported)
                    assertTrue(mp4.name.contains("-$language-"))
                    MediaMetadataRetriever().use { reader ->
                        reader.setDataSource(context, mp4.uri)
                        assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                        val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                        assertTrue(kotlin.math.abs(duration - p.durationMs) <= 150)
                        for (time in listOf(1500L, 5500L, 9500L)) {
                            val frame = requireNotNull(reader.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST))
                            // A wrapped yellow glyph can cross either margin. Check the whole
                            // former white band for exposed source pixels and opaque background.
                            val (whitePercent, blackPercent) = bandPercentages(frame)
                            assertTrue("White source band at ${time}ms: $whitePercent% white, $blackPercent% black",
                                whitePercent < 2 && blackPercent >= 60)
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
                    evidence.appendText("exported=$language\n")
                }
            }
            if (sourcePhase) {
                val sealed = settings.backupGemini(password)
                recoveryFile.writeText(sealed.toString())
                settings.standaloneApiKey = ""
                assertThrows(IllegalArgumentException::class.java) { settings.restoreGemini(sealed, password) }
                assertFalse(settings.hasGeminiVideoKey())
            }
        } finally {
            VideoTranslationClient.overrideResponseForTest = null
            VideoPreviewDiagnostics.onPlayerForTest = null
            VideoPreviewDiagnostics.onFrameForTest = null
            password.fill('\u0000')
        }
    }

    private fun bandPercentages(frame: Bitmap): Pair<Int, Int> {
        var white = 0
        var black = 0
        var sampled = 0
        // Sample across the white drawbox at (22, 470) with size 316 x 131 on the 360 x 640 source.
        for (y in frame.height * 471 / 640 until frame.height * 600 / 640 step 3) {
            for (x in frame.width * 23 / 360 until frame.width * 337 / 360 step 3) {
                val pixel = frame.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (minOf(r, g, b) > 170 && maxOf(r, g, b) - minOf(r, g, b) < 30) white++
                if (maxOf(r, g, b) < 45) black++
                sampled++
            }
        }
        return (100 * white / sampled) to (100 * black / sampled)
    }

    /** Original review excerpts. No test name, timestamp or translation enters the app prompt code. */
    private suspend fun verifyDialogueExcerpts(client: VideoTranslationClient, settings: AppSettings, evidence: File) {
        val sources = listOf("哪个又被他盯上了？", "我妈是班主任。", "我妈不让我乱说。", "凯子。",
            "强哥，我手上钱不够。", "张乔。", "都没准备什么菜。", "你妈妈是老师吗？",
            "是我让小凯别说的。", "张强。", "我帮您洗吧。", "我爸忙着挣钱，顾不上我。",
            "刘老师！", "章强。", "你们干嘛？")
        val rows = sources.mapIndexed { i, text -> VideoCue("excerpt-$i", i * 4000L, i * 4000L + 3500, text) }
        val context = "Teacher Liu is Li Kai's mother. Classmate Zhang Qiang visits unexpectedly. " +
            "At dinner the table already has several dishes. At 00:40 the student is standing at the sink " +
            "holding the used dinner plates and speaking politely to his teacher. " +
            "Zhang Qiang then mentions his father. At 00:56 Kai opens the door. His mother and " +
            "his classmate are the only two people he sees. He questions both of them. " +
            "Three unconfirmed OCR transcriptions of names occur. Their identities have not been verified."
        val batch = requireNotNull(VideoFullDialoguePlan.next(rows, rows.size * 4000L, context))
        for (language in listOf("vi", "en")) {
            val request = batch.copy(targetLanguage = language, requestModel = settings.videoTextModel, strictDialogue = true)
            val result = client.translate(settings.effectiveStandaloneApiKey(), request, "document", "") {}
            assertEquals(request.cueIds, result.accepted.keys.toList())
            val text = rows.map { result.accepted.getValue(it.id).lowercase() }
            // These checks cover explicit lexical evidence. Subtle pronouns need the original scene.
            fun checkWords(label: String, index: Int, vararg alternatives: String) {
                val found = alternatives.any { text[index].contains(it) }
                evidence.appendText("excerpt_${language}_$label=${if (found) "MATCH" else "REVIEW"}\n")
                if (label == "dishes" || label == "pair") {
                    assertTrue("Scene context required for excerpt $index ($language/$label)", found)
                }
            }
            if (language == "vi") {
                checkWords("target", 0, "nhắm", "để mắt", "ngắm", "để ý", "lọt vào mắt", "tăm tia", "dòm ngó")
                checkWords("teacher", 1, "chủ nhiệm")
                checkWords("money", 4, "tiền")
                checkWords("host", 6, "món", "đồ ăn", "thức ăn")
                checkWords("dishes", 10, "bát", "chén", "đĩa", "dĩa")
                checkWords("father", 11, "bố", "ba", "cha")
                checkWords("address", 12, "cô lưu")
                checkWords("pair", 14, "hai", "mẹ")
            } else {
                checkWords("target", 0, "eye", "sight", "target", "after", "pick", "mark", "watch")
                checkWords("teacher", 1, "homeroom", "form teacher", "class teacher", "head teacher")
                checkWords("money", 4, "money", "cash", "afford", "enough on me")
                checkWords("host", 6, "dish", "food", "prepare", "special", "cook", "make much")
                checkWords("dishes", 10, "dish", "plate", "bowl", "wash up", "washing up", "washing-up")
                checkWords("father", 11, "dad", "father")
                checkWords("address", 12, "liu")
                checkWords("pair", 14, "two", "both", "mom", "mum")
            }
            assertFalse("A second teacher was invented", listOf("đinh", "ding").any { text[12].contains(it) })
            evidence.appendText("excerpt_rows_$language=${text.size}\n")
        }
    }
}

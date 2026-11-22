package com.gemini.ocr.video

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gemini.ocr.BuildConfig
import com.gemini.ocr.SessionState
import com.gemini.ocr.video.sync.DriveAuthorizationRequired
import com.gemini.ocr.video.sync.VideoCloudSync
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Runs only with an explicitly authorized synthetic fixture and enrolled Google test account. */
@RunWith(AndroidJUnit4::class)
class BilingualDriveProjectNativeTest {
    @Test(timeout = 240_000L)
    fun publishListRestoreSyntheticProject(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("executeProjectDrive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(BuildConfig.DEBUG && !BuildConfig.VERIFICATION_OFFLINE_NETWORK)
        require(!SessionState.running.value)
        val jobs = VideoJobs.get(context)
        require(jobs.busy.value == null && jobs.batchBusy.value == null)
        val files = File(context.filesDir, "stability-fixtures").apply { mkdirs() }
        val expectedHash = args.getString("expectedSha256").orEmpty()
        require(expectedHash.matches(Regex("[a-f0-9]{64}")))
        val auditId = args.getString("driveAuditId")?.let { UUID.fromString(it).toString() }
            ?: UUID.randomUUID().toString()
        val existingId = args.getString("driveProjectId")?.let { UUID.fromString(it).toString() }
        val id = existingId ?: UUID.randomUUID().toString()
        val reportFile = File(files, "drive-project-audit-$auditId.json")
        val report = JSONObject().put("schema", 1).put("result", "RUNNING").put("fixtureProjectId", id)
            .put("mode", if (existingId == null) "publish-and-restore" else "restore-only")
            .put("sourceSha256", expectedHash)
        val started = SystemClock.elapsedRealtime()
        var phase = "authorize"
        fun record() {
            report.put("phase", phase).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }
        record()
        try {
            withTimeout(220_000L) {
                val sync = VideoCloudSync.get(context)
                require(!sync.state.value.enabled) { "Use a test installation with automatic backups disabled." }
                if (sync.state.value.accountEmail.isBlank() || sync.authorize(selectAccount = false) != null) {
                    throw AssumptionViolatedException("CONSENT_REQUIRED")
                }
                val store = VideoStore(context)
                val original = if (existingId == null) {
                    phase = "publish"
                    record()
                    val source = File(files, "drive-project-fixture-$auditId.mp4")
                    val project = store.save(fixture(context, source, id, expectedHash))
                    sync.backupProject(project.id)
                    store.save(project.copy(cues = project.cues.map { it.copy(translation = LOCAL_EDIT) }))
                } else {
                    require(store.list().none { it.id == id }) { "Restore-only requires an installation without the original fixture project." }
                    null
                }
                phase = "list"
                record()
                val snapshot = sync.listCloud().single { it.projectId == id && it.title == FIXTURE_TITLE }
                report.put("listed", true)
                phase = "restore"
                record()
                val restored = sync.restore(snapshot)
                assertNotEquals(id, restored.id)
                val saved = store.load(restored.id)
                assertEquals("en", saved.targetLanguage)
                assertEquals(EN, saved.cues.single().translation)
                assertTrue(saved.cues.single().approved)
                val vietnamese = saved.selectTarget("vi").cues.single()
                assertEquals(VI, vietnamese.translation)
                assertTrue(vietnamese.approved)
                val media = File(checkNotNull(Uri.parse(saved.uri).path))
                assertEquals(expectedHash, sha256(media))
                original?.let {
                    val retained = store.load(it.id).cues.single()
                    assertEquals(LOCAL_EDIT, retained.translation)
                    assertTrue(retained.approved)
                    report.put("localApprovedEditPreserved", true)
                }
                report.put("result", "PASS").put("bothApprovedTracksRestored", true).put("restoredSha256", expectedHash)
                phase = "complete"
            }
        } catch (e: AssumptionViolatedException) {
            report.put("result", "BLOCKED").put("reason", "CONSENT_REQUIRED")
            throw e
        } catch (e: Throwable) {
            val apiError = generateSequence(e) { it.cause }.filterIsInstance<ApiException>().firstOrNull()
            val requiresConsent = e is DriveAuthorizationRequired || apiError != null
            report.put("result", if (requiresConsent) "BLOCKED" else "FAIL")
                .put("failureType", e.javaClass.simpleName)
                .put("googleStatusCode", apiError?.statusCode ?: JSONObject.NULL)
            if (requiresConsent) throw AssumptionViolatedException("Google authorization stopped the test. Check the sanitized report.")
            throw AssertionError("Drive project checks failed in $phase. Check the sanitized report.")
        } finally {
            record()
        }
    }

    private fun fixture(context: Context, file: File, id: String, expectedHash: String): VideoProject {
        require(file.isFile && file.length() in 1L..2L * 1024 * 1024 && sha256(file) == expectedHash)
        val uri = Uri.fromFile(file)
        val info = VideoDecoder.metadata(context, uri)
        require(info.durationMs in 1_000L..10_000L)
        val vi = VideoCue("fixture", 100, 800, "你好", VI, approved = true)
        return VideoProject(id, FIXTURE_TITLE, uri.toString(), info.durationMs, info.width, info.height,
            cues = listOf(vi.copy(translation = EN)), stage = VideoStage.REVIEW, resumeStage = VideoStage.TRANSLATING,
            scanMs = info.durationMs, targetLanguage = "en", otherCues = listOf(vi))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FIXTURE_TITLE = "Codex synthetic Drive QA"
        const val VI = "Xin chào"
        const val EN = "Hello"
        const val LOCAL_EDIT = "Hello, edited locally"
    }
}

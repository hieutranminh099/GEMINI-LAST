package com.gemini.ocr.batch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gemini.ocr.ui.ExpandableSection
import com.gemini.ocr.ui.StatusNotice
import com.gemini.ocr.video.BatchPipelineStage
import com.gemini.ocr.video.VideoApiFailureCheckpoint
import com.gemini.ocr.video.VideoProject
import com.gemini.ocr.video.VideoTranslationMode
import com.gemini.ocr.video.VideoTranslationPlan
import com.gemini.ocr.video.VideoResponseFailureReason
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BatchAutoRetrySetting(enabled: Boolean, editable: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tự tiếp tục khi gặp lỗi tạm thời", style = MaterialTheme.typography.titleSmall)
            Text("Tự tiếp tục phần còn thiếu tối đa 5 lượt. Ứng dụng dùng thời gian chờ Gemini cung cấp, hoặc chờ khoảng 1 phút khi chưa có thông tin.",
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, onCheckedChange = onChange, enabled = editable,
            modifier = Modifier.testTag("batch_auto_retry"))
    }
}

/** Countdowns observe persisted deadlines. Only the worker decides when to resume. */
@Composable
internal fun BatchRetryStatus(batch: BatchProject, projects: List<VideoProject>, running: Boolean) {
    val deadline = if (running) batchRetryDeadline(batch, projects)
        else if (batch.state in setOf("PAUSED", "FAILED")) batch.retryAfterMs else 0L
    if (deadline == 0L && (!running || !BatchWorkflowUi.retryWaiting(batch, projects))) return
    val seconds by produceState(batchRetrySeconds(deadline, System.currentTimeMillis()), batch.id, deadline) {
        value = batchRetrySeconds(deadline, System.currentTimeMillis())
        while (value > 0) {
            delay(250)
            value = batchRetrySeconds(deadline, System.currentTimeMillis())
        }
    }
    if (!running && seconds == 0L) return
    val remaining = BatchWorkflowUi.duration(seconds * 1000)
    val message = when {
        !running -> "Có thể bấm Tiếp tục sau ${formatBatchTime(deadline)}. Còn $remaining."
        deadline == 0L -> "Đang tự khôi phục. Tiến độ đã được lưu."
        seconds == 0L -> "Đã hết thời gian chờ. Đang chuẩn bị tiếp tục từ mốc đã lưu."
        else -> "Tự tiếp tục lúc ${formatBatchTime(deadline)}. Còn $remaining."
    }
    Column(Modifier.testTag("batch_retry_countdown")) { StatusNotice(message) }
}

internal fun batchRetryDeadline(batch: BatchProject, projects: List<VideoProject>): Long {
    if (batch.state == "RETRY_WAIT" && batch.autoRetryAtMs > 0) return batch.autoRetryAtMs
    val members = batch.items.mapTo(mutableSetOf()) { it.projectId }
    return projects.asSequence().filter { it.id in members }
        .map { it.pipeline.task(it.pipeline.currentStage) }
        .filter { it.status == "RETRY_WAIT" && it.nextRetryAt > 0 }
        .map { it.nextRetryAt }.minOrNull() ?: 0L
}

internal fun batchRetrySeconds(deadlineMs: Long, nowMs: Long): Long =
    if (deadlineMs <= nowMs) 0 else (deadlineMs - nowMs - 1) / 1000 + 1

@Composable
internal fun BatchRetryErrorNotice(item: BatchItem, project: VideoProject?, batch: BatchProject?, observedRunSince: Long?) {
    val task = project?.pipeline?.task(item.stage)
    val hasSavedFailure = task?.status == "NEEDS_REVIEW" && task.errorCode.isNotBlank()
    if (item.error.isBlank() && item.errorCode.isBlank() && !hasSavedFailure) return
    val guidance = batchRetryGuidance(item, project)
    Card(Modifier.fillMaxWidth().testTag("batch_error_guidance"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(guidance.title, style = MaterialTheme.typography.titleSmall)
            batchRetryFailureOrigin(guidance, observedRunSince)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(guidance.action, style = MaterialTheme.typography.bodySmall)
            if (batch != null && observedRunSince == null) {
                batchRetryManualReason(batch, item, System.currentTimeMillis())?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (guidance.unfinishedCues > 0) Text("Còn ${guidance.unfinishedCues} câu chưa hoàn tất. Các câu đã xong được giữ lại.",
                style = MaterialTheme.typography.bodySmall)
            if (guidance.code.isNotBlank()) ExpandableSection("Chi tiết lỗi") {
                Text(guidance.code, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal data class BatchRetryGuidance(val title: String, val action: String, val code: String,
    val unfinishedCues: Int, val failure: VideoApiFailureCheckpoint?)

private fun formatBatchTime(timeMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timeMs))

internal fun batchRetryFailureOrigin(guidance: BatchRetryGuidance, observedRunSince: Long?): String? {
    val failure = guidance.failure
    if (failure == null) return if (guidance.code.startsWith("API_"))
        "Lỗi đã lưu. Chưa có thời điểm hoặc mã HTTP của lần gửi." else null
    val status = failure.httpStatus?.let { " (HTTP $it)" }.orEmpty()
    val time = failure.failedAtMs.takeIf { it > 0 }?.let { " lúc ${formatBatchTime(it)}" }.orEmpty()
    return when {
        failure.fromSavedFailure -> "Tập này chưa gửi yêu cầu mới. Lỗi Gemini đã lưu của hàng đợi$status$time."
        observedRunSince != null && failure.failedAtMs >= observedRunSince -> "Lần gửi vừa thất bại$status$time."
        else -> "Lỗi đã lưu từ lần gửi gần nhất$status$time."
    }
}

private fun batchRetryManualReason(batch: BatchProject, item: BatchItem, nowMs: Long): String? {
    if (batch.state !in setOf("PAUSED", "FAILED") || !BatchRecovery.canRetry(item)) return null
    return when {
        !batch.autoRetryEnabled -> "Đã tắt tự tiếp tục. Bấm Tiếp tục khi sẵn sàng."
        BatchRecovery.exhausted(batch) -> "Đã dùng hết ${BatchRecovery.MAX_ATTEMPTS} lượt tự tiếp tục. Bấm Tiếp tục để thử lại."
        batch.retryAfterMs - nowMs > BatchRecovery.MAX_AUTOMATIC_WAIT_MS ->
            "Gemini yêu cầu chờ lâu hơn 5 phút. Bấm Tiếp tục sau thời điểm chờ."
        else -> null
    }
}

internal fun batchRetryGuidance(item: BatchItem, project: VideoProject?): BatchRetryGuidance {
    val message = item.error
    val task = project?.pipeline?.task(item.stage)
    val savedCode = item.errorCode.ifBlank { task?.errorCode.orEmpty() }
    val code = savedCode.ifBlank { messageCode(message) }
    val failure = task?.lastApiFailure?.takeIf { it.code == code }
    val responseReason = VideoResponseFailureReason.entries.firstOrNull {
        it != VideoResponseFailureReason.UNKNOWN && message.contains(it.label)
    }
    val (title, action) = when {
        code == "API_AUTH" || message.contains("chưa cấu hình khóa", true) ->
            "Cần kiểm tra kết nối Gemini" to "Mở Tùy chỉnh để kiểm tra khóa và quyền sử dụng, rồi bấm Tiếp tục."
        code == "ECONOMY_SUBMISSION_UNCERTAIN" ->
            "Chưa xác nhận được tác vụ Gemini nền" to "Kiểm tra tác vụ đã nộp trong Google AI Studio. Các bản dịch đã lưu vẫn được giữ."
        code == "API_QUOTA_EXHAUSTED" && failure?.quotaKind == "ZERO" ->
            "Hạn mức Gemini bằng 0" to "Kiểm tra hạn mức và quyền sử dụng của project Gemini. Bấm Tiếp tục sau khi project có hạn mức."
        code == "API_QUOTA_EXHAUSTED" && failure?.quotaKind == "DAILY" ->
            "Đã hết hạn mức ngày của Gemini" to "Kiểm tra thời điểm cấp lại hạn mức của project, rồi bấm Tiếp tục sau khi hạn mức được cấp lại."
        code == "API_QUOTA_EXHAUSTED" ->
            "Đã hết hạn mức Gemini" to "Kiểm tra hạn mức hoặc đợi hạn mức được cấp lại, rồi bấm Tiếp tục."
        code == "API_RATE_LIMIT" && failure?.quotaKind != "SHORT_WINDOW" ->
            "Chưa xác định loại hạn mức Gemini" to "Nếu lỗi lặp lại, kiểm tra hạn mức của project trong Google AI Studio. Có thể tiếp tục phần còn thiếu."
        code == "API_RATE_LIMIT" ->
            "Gemini đang giới hạn lượt gọi" to "Chờ hết thời gian giới hạn. Tiến độ được giữ ở mốc đã lưu."
        code == "API_NETWORK" || code == "API_TIMEOUT" ->
            "Kết nối tới Gemini bị gián đoạn" to "Kiểm tra mạng. Có thể tiếp tục từ các câu chưa hoàn tất."
        code.startsWith("API_SERVICE_") ->
            "Gemini tạm thời chưa đáp ứng" to "Đợi dịch vụ hoạt động trở lại. Các câu đã lưu được giữ nguyên."
        code == "API_HTTP_404" ->
            "Không tìm thấy dịch vụ Gemini đã chọn" to "Kiểm tra lựa chọn mô hình và quyền truy cập trong Tùy chỉnh, rồi bấm Tiếp tục."
        code == "OFFLINE_MODEL_MISSING" || message.contains("thiếu model offline", true) ->
            "Thiếu gói dịch trên máy" to "Vào Thiết lập phụ đề, chuẩn bị gói dịch rồi bấm Tiếp tục."
        code == "ASR_MODEL_MISSING" ->
            "Thiếu gói nhận diện giọng nói" to "Vào Nguồn phụ đề để tải gói tiếng Trung, rồi tiếp tục từ mốc đã lưu."
        code == "ASR_ABI_UNSUPPORTED" ->
            "Thiết bị chưa hỗ trợ nhận diện giọng nói" to "Chọn OCR hoặc phụ đề có sẵn trong Thiết lập video."
        code == "ASR_LOW_MEMORY" ->
            "Thiết bị thiếu bộ nhớ để nhận diện giọng nói" to "Đóng bớt ứng dụng rồi bấm Tiếp tục."
        code == "ASR_NO_AUDIO" || code == "ASR_UNCLEAR_AUDIO" ->
            "Âm thanh chưa có lời thoại rõ" to "Nghe lại video và chọn OCR hoặc tệp phụ đề nếu có."
        code.startsWith("ASR_AUDIO_") ->
            "Chưa đọc được âm thanh" to "Kiểm tra tệp video hoặc chọn phụ đề có sẵn trong Thiết lập video."
        code == "STORAGE_FULL" ->
            "Thiết bị thiếu chỗ trống" to "Giải phóng bộ nhớ lưu trữ rồi bấm Tiếp tục. Tiến độ đã lưu được giữ."
        code == "SOURCE_PERMISSION" ->
            "Ứng dụng chưa đọc được tệp nguồn" to "Kiểm tra quyền truy cập tệp và tệp còn ở vị trí đã chọn, rồi bấm Tiếp tục."
        code.startsWith("ROI_") || code == "OCR_NO_SUBTITLES" ->
            "Cần kiểm tra nguồn phụ đề" to "Mở Thiết lập video để kiểm tra vùng chữ hoặc chọn nguồn phụ đề khác."
        code.startsWith("FRAME_") ->
            "Đọc hình ảnh video bị gián đoạn" to "Kiểm tra video mở được và tệp đã tải đủ. Có thể tiếp tục từ mốc đã lưu."
        code == "TEXT_BLOCKED" ->
            "Gemini chưa thể xử lý nhóm câu này" to "Mở Xem và sửa phụ đề để kiểm tra các câu còn thiếu. Có thể chọn dịch trên máy cho tập này."
        code == "API_REQUEST_400" || code == "API_REQUEST_413" ->
            "Gemini chưa chấp nhận nhóm câu" to "Kiểm tra phụ đề nguồn và ngữ cảnh phim. Nếu dùng nhóm câu lớn, giảm số câu mỗi nhóm trong Thiết lập phụ đề."
        code == "TEXT_INVALID_RESPONSE" ->
            (responseReason?.label ?: "Gemini trả kết quả không đọc được") to
                "Tiến độ đã lưu. Mở Xem và sửa phụ đề để kiểm tra phần chưa xong. Lượt tiếp tục dùng nhóm câu nhỏ có giới hạn phục hồi."
        code == "TEXT_EMPTY_RESPONSE" ->
            "Gemini chưa trả bản dịch cho phần còn thiếu" to "Đã thử nhóm nhỏ hơn ba lần. Mở Xem và sửa phụ đề để kiểm tra nguồn, rồi bấm Tiếp tục khi sẵn sàng."
        code == "POLISH_IDLE_TIMEOUT" ->
            "Gemini chưa phản hồi hiệu đính" to "Các câu đã hiệu đính và bản nháp vẫn được giữ. Lượt tiếp tục chỉ gửi phần còn thiếu."
        item.stage == BatchPipelineStage.POLISH && BatchRecovery.canRetryPolishResponse(code) ->
            "Phản hồi hiệu đính chưa đầy đủ hoặc không hợp lệ" to "Tiếp tục hiệu đính theo nhóm nhỏ hơn; các câu đã hoàn thành được giữ."
        code == "TEXT_INCOMPLETE_RESPONSE" || code == "TEXT_TRUNCATED" || code == "RESPONSE_NEEDS_REVIEW" ->
            "Một số nhóm câu chưa có kết quả đầy đủ" to "Mở Xem và sửa phụ đề để xem câu còn thiếu. Bấm Tiếp tục để xử lý phần còn lại."
        item.stage == BatchPipelineStage.POLISH ->
            "Hiệu đính chưa hoàn tất" to "Kiểm tra bản nháp. Có thể tắt hiệu đính trong Thiết lập phụ đề để hoàn tất bản dịch trên máy."
        else -> "Công đoạn chưa hoàn tất" to "Mở Thiết lập video hoặc Xem và sửa phụ đề để kiểm tra phần đang dở."
    }
    val unfinished = if (project != null && item.stage == BatchPipelineStage.TRANSLATE) {
        val hybrid = project.translationMode == VideoTranslationMode.HYBRID
        project.cues.count { !VideoTranslationPlan.isComplete(it, hybrid) }
    } else 0
    return BatchRetryGuidance(title, action, safeErrorCode(code), unfinished, failure)
}

private val batchErrorCodePattern = Regex("(?:API_(?:AUTH|NETWORK|TIMEOUT|RATE_LIMIT|QUOTA_EXHAUSTED|(?:REQUEST|SERVICE|HTTP)_[1-5][0-9]{2})|" +
    "(?:ASR|FRAME|OCR|ROI|TEXT|POLISH|RESPONSE|STAGE|SOURCE|STORAGE|OFFLINE|EXPORT|ECONOMY)_[A-Z0-9_]{2,43})")

private fun safeErrorCode(value: String): String = value.takeIf(batchErrorCodePattern::matches).orEmpty()

private fun messageCode(message: String): String = safeErrorCode(message.trimStart().removePrefix("[")
    .takeWhile { it in 'A'..'Z' || it in '0'..'9' || it == '_' })

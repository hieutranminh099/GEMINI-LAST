package com.gemini.ocr.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gemini.ocr.ui.ExpandableSection
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.platform.LocalContext
import com.gemini.ocr.video.VideoChoiceRow
import com.gemini.ocr.video.VideoTranslationClient

@Composable
fun GeminiApiKeySettingsPanel(settings: AppSettings, enabled: Boolean, onChanged: () -> Unit = {}) {
    var mode by remember { mutableStateOf(settings.geminiKeyMode) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf(settings.videoTextModel) }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    VideoChoiceRow("Model dịch lời thoại video", model, AppSettings.VIDEO_MODELS.map { it to it },
        enabled && !checking, { model = it; settings.videoTextModel = it; status = "" }, "video_text_model")
    OutlinedButton(enabled = enabled && !checking && settings.hasGeminiVideoKey(), onClick = {
        checking = true
        scope.launch {
            try { VideoTranslationClient(context).checkModel(); status = "Model hỗ trợ tạo nội dung với cấu hình đã chọn." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "Chưa kết nối được Gemini." }
            finally { checking = false }
        }
    }) { Text("Kiểm tra kết nối Gemini") }
    if (status.isNotBlank()) Text(status)
    ExpandableSection("3 khóa Gemini cho video", "Dịch video và dịch hàng loạt") {
        Text("Khóa chính ở trên cũng dùng cho dịch trực tiếp. Bạn có thể thêm hai khóa để phân phối các lượt dịch video.",
            style = MaterialTheme.typography.bodySmall)
        GeminiKeyMode.entries.forEach { choice ->
            TextButton(onClick = {
                settings.geminiKeyMode = choice
                mode = choice
                onChanged()
            }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("gemini_key_mode_${choice.name}")) {
                RadioButton(selected = mode == choice, onClick = null, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Text(choice.label, Modifier.weight(1f))
            }
        }
        Text(when (mode) {
            GeminiKeyMode.PRIMARY_ONLY -> "Video dùng khóa chính."
            GeminiKeyMode.FAILOVER -> "Ưu tiên khóa chính, chuyển sang khóa còn sẵn sàng khi cần."
            GeminiKeyMode.BALANCED -> "Phân phối các yêu cầu video qua những khóa đang sẵn sàng. Kết hợp với Dịch đồng thời để xử lý nhiều video."
        }, style = MaterialTheme.typography.bodySmall)
        SecondaryGeminiKey(settings, 2, enabled, onChanged)
        SecondaryGeminiKey(settings, 3, enabled, onChanged)
        Text("Các khóa thuộc cùng một dự án Gemini dùng chung hạn mức của dự án đó.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SecondaryGeminiKey(settings: AppSettings, slot: Int, enabled: Boolean, onChanged: () -> Unit) {
    var input by remember { mutableStateOf(settings.secondaryGeminiApiKey(slot)) }
    var status by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    OutlinedTextField(input, {
        input = it
        status = ""
    }, label = { Text("Khóa Gemini API $slot") }, visualTransformation = PasswordVisualTransformation(),
        singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("gemini_api_key_$slot"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            try {
                settings.setSecondaryGeminiApiKey(slot, input)
                input = input.trim()
                onChanged()
                failed = false
                status = if (input.isBlank()) "Đã xóa khóa $slot." else "Đã lưu khóa $slot trên thiết bị."
            } catch (_: Exception) {
                failed = true
                status = "Chưa lưu được khóa $slot vào bộ nhớ bảo mật. Hãy thử lại."
            }
        }, enabled = enabled, modifier = Modifier.testTag("save_gemini_api_key_$slot")) { Text("Lưu khóa $slot") }
        TextButton(onClick = {
            settings.setSecondaryGeminiApiKey(slot, "")
            input = ""
            onChanged()
            failed = false
            status = "Đã xóa khóa $slot."
        }, enabled = enabled && input.isNotBlank(), modifier = Modifier.testTag("clear_gemini_api_key_$slot")) {
            Text("Xóa")
        }
    }
    if (status.isNotBlank()) Text(status,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall)
}

package com.gemini.ocr.video

/** Local limits for staged requests. The provider's actual token count depends on its tokenizer. */
internal object VideoBatchRequestBudget {
    const val MAX_INPUT_TOKENS = 8192
    const val MIN_OUTPUT_TOKENS = 1024
    const val MAX_OUTPUT_TOKENS = 8192
    const val MAX_DIALOGUE_NEIGHBORS = 12

    fun estimateInputTokens(payload: String): Int {
        val ascii = payload.count { it.code < 128 }
        // Include the whole encoded request, including context, glossary and response schema.
        // Count non-ASCII UTF-16 units individually so CJK and emoji get more room than ASCII.
        return (ascii + 2) / 3 + (payload.length - ascii)
    }

    fun fits(batch: VideoTranslationPlan.Batch, payload: String): Boolean =
        batch.maxOutputTokens <= MAX_OUTPUT_TOKENS && estimateInputTokens(payload) <= MAX_INPUT_TOKENS

    fun fits(batch: VideoTranslationPlan.Batch, buildPayload: () -> String): Boolean =
        try { fits(batch, buildPayload()) } catch (_: IllegalArgumentException) { false }

    /** Rebuild a shorter chronological target prefix with its own adjacent context and IDs. */
    fun fit(
        batch: VideoTranslationPlan.Batch,
        rebuild: (Int) -> VideoTranslationPlan.Batch,
        fitsRequest: (VideoTranslationPlan.Batch) -> Boolean
    ): VideoTranslationPlan.Batch {
        if (fitsRequest(batch)) return batch
        var count = batch.cueIds.size
        while (count > 1) {
            count /= 2
            val candidate = rebuild(count)
            if (fitsRequest(candidate)) return candidate
        }
        throw exceeded()
    }

    fun exceeded() = VideoApiException("TEXT_REQUEST_BUDGET",
        detail = "Một câu cùng ngữ cảnh hoặc từ điển vượt ngân sách gửi Gemini. Đã giữ nguồn và bản dịch đã lưu. Kiểm tra câu hoặc ngữ cảnh rồi bấm Tiếp tục.")
}

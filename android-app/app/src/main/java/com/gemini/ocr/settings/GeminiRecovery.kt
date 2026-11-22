package com.gemini.ocr.settings

import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Version 1 fixes the KDF and AEAD parameters so that untrusted manifests cannot set their cost. */
internal object GeminiRecovery {
    private val random = SecureRandom()
    private val aad = "GeminiOCR/gemini-settings/1".toByteArray()
    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray): Cipher {
        require(salt.size == 16 && iv.size == 12 && password.size in 12..256) {
            "Nhập mật khẩu khôi phục từ 12 đến 256 ký tự."
        }
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                updateAAD(aad)
            }
        } finally { key.fill(0) }
    }

    fun seal(plain: ByteArray, password: CharArray): JSONObject {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        require(plain.size in 1..65536)
        val data = cipher(Cipher.ENCRYPT_MODE, password, salt, iv).doFinal(plain)
        val encoder = Base64.getEncoder()
        return JSONObject().put("version", 1).put("salt", encoder.encodeToString(salt))
            .put("iv", encoder.encodeToString(iv)).put("data", encoder.encodeToString(data))
    }

    fun open(envelope: JSONObject, password: CharArray): ByteArray {
        require(envelope.getInt("version") == 1) { "Phiên bản sao lưu Gemini chưa được hỗ trợ." }
        val decoder = Base64.getDecoder()
        require(envelope.getString("data").length <= 90000)
        return try {
            cipher(Cipher.DECRYPT_MODE, password, decoder.decode(envelope.getString("salt")),
                decoder.decode(envelope.getString("iv"))).doFinal(decoder.decode(envelope.getString("data")))
        } catch (_: GeneralSecurityException) {
            throw IllegalArgumentException("Không mở được bản sao Gemini. Kiểm tra mật khẩu khôi phục hoặc bản sao.")
        }
    }
}

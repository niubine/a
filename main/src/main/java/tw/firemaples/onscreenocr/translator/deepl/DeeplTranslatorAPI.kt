package tw.firemaples.onscreenocr.translator.deepl

import tw.firemaples.onscreenocr.utils.Logger
import tw.firemaples.onscreenocr.utils.Utils

object DeeplTranslatorAPI {
    private const val LANGUAGE_MODEL = "next-gen"
    private const val USAGE_TYPE = "Ocr"

    private val logger: Logger by lazy { Logger(this::class) }
    private val apiService: DeeplAPIService by lazy {
        Utils.retrofit.create(DeeplAPIService::class.java)
    }

    suspend fun translate(text: String, sourceLang: String?, targetLang: String): Result<String> {
        logger.debug("Start translate, text: $text, source: $sourceLang, target: $targetLang")

        val response = apiService.translate(
            DeeplTranslateRequest(
                text = listOf(text),
                sourceLang = sourceLang,
                targetLang = targetLang,
                languageModel = LANGUAGE_MODEL,
                usageType = USAGE_TYPE,
            )
        )

        if (!response.isSuccessful) {
            return Result.failure(
                IllegalStateException(
                    "API failed(${response.code()}): ${response.errorBody()?.toString()}"
                )
            )
        }

        val translatedTexts = response.body()
            ?.translations
            ?.mapNotNull { it.text?.takeIf(String::isNotBlank) }
            ?: return Result.failure(IllegalStateException("Got empty translation result"))

        if (translatedTexts.isEmpty()) {
            return Result.failure(IllegalStateException("Got empty translation result"))
        }

        val translatedText = if (translatedTexts.size == 1) {
            translatedTexts.first()
        } else {
            translatedTexts.joinToString(separator = "\n")
        }

        logger.debug("Got translation result: $translatedText")
        return Result.success(translatedText)
    }

    suspend fun translate(
        text: List<String>,
        sourceLang: String?,
        targetLang: String,
    ): Result<List<String>> {
        if (text.isEmpty()) {
            return Result.success(emptyList())
        }

        logger.debug(
            "Start translate, textCount: ${text.size}, source: $sourceLang, target: $targetLang"
        )

        val response = apiService.translate(
            DeeplTranslateRequest(
                text = text,
                sourceLang = sourceLang,
                targetLang = targetLang,
                languageModel = LANGUAGE_MODEL,
                usageType = USAGE_TYPE,
            )
        )

        if (!response.isSuccessful) {
            return Result.failure(
                IllegalStateException(
                    "API failed(${response.code()}): ${response.errorBody()?.toString()}"
                )
            )
        }

        val translations = response.body()?.translations
            ?: return Result.failure(IllegalStateException("Got empty translation result"))

        if (translations.size != text.size) {
            return Result.failure(IllegalStateException("Got unexpected translation size"))
        }

        val translatedTexts = translations.map { it.text ?: "" }

        logger.debug("Got translation result: ${translatedTexts.size}")
        return Result.success(translatedTexts)
    }
}

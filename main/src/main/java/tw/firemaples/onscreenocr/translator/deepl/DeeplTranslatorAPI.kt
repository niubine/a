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

        val translatedText = response.body()
            ?.translations
            ?.firstOrNull()
            ?.text
            ?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("Got empty translation result"))

        logger.debug("Got translation result: $translatedText")
        return Result.success(translatedText)
    }
}

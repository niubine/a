package tw.firemaples.onscreenocr.translator.deepl

import tw.firemaples.onscreenocr.R
import tw.firemaples.onscreenocr.pref.AppPref
import tw.firemaples.onscreenocr.translator.TranslationLanguage
import tw.firemaples.onscreenocr.translator.TranslationProviderType
import tw.firemaples.onscreenocr.translator.TranslationResult
import tw.firemaples.onscreenocr.translator.Translator
import tw.firemaples.onscreenocr.utils.Logger
import tw.firemaples.onscreenocr.utils.firstPart

object DeeplTranslator : Translator {
    private val logger: Logger by lazy { Logger(this::class) }

    override val type: TranslationProviderType
        get() = TranslationProviderType.Deepl

    override val defaultLanguage: String
        get() = "en"

    override suspend fun supportedLanguages(): List<TranslationLanguage> {
        val langCodeList =
            context.resources.getStringArray(R.array.deepl_translationLangCode)
        val langNameList = context.resources.getStringArray(R.array.deepl_translationLangName)

        val selectedLangCode = selectedLangCode(langCodeList)

        return (langCodeList.indices).map { i ->
            val code = langCodeList[i]
            val name = langNameList[i]

            TranslationLanguage(
                code = code,
                displayName = name,
                selected = code == selectedLangCode
            )
        }
    }

    override suspend fun translate(text: String, sourceLangCode: String): TranslationResult {
        if (!isLangSupport()) {
            return TranslationResult.SourceLangNotSupport(type)
        }

        if (text.isBlank()) {
            return TranslationResult.TranslatedResult(result = "", type)
        }

        val targetLangCode = supportedLanguages().firstOrNull { it.selected }?.code
            ?: return TranslationResult.TranslationFailed(
                IllegalArgumentException("The selected translation language is not found")
            )

        if (AppPref.selectedOCRLang.firstPart()
                .equals(targetLangCode.firstPart(), ignoreCase = true)
        ) {
            return TranslationResult.TranslatedResult(result = text, type)
        }

        return doTranslate(
            text = text,
            sourceLangCode = sourceLangCode,
            targetLangCode = targetLangCode,
        )
    }

    private suspend fun doTranslate(
        text: String,
        sourceLangCode: String,
        targetLangCode: String,
    ): TranslationResult {
        val sourceLang = toDeeplLang(sourceLangCode).takeUnless { it.isNullOrBlank() }
        val targetLang = toDeeplLang(targetLangCode) ?: targetLangCode

        DeeplTranslatorAPI.translate(
            text = text,
            sourceLang = sourceLang,
            targetLang = targetLang,
        ).onSuccess {
            return TranslationResult.TranslatedResult(it, type)
        }.onFailure {
            logger.warn(t = it)
            return TranslationResult.TranslationFailed(it)
        }

        return TranslationResult.TranslationFailed(IllegalStateException("Illegal state"))
    }

    internal fun toDeeplLang(code: String?): String? {
        if (code.isNullOrBlank()) {
            return null
        }

        val normalized = code.uppercase()
        return LANG_MAP[normalized] ?: code
    }

    private val LANG_MAP = mapOf(
        "ZH" to "zh-Hans",
        "ZH-CN" to "zh-Hans",
        "ZH-TW" to "zh-Hant",
        "ZH-HANS" to "zh-Hans",
        "ZH-HANT" to "zh-Hant",
        "EN" to "en-US",
        "EN-US" to "en-US",
        "EN-GB" to "en-GB",
        "PT" to "pt-PT",
        "PT-BR" to "pt-BR",
        "JA" to "ja",
        "KO" to "ko",
        "FR" to "fr",
        "DE" to "de",
        "ES" to "es",
        "RU" to "ru",
        "IT" to "it",
        "NL" to "nl",
        "PL" to "pl",
        "AR" to "ar",
        "TR" to "tr",
    )
}

package tw.firemaples.onscreenocr.pages.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import tw.firemaples.onscreenocr.R
import tw.firemaples.onscreenocr.databinding.ActivitySettingBinding
import tw.firemaples.onscreenocr.floatings.translationSelectPanel.TranslationSelectPanel
import tw.firemaples.onscreenocr.pref.AppPref
import tw.firemaples.onscreenocr.translator.TranslationProviderType
import tw.firemaples.onscreenocr.utils.fitCutoutInsets

class SettingActivity : AppCompatActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingActivity::class.java).apply {
                flags =
                    flags or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
            })
        }
    }

    private lateinit var binding: ActivitySettingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.fitCutoutInsets()
        binding.languageSwitchBar.setOnClickListener {
            TranslationSelectPanel(this).attachToScreen()
        }
        updateLanguageSwitchBar()
    }

    override fun onResume() {
        super.onResume()
        updateLanguageSwitchBar()
    }

    private fun updateLanguageSwitchBar() {
        val providerType = TranslationProviderType.fromKey(AppPref.selectedTranslationProvider)
        val ocrLang = AppPref.selectedOCRLang
        val translationLang = AppPref.selectedTranslationLang

        val iconRes = when (providerType) {
            TranslationProviderType.GoogleTranslateApp -> R.drawable.ic_google_translate_dark_grey
            TranslationProviderType.BingTranslateApp -> R.drawable.ic_microsoft_bing
            TranslationProviderType.OtherTranslateApp -> R.drawable.ic_open_in_app
            else -> null
        }

        val text = when (providerType) {
            TranslationProviderType.GoogleTranslateApp,
            TranslationProviderType.BingTranslateApp,
            TranslationProviderType.OtherTranslateApp -> "$ocrLang>"
            TranslationProviderType.YandexTranslateApp -> "$ocrLang > Y"
            TranslationProviderType.PapagoTranslateApp -> "$ocrLang > P"
            TranslationProviderType.OCROnly -> " $ocrLang "
            TranslationProviderType.MicrosoftAzure,
            TranslationProviderType.GoogleMLKit,
            TranslationProviderType.Deepl,
            TranslationProviderType.MyMemory -> "$ocrLang>$translationLang"
        }

        binding.tvLanguageSwitchText.text = text
        if (iconRes != null) {
            binding.ivLanguageSwitchProvider.visibility = View.VISIBLE
            binding.ivLanguageSwitchProvider.setImageResource(iconRes)
        } else {
            binding.ivLanguageSwitchProvider.visibility = View.GONE
        }
    }
}

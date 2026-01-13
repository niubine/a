package tw.firemaples.onscreenocr.translator.deepl

import androidx.annotation.Keep
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface DeeplAPIService {
    @Headers(
        "Content-Type: application/json",
        "Accept: */*",
        "x-app-os-version: 18.5.0",
        "x-app-device: iPhone11,8",
        "User-Agent: ktor-client",
        "x-app-build: 3065622",
        "x-app-version: 25.37",
        "Accept-Language: zh-Hans",
    )
    @POST("https://oneshot-free.www.deepl.com/v1/translate")
    suspend fun translate(
        @Body body: DeeplTranslateRequest,
    ): Response<DeeplTranslateResponse>
}

@JsonClass(generateAdapter = true)
@Keep
data class DeeplTranslateRequest(
    @Json(name = "text")
    val text: String,
    @Json(name = "source_lang")
    val sourceLang: String?,
    @Json(name = "target_lang")
    val targetLang: String,
    @Json(name = "language_model")
    val languageModel: String,
    @Json(name = "usage_type")
    val usageType: String,
)

@JsonClass(generateAdapter = true)
@Keep
data class DeeplTranslateResponse(
    @Json(name = "translations")
    val translations: List<DeeplTranslation>?,
)

@JsonClass(generateAdapter = true)
@Keep
data class DeeplTranslation(
    @Json(name = "text")
    val text: String?,
)

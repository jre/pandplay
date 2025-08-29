package net.joshe.pandplay.remote

import android.annotation.SuppressLint
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

fun String.unhexify(): ByteArray {
    check(length % 2 == 0)
    return ByteArray(length / 2) { idx ->
        Integer.parseInt(substring(idx * 2, idx * 2 + 2), 16).toByte()
    }
}

private const val hexchars = "0123456789abcdef"
fun ByteArray.hexify(): String {
    return String((0 until size * 2).map {
            idx -> hexchars[this[idx/2].toInt().shr(if (idx % 2 == 0) 4 else 0) and 0xf]
    }.toCharArray())
}

private object KtorAndroidLogger : Logger {
    override fun log(message: String) {
        Log.d("HTTP", message)
    }
}

class JsonAPI(
    hostname: String,
    inputKey: ByteArray,
    outputKey: ByteArray,
    partnerUser: String,
    partnerPassword: String,
    deviceModel: String,
    private val loginUsername: String,
    private val loginPassword: String
) {
    private val url = "https://${hostname}/services/json/"
    @SuppressLint("GetInstance")
    private val inCypher = Cipher.getInstance("Blowfish/ECB/PKCS5Padding")
    @SuppressLint("GetInstance")
    private val outCypher = Cipher.getInstance("Blowfish/ECB/PKCS5Padding")
    init {
        inCypher.init(Cipher.DECRYPT_MODE, SecretKeySpec(inputKey, "Blowfish"))
        outCypher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(outputKey, "Blowfish"))
    }
    private val partnerLoginReq: APIRequest = PartnerLoginRequest(
        username=partnerUser,
        password=partnerPassword,
        deviceModel=deviceModel,
        version="5")

    private val wrapperDecoder = Json { classDiscriminator = APIResponseWrapperDiscriminator }
    private val resultDecoder = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "#method"
    }
    var syncTime: Long = 0
        get() = (System.currentTimeMillis() / 1000) - field
        set(secs) { field = (System.currentTimeMillis() / 1000) - secs }
    val paramData: MutableMap<APIParam, String> = mutableMapOf()

    private val client = HttpClient {
        expectSuccess = true
        install(Logging) {
            logger = KtorAndroidLogger
            level = LogLevel.ALL
        }
    }

    fun encrypt(data: String): String =
        outCypher.doFinal(data.encodeToByteArray()).hexify()

    fun decrypt(data: String): ByteArray =
        inCypher.doFinal(data.unhexify())

    suspend fun request(req: APIRequest, canRetry: Boolean = true): APIResponse {
        val rawbody = req.encodeJSON()
        val body = if (req.encrypted) encrypt(rawbody) else rawbody
        if (req.encrypted)
            Log.v("API", "body before encryption: $rawbody")

        val resp: HttpResponse = client.post(url) {
            url { req.appendQueryParams(parameters, paramData) }
            headers {
                append(HttpHeaders.ContentType, "text/plain")
                append(HttpHeaders.Connection, "Keep-Alive")
            }
            setBody(body)
        }
        val wrapper: APIResponseWrapper = wrapperDecoder.decodeFromString(resp.body())
        Log.v("API", "wrapper $wrapper")
        if (wrapper is APIResponseWrapperError) {
            if (canRetry && (
                        // XXX I think this was a one-time thing and retrying didn't help.
                        //wrapper.code == ApiError.InternalServerError.code ||
                        wrapper.code == ApiError.InvalidAuthToken.code)) {
                Log.v("API", "recieved $wrapper, attempting to log back in")
                login()
                if (req is APIAuthenticatedRequest) {
                    req.syncTime = syncTime
                    req.userAuthToken = paramData.getValue(APIParam.UserAuthToken)
                }
                Log.v("API", "successfully logged back in, retrying request")
                return request(req, canRetry = false)
            }
            throw wrapper.exception()
        }
        val result = (wrapper as APIResponseWrapperOk).result.toMutableMap()
        result[resultDecoder.configuration.classDiscriminator] = JsonPrimitive(req.method)
        return resultDecoder.decodeFromJsonElement(JsonObject(result))
    }

    suspend fun download(url: String, file: File, retryCount: Int = 0) : Boolean {
        require(url.isNotEmpty())
        if (retryCount > 0)
            Log.v("API", "retrying download $retryCount of 2 times")
        val client = HttpClient {
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                exponentialDelay()
            }
            install(Logging) {
                logger = KtorAndroidLogger
                level = LogLevel.INFO
            }
        }
        try {
            val result = client.get(url)
            if (result.status.isSuccess())
                result.bodyAsChannel().copyAndClose(file.writeChannel())
            return result.status.isSuccess()
        } catch (_: HttpRequestTimeoutException) {
            if (retryCount < 3)
                return download(url, file, retryCount + 1)
            return false
        }
    }

    suspend fun login() {
        val partner: PartnerLoginResponse = request(partnerLoginReq, canRetry = false) as PartnerLoginResponse
        paramData[APIParam.PartnerAuthToken] = partner.partnerAuthToken
        paramData[APIParam.PartnerId] = partner.partnerId.toString()
        syncTime = decrypt(partner.syncTime).decodeToString(4).toLong()

        val userLoginReq = UserLoginRequest(
            loginType = "user",
            username = loginUsername,
            password = loginPassword,
            partnerAuthToken = partner.partnerAuthToken,
            syncTime = syncTime
        )
        val user: UserLoginResponse = request(userLoginReq, canRetry = false) as UserLoginResponse
        paramData[APIParam.UserAuthToken] = user.userAuthToken
        paramData[APIParam.UserId] = user.userId.toString()
    }
}

// the message property will be used when I get around to writing proper error handling and reporting
enum class ApiError(val code: Long, @Suppress("unused") val message: String) {
    // XXX InternalServerError(0, "Internal Server Error"),
    InvalidAuthToken(1001, "Invalid Auth Token"),
}

private val apiErrors: Map<Long, String> = mapOf(
    0L to "Internal Server Error",
    1L to "Maintenance Mode",
    2L to "Missing API Method",
    3L to "Missing Auth Token",
    4L to "Missing Partner ID",
    5L to "Missing User ID",
    6L to "Secure Protocol Required",
    7L to "Certificate Required",
    8L to "Parameter Type Mismatch",
    9L to "Parameter Missing",
    10L to "Parameter Value Invalid",
    11L to "API Version Not Supported",
    12L to "Pandora not available in this country",
    13L to "Bad Sync Time",
    14L to "Unknown Method Name",
    15L to "Wrong Protocol - (http/https)",
    1000L to "Read Only Mode",
    1001L to "Invalid Auth Token",
    1002L to "Invalid Partner Login",
    1003L to "Listener Not Authorized - Subscription or Trial Expired",
    1004L to "User Not Authorized",
    1005L to "Station limit reached",
    1006L to "Station does not exist",
    1009L to "Device Not Found",
    1010L to "Partner Not Authorized",
    1011L to "Invalid Username",
    1012L to "Invalid Password",
    1023L to "Device Model Invalid",
    1039L to "Too many requests for a new playlist",
    9999L to "Authentication Required",
)


enum class APIParam {
    PartnerAuthToken { override fun toQueryParam() = "auth_token" },
    PartnerId { override fun toQueryParam() =  "partner_id" },
    UserAuthToken { override fun toQueryParam() =  "auth_token" },
    UserId { override fun toQueryParam() =  "user_id" };
    abstract fun toQueryParam(): String
}

@Serializable
sealed class APIRequest(
    @Transient val method: String = "",
    @Transient val params: Set<APIParam> = emptySet(),
    @Transient val encrypted: Boolean = true,
) {
    fun encodeJSON() : String {
        val data = Json.encodeToJsonElement(this)
        return Json.encodeToString(data.jsonObject.filterNot {it.key == "type"})
    }

    fun appendQueryParams(builder: ParametersBuilder, data: Map<APIParam, String>) {
        builder.append("method", method)
        for (p in params)
            builder.append(p.toQueryParam(), data.getValue(p))
    }
}

private const val APIResponseWrapperDiscriminator = "stat"

@Serializable
sealed class APIResponseWrapper

@Serializable
@SerialName("ok")
data class APIResponseWrapperOk(val result: JsonObject) : APIResponseWrapper()

@Serializable
@SerialName("fail")
data class APIResponseWrapperError(val message: String, val code: Long) : APIResponseWrapper() {
    fun exception() : Exception = SerializationException(if (code in apiErrors) "${message}: ${apiErrors[code]}" else message)
}

@Serializable
sealed class APIResponse

@Serializable
data class PartnerLoginRequest(
    val deviceModel: String,
    val password: String,
    val username: String,
    val version: String,
) : APIRequest(method = "auth.partnerLogin", encrypted = false)

@Serializable
@SerialName("auth.partnerLogin")
data class PartnerLoginResponse(
    val partnerAuthToken: String,
    val partnerId: Long,
    val syncTime: String,
) : APIResponse()

@Serializable
data class UserLoginRequest(
    val loginType: String,
    val partnerAuthToken: String,
    val password: String,
    val syncTime: Long,
    val username: String,
) : APIRequest(
    method = "auth.userLogin",
    params = setOf(APIParam.PartnerAuthToken, APIParam.PartnerId))

@Serializable
@SerialName("auth.userLogin")
data class UserLoginResponse(
    val userAuthToken: String,
    val userId: Long,
    val userProfileUrl: String,
    val username: String,
) : APIResponse()

interface APIAuthenticatedRequest {
    var syncTime: Long
    var userAuthToken: String
}

@Serializable
class GetStationListChecksumRequest(
    override var syncTime: Long,
    override var userAuthToken: String
) : APIAuthenticatedRequest, APIRequest(
    method = "user.getStationListChecksum",
    params = setOf(APIParam.UserAuthToken, APIParam.UserId, APIParam.PartnerId))

@Serializable
@SerialName("user.getStationListChecksum")
data class GetStationListChecksumResponse(val checksum: String) : APIResponse()

@Serializable
class GetStationListRequest(
    @Suppress("unused") // I don't understand why kotlin thinks this isn't used
    val includeStationArtUrl: Boolean,
    // XXX stationArtSize
    // XXX includeStationSeeds
    // XXX includeRecommendations
    // XXX includeExplanations
    // XXX includeExtras
    override var syncTime: Long,
    override var userAuthToken: String
) : APIAuthenticatedRequest, APIRequest(
    method = "user.getStationList",
    params = setOf(APIParam.UserAuthToken, APIParam.UserId, APIParam.PartnerId))

// XXX user.getStation request and response

@Serializable
@SerialName("user.getStationList")
data class GetStationListResponse(
    val checksum: String,
    val stations: List<Station>,
) : APIResponse() {
    @Serializable
    data class Station(
        val allowAddMusic: Boolean,
        val allowDelete: Boolean,
        val allowRename: Boolean,
        val artUrl: String = "",
        val isGenreStation: Boolean,
        val isQuickMix: Boolean,
        val stationDetailUrl: String,
        val stationId: String,
        val stationName: String,
        val stationToken: String,
    )
}

@Serializable
class GetPlaylistRequest(
    @Suppress("unused") // I don't understand why kotlin thinks this isn't used
    val stationToken: String,
    @Suppress("unused") // I don't understand why kotlin thinks this isn't used
    val additionalAudioUrl: String,
    // val includeTrackLength: Boolean = false,
    // val includeTrackOptions: Boolean = false,
    // val includeAudioToken: Boolean = false,
    override var syncTime: Long,
    override var userAuthToken: String
) : APIAuthenticatedRequest, APIRequest(
    method = "station.getPlaylist",
    params = setOf(APIParam.UserAuthToken, APIParam.UserId, APIParam.PartnerId))

@Serializable
@SerialName("station.getPlaylist")
data class GetPlaylistResponse(
    val items: List<Item>
) : APIResponse() {
    @Serializable(with = PlaylistItemSerializer::class)
    abstract class Item

    @Serializable
    data class Song(
        @Serializable(with = SloppyStringListSerializer::class)
        val additionalAudioUrl: List<String>,
        // XXX additionalAudioToken (sloppy string list, optional)
        val albumArtUrl: String,
        val albumDetailUrl: String,
        val albumExplorerUrl: String,
        val albumIdentity: String,
        val albumName: String,
        val allowFeedback: Boolean,
        val artistDetailUrl: String,
        val artistExplorerUrl: String,
        val artistName: String,
        val audioUrlMap: Map<String, AudioUrl>,
        // XXX isFeatured
        // XXX musicId
        // XXX pandoraType
        // XXX pandoraId
        // XXX shareLandingUrl
        val songDetailUrl: String,
        val songExplorerUrl: String,
        val songIdentity: String,
        val songName: String,
        val songRating: Long,
        val stationId: String,
        val trackGain: String,
        // XXX trackLength (int, optional)
        val trackToken: String,
        val userSeed: String,
    ) : Item()

    @Serializable
    data class AudioUrl(
        // XXX audioToken (optional)
        val audioUrl: String,
        val bitrate: String,
        val encoding: String,
        val protocol: String,
    )

    @Serializable
    data class Ad(val adToken: String) : Item()
}

object PlaylistItemSerializer : JsonContentPolymorphicSerializer<GetPlaylistResponse.Item>(GetPlaylistResponse.Item::class) {
    override fun selectDeserializer(element: JsonElement) = when {
        "adToken" in element.jsonObject -> GetPlaylistResponse.Ad.serializer()
        else -> GetPlaylistResponse.Song.serializer()
    }
}

object SloppyStringListSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        element as? JsonArray ?: JsonArray(listOf(element))
}

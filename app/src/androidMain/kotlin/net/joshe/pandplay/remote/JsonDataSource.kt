package net.joshe.pandplay.remote

import android.content.Context
import net.joshe.pandplay.PrefKey
import net.joshe.pandplay.R
import net.joshe.pandplay.db.StationToken
import java.io.File

class JsonDataSource {
    private var _api: JsonAPI? = null
    private val api get() = _api!!
    private val codec = "HTTP_64_AACPLUS"

    companion object {
        val serviceName = Pair("Panda", "roar").let { (panda, roar) ->
            panda.substring(0..3) + roar[1] + roar[3] + roar[2] }
    }

    private fun loadApi(context: Context, testOnly: Boolean = false) : Pair<Boolean, String> {
        val host = PrefKey.J_API_HOST.getStringWithDefault(context)
        val partnerUser = PrefKey.J_API_PARTNER_USER.getStringWithDefault(context)
        val partnerPass = PrefKey.J_API_PARTNER_PASS.getStringWithDefault(context)
        val dev = PrefKey.J_API_DEVICE.getStringWithDefault(context)
        val inKey = PrefKey.J_API_IN_KEY.getStringWithDefault(context)
        val outKey = PrefKey.J_API_OUT_KEY.getStringWithDefault(context)
        val user = PrefKey.J_API_USER.getStringWithDefault(context)
        val pass = PrefKey.J_API_PASS.getStringWithDefault(context)

        if (host.isNullOrEmpty() || partnerUser.isNullOrEmpty() || partnerPass.isNullOrEmpty() ||
            dev.isNullOrEmpty() || inKey.isNullOrEmpty() || outKey.isNullOrEmpty())
            return Pair(false, context.getString(R.string.err_missing_partner))
        else if (user.isNullOrEmpty() || pass.isNullOrEmpty())
            return Pair(false, context.getString(R.string.err_missing_auth))
        else if (!testOnly)
            _api = JsonAPI(
                hostname = host,
                partnerUser = partnerUser,
                partnerPassword = partnerPass,
                deviceModel = dev,
                inputKey = inKey.toByteArray(),
                outputKey = outKey.toByteArray(),
                loginUsername = user,
                loginPassword = pass)
        return Pair(true, "")
    }

    fun haveCompleteCredentials(context: Context) = loadApi(context, testOnly = true).first

    fun haveValidCredentials(context: Context)
            = PrefKey.J_API_LOGGED_IN.getBoolWithDefault(context) && loadApi(context, testOnly = true).first

    suspend fun login(context: Context) : Pair<Boolean, String> {
        var result = loadApi(context)
        if (result.first)
            result = try {
                api.login()
                Pair(true, "")
            } catch (e: Throwable) {
                Pair(false, e.toString())
            }
        PrefKey.J_API_LOGGED_IN.saveAny(context, result.first)
        return result
    }

    suspend fun ensureLogin(context: Context) : Pair<Boolean, String> {
        return if (_api != null)
            Pair(true, "")
        else
            login(context)
    }

    suspend fun fetchStationListHash() : String {
        val req: APIRequest = GetStationListChecksumRequest(
            userAuthToken = api.paramData.getValue(APIParam.UserAuthToken),
            syncTime = api.syncTime)
        val resp = api.request(req) as GetStationListChecksumResponse
        return resp.checksum
    }

    suspend fun fetchStationList() : Pair<String, List<GetStationListResponse.Station>> {
        val req: APIRequest = GetStationListRequest(
            userAuthToken = api.paramData.getValue(APIParam.UserAuthToken),
            syncTime = api.syncTime,
            includeStationArtUrl = true)
        val resp = api.request(req) as GetStationListResponse
        return Pair(resp.checksum, resp.stations)
    }

    suspend fun fetchSongMetadata(stationToken: StationToken) : List<GetPlaylistResponse.Song> {
        val req = GetPlaylistRequest(
            userAuthToken = api.paramData.getValue(APIParam.UserAuthToken),
            syncTime = api.syncTime,
            stationToken = stationToken.stationToken,
            additionalAudioUrl = codec)
        val resp = api.request(req) as GetPlaylistResponse
        return resp.items.filterIsInstance<GetPlaylistResponse.Song>()
    }

    suspend fun fetchMedia(url: String, file: File) = api.download(url, file)
}

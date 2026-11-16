package com.gathin.tvhome.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

internal class IptvRepository(context: Context) {
    private val appContext = context.applicationContext

    fun loadSources(): List<IptvSource> = IptvStorage.load(appContext)

    fun addM3uUrl(name: String, url: String): IptvSource {
        val sourceUrl = validateHttpUrl(url)
        val source = IptvSource(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "M3U playlist" },
            type = IptvSourceType.M3U_URL,
            url = sourceUrl,
        )
        addSource(source)
        return source
    }

    fun addLocalM3u(name: String, fileName: String, content: String): IptvSource {
        require(content.isNotBlank()) { "The selected file is empty." }
        require(content.length <= MAX_PLAYLIST_CHARS) { "Playlist file is too large." }
        val source = IptvSource(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { fileName.substringBeforeLast('.', fileName).ifBlank { "Local M3U" } },
            type = IptvSourceType.M3U_FILE,
            localContent = content,
        )
        val parsed = IptvM3uParser.parse(content, source)
        addSource(source.copy(cachedChannels = parsed, lastSyncedAt = System.currentTimeMillis()))
        return source
    }

    fun addXtream(name: String, server: String, username: String, password: String): IptvSource {
        val endpoint = validateHttpUrl(server, forbidUserInfo = true)
            .trimEnd('/').removeSuffix("/player_api.php").trimEnd('/')
        require(username.isNotBlank() && password.isNotBlank()) {
            "Enter both the Xtream username and password."
        }
        val source = IptvSource(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Xtream" },
            type = IptvSourceType.XTREAM,
            url = endpoint,
            username = username.trim(),
            password = password,
        )
        addSource(source)
        return source
    }

    fun deleteSource(sourceId: String) {
        saveSources(loadSources().filterNot { it.id == sourceId })
    }

    fun syncSource(sourceId: String): IptvSource {
        val sources = loadSources()
        val source = sources.firstOrNull { it.id == sourceId }
            ?: throw IptvRepositoryException("Playlist no longer exists.")
        val channels = when (source.type) {
            IptvSourceType.M3U_URL ->
                IptvM3uParser.parse(requestText(source.url), source)
            IptvSourceType.M3U_FILE ->
                IptvM3uParser.parse(source.localContent, source)
            IptvSourceType.XTREAM ->
                fetchXtreamChannels(source)
        }
        val updated = source.copy(
            cachedChannels = channels,
            lastSyncedAt = System.currentTimeMillis(),
        )
        saveSources(sources.map { if (it.id == sourceId) updated else it })
        return updated
    }

    fun syncAll(): IptvSyncResult {
        val initial = loadSources()
        val errors = mutableListOf<String>()
        initial.filter(IptvSource::enabled).forEach { source ->
            try {
                syncSource(source.id)
            } catch (error: Exception) {
                errors += "${source.name}: ${safeMessage(error)}"
            }
        }
        return IptvSyncResult(loadSources(), errors)
    }

    private fun addSource(source: IptvSource) {
        val existing = loadSources()
        require(existing.size < MAX_SOURCES) { "A maximum of $MAX_SOURCES sources can be saved." }
        require(existing.none { candidate ->
            when (source.type) {
                IptvSourceType.M3U_URL ->
                    candidate.type == source.type && candidate.url.equals(source.url, ignoreCase = true)
                IptvSourceType.XTREAM ->
                    candidate.type == source.type &&
                        candidate.url.equals(source.url, ignoreCase = true) &&
                        candidate.username == source.username
                IptvSourceType.M3U_FILE -> false
            }
        }) { "This source is already saved." }
        saveSources(existing + source)
    }

    private fun fetchXtreamChannels(source: IptvSource): List<IptvChannel> {
        val auth = "username=${query(source.username)}&password=${query(source.password)}"
        // Do not forward credentials across a redirect to another host.
        val categoriesText = requestText(
            "${source.url}/player_api.php?$auth&action=get_live_categories",
            allowRedirects = false,
        )
        val streamsText = requestText(
            "${source.url}/player_api.php?$auth&action=get_live_streams",
            allowRedirects = false,
        )
        val categories = parseJsonArray(categoriesText, allowEmptyObject = true)
            .associate { item ->
                item.optString("category_id") to item.optString("category_name")
            }
        return parseJsonArray(streamsText).let { streams ->
            require(streams.size <= MAX_CHANNELS) { "Xtream returned more than $MAX_CHANNELS channels." }
            streams.mapNotNull { item ->
                val streamId = item.optString("stream_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = item.optString("name").ifBlank { "Channel $streamId" }
                val streamType = item.optString("stream_type").lowercase()
                val suffix = if (streamType.contains("m3u8")) "m3u8" else "ts"
                val encodedUser = pathSegment(source.username)
                val encodedPassword = pathSegment(source.password)
                val encodedStreamId = pathSegment(streamId)
                IptvChannel(
                    id = "${source.id}:$streamId",
                    sourceId = source.id,
                    name = name,
                    streamUrl = "${source.url}/live/$encodedUser/$encodedPassword/$encodedStreamId.$suffix",
                    logoUrl = item.optString("stream_icon"),
                    group = categories[item.optString("category_id")].orEmpty(),
                    epgId = item.optString("epg_channel_id"),
                )
            }
        }
    }

    private fun parseJsonArray(text: String, allowEmptyObject: Boolean = false): List<JSONObject> {
        val normalized = text.trim()
        if (allowEmptyObject && normalized.startsWith("{")) return emptyList()
        if (!normalized.startsWith("[")) {
            throw IptvRepositoryException("Xtream rejected the login or returned an invalid response.")
        }
        val array = JSONArray(normalized)
        return List(array.length()) { index -> array.getJSONObject(index) }
    }

    private fun requestText(url: String, allowRedirects: Boolean = true): String {
        val connection = try {
            URL(url).openConnection() as? HttpURLConnection
                ?: throw IptvRepositoryException("This IPTV server type is not supported.")
        } catch (error: IptvRepositoryException) {
            throw error
        } catch (error: Exception) {
            throw IptvRepositoryException("The IPTV server address is invalid.", error)
        }
        try {
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = allowRedirects
            connection.setRequestProperty("User-Agent", "TvHome IPTV/1.0")
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IptvRepositoryException("The IPTV server returned HTTP $status.")
            }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) {
                        throw IptvRepositoryException("The IPTV response is too large.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return String(bytes, StandardCharsets.UTF_8)
        } catch (error: IptvRepositoryException) {
            throw error
        } catch (error: Exception) {
            throw IptvRepositoryException("Could not reach the IPTV server.", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateHttpUrl(raw: String, forbidUserInfo: Boolean = false): String {
        val value = raw.trim()
        val url = try {
            URL(value)
        } catch (error: Exception) {
            throw IptvRepositoryException("Enter a valid HTTP or HTTPS address.", error)
        }
        require(url.protocol.equals("http", true) || url.protocol.equals("https", true)) {
            "Only HTTP and HTTPS sources are supported."
        }
        require(!url.host.isNullOrBlank()) { "Enter a valid HTTP or HTTPS address." }
        if (forbidUserInfo) {
            require(url.userInfo.isNullOrBlank()) {
                "Keep login credentials in the Xtream fields, not in the server URL."
            }
        }
        return value
    }

    private fun saveSources(sources: List<IptvSource>) = IptvStorage.save(appContext, sources)

    private fun safeMessage(error: Exception): String =
        (error as? IptvRepositoryException)?.message ?: "Could not sync this source."

    private fun query(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun pathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val MAX_PLAYLIST_CHARS = 20 * 1024 * 1024
        private const val MAX_SOURCES = 20
        private const val MAX_CHANNELS = 20_000
        private const val MAX_RESPONSE_BYTES = 20 * 1024 * 1024
        private const val REQUEST_TIMEOUT_MS = 18_000
    }
}

internal class IptvRepositoryException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

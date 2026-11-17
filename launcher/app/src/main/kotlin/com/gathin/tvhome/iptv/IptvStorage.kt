package com.gathin.tvhome.iptv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local-first storage. Xtream credentials and imported M3U contents are encrypted with an Android Keystore
 * AES-GCM key; the app does not put credentials in logs, backups, or plaintext preferences.
 */
internal object IptvStorage {
    private const val KEY_ALIAS = "com.gathin.tvhome.iptv.state.v1"
    private const val FILE_NAME = "iptv-state.enc"
    private const val FORMAT_VERSION: Byte = 1

    fun load(context: Context): List<IptvSource> {
        val atomic = stateFile(context)
        if (!atomic.baseFile.exists()) return emptyList()
        return try {
            val encoded = atomic.openRead().use { it.readBytes() }
            val cleartext = decrypt(encoded)
            decodeSources(String(cleartext, StandardCharsets.UTF_8))
        } catch (error: IptvRepositoryException) {
            throw error
        } catch (error: Exception) {
            throw IptvRepositoryException("Saved IPTV data could not be opened. It was not overwritten.", error)
        }
    }

    fun save(context: Context, sources: List<IptvSource>) {
        val encoded = encrypt(encodeSources(sources).toByteArray(StandardCharsets.UTF_8))
        val atomic = stateFile(context)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(encoded)
            atomic.finishWrite(stream)
            stream = null
        } catch (error: Exception) {
            stream?.let(atomic::failWrite)
            throw IptvRepositoryException("Could not securely save IPTV data.", error)
        }
    }

    private fun stateFile(context: Context): AtomicFile {
        val file = File(context.filesDir, FILE_NAME)
        file.parentFile?.mkdirs()
        return AtomicFile(file)
    }

    private fun encrypt(cleartext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(cleartext)
        return ByteBuffer.allocate(2 + iv.size + ciphertext.size)
            .put(FORMAT_VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(encoded: ByteArray): ByteArray {
        if (encoded.size < 2 || encoded[0] != FORMAT_VERSION) {
            throw IptvRepositoryException("Saved IPTV data has an unsupported format.")
        }
        val ivSize = encoded[1].toInt() and 0xff
        if (ivSize !in 12..16 || encoded.size <= 2 + ivSize) {
            throw IptvRepositoryException("Saved IPTV data is incomplete.")
        }
        val iv = encoded.copyOfRange(2, 2 + ivSize)
        val ciphertext = encoded.copyOfRange(2 + ivSize, encoded.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encodeSources(sources: List<IptvSource>): String =
        JSONObject()
            .put("version", 1)
            .put("sources", JSONArray().apply {
                sources.forEach { source ->
                    put(
                        JSONObject()
                            .put("id", source.id)
                            .put("name", source.name)
                            .put("type", source.type.name)
                            .put("url", source.url)
                            .put("username", source.username)
                            .put("password", source.password)
                            .put("localContent", source.localContent)
                            .put("enabled", source.enabled)
                            .put("lastSyncedAt", source.lastSyncedAt)
                            .put("channels", JSONArray().apply {
                                source.cachedChannels.forEach { channel ->
                                    put(
                                        JSONObject()
                                            .put("id", channel.id)
                                            .put("sourceId", channel.sourceId)
                                            .put("name", channel.name)
                                            .put("streamUrl", channel.streamUrl)
                                            .put("logoUrl", channel.logoUrl)
                                            .put("group", channel.group)
                                            .put("epgId", channel.epgId)
                                            .put("headers", JSONObject(channel.headers))
                                            .put("drm", channel.drm?.let { drm ->
                                                JSONObject()
                                                    .put("scheme", drm.scheme)
                                                    .put("licenseUrl", drm.licenseUrl)
                                                    .put("licenseKey", drm.licenseKey)
                                            }),
                                    )
                                }
                            }),
                    )
                }
            })
            .toString()

    private fun decodeSources(json: String): List<IptvSource> {
        val root = JSONObject(json)
        if (root.optInt("version") != 1) {
            throw IptvRepositoryException("Saved IPTV data has an unsupported version.")
        }
        val sources = root.optJSONArray("sources") ?: return emptyList()
        return List(sources.length()) { index ->
            val item = sources.getJSONObject(index)
            val headers = item.optJSONArray("channels")
            val channels = List(headers?.length() ?: 0) { channelIndex ->
                val channel = headers!!.getJSONObject(channelIndex)
                val headerObject = channel.optJSONObject("headers") ?: JSONObject()
                val channelHeaders = buildMap {
                    val keys = headerObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        put(key, headerObject.optString(key))
                    }
                }
                val drmObject = channel.optJSONObject("drm")
                val drm = drmObject?.let {
                    val scheme = it.optString("scheme")
                    if (scheme.isBlank()) null else IptvDrmConfig(
                        scheme = scheme,
                        licenseUrl = it.optString("licenseUrl"),
                        licenseKey = it.optString("licenseKey"),
                    )
                }
                IptvChannel(
                    id = channel.getString("id"),
                    sourceId = channel.getString("sourceId"),
                    name = channel.getString("name"),
                    streamUrl = channel.getString("streamUrl"),
                    logoUrl = channel.optString("logoUrl"),
                    group = channel.optString("group"),
                    epgId = channel.optString("epgId"),
                    headers = channelHeaders,
                    drm = drm,
                )
            }
            IptvSource(
                id = item.getString("id"),
                name = item.getString("name"),
                type = runCatching { IptvSourceType.valueOf(item.getString("type")) }
                    .getOrElse { throw IptvRepositoryException("Saved IPTV source type is invalid.", it) },
                url = item.optString("url"),
                username = item.optString("username"),
                password = item.optString("password"),
                localContent = item.optString("localContent"),
                enabled = item.optBoolean("enabled", true),
                lastSyncedAt = item.optLong("lastSyncedAt"),
                cachedChannels = channels,
            )
        }
    }

}

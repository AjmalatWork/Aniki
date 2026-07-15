package com.aniki.anikiai.share

import android.net.Uri
import com.aniki.anikiai.data.db.ItemType

data class ClassifiedContent(
    val type: String,
    val sourceUrl: String?,
    val normalizedUrl: String?,
    val title: String,
    val bodyText: String? = null
)

private val TRACKING_PARAMS = setOf(
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
    "gclid", "fbclid", "igshid", "mc_cid", "mc_eid"
)

private val URL_TOKEN_REGEX = Regex("""https?://\S+""")

object ShareContentClassifier {

    fun classify(rawText: String): ClassifiedContent {
        val trimmed = rawText.trim()
        val url = extractUrl(trimmed)

        if (url == null) {
            return ClassifiedContent(
                type = ItemType.NOTE,
                sourceUrl = null,
                normalizedUrl = null,
                title = trimmed.take(60),
                bodyText = trimmed
            )
        }

        val host = url.host?.lowercase().orEmpty()
        val type = if (isYouTubeHost(host)) ItemType.YOUTUBE_VIDEO else ItemType.WEB_ARTICLE
        val sourceUrl = url.toString()

        return ClassifiedContent(
            type = type,
            sourceUrl = sourceUrl,
            normalizedUrl = normalizeUrl(url),
            title = sourceUrl
        )
    }

    private fun isYouTubeHost(host: String): Boolean {
        return host == "youtu.be" || host.endsWith(".youtu.be") ||
            host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun extractUrl(text: String): Uri? {
        val candidate = URL_TOKEN_REGEX.find(text)?.value ?: text
        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri
    }

    private fun normalizeUrl(uri: Uri): String {
        val scheme = uri.scheme?.lowercase() ?: "https"
        val host = uri.host?.lowercase() ?: ""
        var path = uri.path.orEmpty()
        if (path.length > 1 && path.endsWith("/")) {
            path = path.trimEnd('/')
        }

        val keptParams = uri.queryParameterNames
            .filterNot { it.lowercase() in TRACKING_PARAMS }
            .sorted()

        val query = keptParams.joinToString("&") { name ->
            val values = uri.getQueryParameters(name)
            values.joinToString("&") { value -> "$name=$value" }
        }

        return buildString {
            append(scheme).append("://").append(host).append(path)
            if (query.isNotEmpty()) append('?').append(query)
        }
    }
}

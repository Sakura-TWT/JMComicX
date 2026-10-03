package app.prismia.source.iwara

import app.prismia.foundation.ContentKey
import app.prismia.foundation.ContentSource
import app.prismia.foundation.ContentType
import app.prismia.foundation.SourceErrorCategory
import app.prismia.foundation.SourceFailure
import app.prismia.foundation.SourceFailureCarrier
import app.prismia.video.PagedVideoCatalog
import app.prismia.video.StreamVariant
import app.prismia.video.VideoAvailability
import app.prismia.video.VideoDetail
import app.prismia.video.VideoPage
import app.prismia.video.VideoWork
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

class IwaraClient(
    private val transport: IwaraTransport,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) : PagedVideoCatalog {
    override suspend fun browse(page: Int, limit: Int): VideoPage {
        validatePage(page, limit)
        val json = transport.get("videos", mapOf("page" to page.toString(), "limit" to limit.toString()))
        return parsePage(json, page, limit)
    }

    override suspend fun searchPage(query: String, page: Int, limit: Int): VideoPage {
        require(query.isNotBlank()) { "query must not be blank" }
        validatePage(page, limit)
        val json = transport.get(
            "search",
            mapOf("query" to query, "type" to "videos", "page" to page.toString(), "limit" to limit.toString()),
        )
        return parsePage(json, page, limit)
    }

    override suspend fun find(query: String): List<VideoWork> = searchPage(query).items

    override suspend fun detail(key: ContentKey): VideoWork = detailPage(key).work

    override suspend fun detailPage(key: ContentKey): VideoDetail {
        require(key.contentType == ContentType.VIDEO) { "IwaraClient requires a VIDEO content key" }
        require(key.source == ContentSource.IWARA) { "IwaraClient requires an IWARA content key" }
        val json = transport.get("video/${key.remoteId}")
        val detail = parseObject(json)
        val record = parseVideo(detail)
        val streams = parseStreams(detail).ifEmpty {
            detail.string("fileUrl")?.let { fileUrl ->
                parseStreams(JsonParser.parseString(transport.get(fileUrl.normalizeUrl())))
            }.orEmpty()
        }
        return VideoDetail(
            work = record.toWork(if (streams.isNotEmpty()) VideoAvailability.PLAYABLE else VideoAvailability.UNKNOWN),
            variants = streams.map { it.toDomain() },
        )
    }

    suspend fun refreshStreams(iwaraId: String): List<StreamVariant> =
        detailPage(ContentKey(ContentType.VIDEO, ContentSource.IWARA, iwaraId)).variants

    private fun parsePage(raw: String, page: Int, limit: Int): VideoPage {
        val root = parseObject(raw)
        val results = root.array("results") ?: root.array("data") ?: JsonArray()
        val items = results.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject?.let(::parseVideo) }
        return VideoPage(items.map { it.toWork(VideoAvailability.UNKNOWN) }, page, items.size >= limit)
    }

    private fun parseVideo(json: JsonObject): IwaraVideoRecord {
        val user = json.obj("user")
        val tags = (json.array("tags") ?: JsonArray()).mapNotNull { tag ->
            when {
                tag.isJsonPrimitive -> tag.asString
                tag.isJsonObject -> tag.asJsonObject.string("name") ?: tag.asJsonObject.string("id")
                else -> null
            }
        }
        return IwaraVideoRecord(
            id = json.string("id") ?: json.string("_id") ?: throw IwaraParseException("iwara video has no id"),
            title = json.string("title") ?: "",
            username = user?.string("username") ?: user?.string("name") ?: json.string("username"),
            thumbnailUrl = json.string("thumbnailUrl") ?: json.string("thumbnail") ?: json.string("thumbnail_url"),
            durationSeconds = json.long("duration")
                ?: json.long("durationSeconds")
                ?: json.obj("file")?.long("duration"),
            tags = tags,
            body = json.string("body") ?: json.string("description"),
            rating = json.string("rating"),
            status = json.string("status"),
        )
    }

    private fun parseStreams(root: JsonElement): List<IwaraStream> {
        val renditions = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> {
                val json = root.asJsonObject
                json.array("renditions")
                    ?: json.array("results")
                    ?: json.obj("file")?.array("renditions")
                    ?: JsonArray()
            }
            else -> JsonArray()
        }
        return renditions.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val item = element.asJsonObject
            val src = item.string("src")
                ?: item.obj("src")?.string("view")
                ?: item.string("url")
                ?: return@mapNotNull null
            val normalizedUrl = src.normalizeUrl()
            IwaraStream(
                name = item.string("name") ?: item.string("quality") ?: "auto",
                url = normalizedUrl,
                width = item.int("width"),
                height = item.int("height"),
                bitrate = item.long("bitrate"),
                expiresAtEpochSeconds = item.long("expires")
                    ?.toEpochSeconds()
                    ?: item.long("expiresAt")?.toEpochSeconds()
                    ?: normalizedUrl.extractExpiresEpochSeconds()
                    ?: (nowEpochSeconds() + 3600),
            )
        }.distinctBy { it.url }
    }

    private fun parseObject(raw: String): JsonObject = try {
        JsonParser.parseString(raw).takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: throw IwaraParseException("expected a JSON object")
    } catch (failure: IwaraParseException) {
        throw failure
    } catch (failure: RuntimeException) {
        throw IwaraParseException("invalid Iwara JSON", failure)
    }

    private fun validatePage(page: Int, limit: Int) {
        require(page >= 0) { "page must be non-negative" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
    }

    private fun IwaraVideoRecord.toWork(availability: VideoAvailability) = VideoWork(
        key = ContentKey(ContentType.VIDEO, ContentSource.IWARA, id),
        title = title,
        author = username,
        coverUrl = thumbnailUrl,
        durationMs = durationSeconds?.times(1000),
        description = body,
        tags = tags,
        availability = when (status?.lowercase()) {
            "deleted", "removed", "tombstoned" -> VideoAvailability.TOMBSTONED
            else -> availability
        },
        sourceLinks = mapOf(ContentSource.IWARA to id),
    )

    private fun IwaraStream.toDomain() = StreamVariant(name, url, width, height, bitrate, expiresAtEpochSeconds)
}

class IwaraParseException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause), SourceFailureCarrier {
    override val sourceFailure: SourceFailure
        get() = SourceFailure(
            source = ContentSource.IWARA,
            operation = "parse",
            category = SourceErrorCategory.JSON,
            retryable = false,
            message = checkNotNull(super.message),
            cause = cause,
        )
}

private fun Long.toEpochSeconds(): Long = if (this >= 1_000_000_000_000L) this / 1000 else this

private fun String.normalizeUrl(): String = if (startsWith("//")) "https:$this" else this

private val EXPIRY_QUERY = Regex("(?:[?&])(?:expires|expire|exp)=(\\d+)")

private fun String.extractExpiresEpochSeconds(): Long? =
    EXPIRY_QUERY.find(this)?.groupValues?.getOrNull(1)?.toLongOrNull()?.toEpochSeconds()

private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }?.asString
private fun JsonObject.long(name: String): Long? = get(name)
    ?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }
    ?.let { runCatching { it.asLong }.getOrNull() }
private fun JsonObject.int(name: String): Int? = get(name)
    ?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }
    ?.let { runCatching { it.asInt }.getOrNull() }
private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.array(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

package dev.jmx.client.core.session

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.jmx.client.core.cache.KeyValueStore
import okhttp3.Cookie
import okhttp3.HttpUrl

class PersistentCookieStore(
    private val keyValueStore: KeyValueStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val gson: Gson = Gson()
) : CookieStore {
    private val lock = Any()

    override fun save(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val retained = readCookies().filterNot { old ->
                cookies.any { new -> old.identityKey() == new.identityKey() }
            }
            writeCookies(
                (retained + cookies.filter { !it.isExpired() })
                    .filter { !it.isExpired() }
                    .latestByIdentity()
            )
        }
    }

    override fun load(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val cookies = readCookies().filter { !it.isExpired() }
            writeCookies(cookies)
            return cookies.filter { it.matches(url) }
        }
    }

    override fun snapshot(): List<Cookie> = synchronized(lock) {
        val cookies = readCookies().filter { !it.isExpired() }
        writeCookies(cookies)
        cookies
    }

    override fun replace(cookies: List<Cookie>) {
        synchronized(lock) {
            writeCookies(cookies.filter { !it.isExpired() }.latestByIdentity())
        }
    }

    override fun clear() {
        keyValueStore.putString(KEY_COOKIES, null)
    }

    private fun readCookies(): List<Cookie> {
        val json = keyValueStore.getString(KEY_COOKIES) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<PersistedCookie>>() {}.type
            gson.fromJson<List<PersistedCookie>>(json, type)
                .mapNotNull { it.toCookie() }
        }.getOrDefault(emptyList())
    }

    private fun writeCookies(cookies: List<Cookie>) {
        val normalized = cookies.latestByIdentity()
        if (normalized.isEmpty()) {
            keyValueStore.putString(KEY_COOKIES, null)
        } else {
            keyValueStore.putString(KEY_COOKIES, gson.toJson(normalized.map { it.toPersistedCookie() }))
        }
    }

    private fun Cookie.identityKey(): String = "${domain}|${path}|${name}"

    private fun List<Cookie>.latestByIdentity(): List<Cookie> =
        asReversed().distinctBy { it.identityKey() }.asReversed()

    private fun Cookie.isExpired(): Boolean = expiresAt < nowMillis()

    private fun Cookie.toPersistedCookie(): PersistedCookie {
        return PersistedCookie(
            name = name,
            value = value,
            expiresAt = expiresAt,
            domain = domain,
            path = path,
            secure = secure,
            httpOnly = httpOnly,
            hostOnly = hostOnly
        )
    }

    private data class PersistedCookie(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean
    ) {
        fun toCookie(): Cookie? {
            return runCatching {
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .expiresAt(expiresAt)
                    .apply {
                        if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                        path(path)
                        if (secure) secure()
                        if (httpOnly) httpOnly()
                    }
                    .build()
            }.getOrNull()
        }
    }

    private companion object {
        const val KEY_COOKIES = "session.cookies"
    }
}

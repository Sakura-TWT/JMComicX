package dev.jmx.client.core.session

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

interface CookieStore {
    fun save(url: HttpUrl, cookies: List<Cookie>)
    fun load(url: HttpUrl): List<Cookie>
    fun snapshot(): List<Cookie>
    fun replace(cookies: List<Cookie>)
    fun clear()
}

class InMemoryCookieStore(
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) : CookieStore {
    private val lock = Any()
    private var cookies: List<Cookie> = emptyList()

    override fun save(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val retained = this.cookies.filterNot { old ->
                cookies.any { new -> old.identityKey() == new.identityKey() }
            }
            this.cookies = (retained + cookies.filter { !it.isExpired() })
                .filter { !it.isExpired() }
                .latestByIdentity()
        }
    }

    override fun load(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            cookies = cookies.filter { !it.isExpired() }
            return cookies.filter { it.matches(url) }
        }
    }

    override fun snapshot(): List<Cookie> = synchronized(lock) { cookies.filter { !it.isExpired() } }

    override fun replace(cookies: List<Cookie>) {
        synchronized(lock) {
            this.cookies = cookies.filter { !it.isExpired() }.latestByIdentity()
        }
    }

    override fun clear() {
        synchronized(lock) {
            cookies = emptyList()
        }
    }

    private fun Cookie.identityKey(): String = "${domain}|${path}|${name}"

    private fun List<Cookie>.latestByIdentity(): List<Cookie> =
        asReversed().distinctBy { it.identityKey() }.asReversed()

    private fun Cookie.isExpired(): Boolean = expiresAt < nowMillis()
}

class StoreBackedCookieJar(
    private val store: CookieStore,

    private val alwaysAttachNames: Set<String> = setOf("AVS")
) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.save(url, cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val matched = store.load(url)
        if (alwaysAttachNames.isEmpty()) return matched
        val present = matched.map { it.name.lowercase() }.toHashSet()
        val snapshot = store.snapshot()
        val extras = alwaysAttachNames.mapNotNull { name ->
            if (name.lowercase() in present) null
            else snapshot.lastOrNull { it.name.equals(name, ignoreCase = true) }
        }
        return if (extras.isEmpty()) matched else matched + extras
    }
}

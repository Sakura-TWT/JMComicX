package dev.jmx.v2.foundation.session

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

class InMemoryCookieStore : CookieStore {
    private val lock = Any()
    private var cookies: List<Cookie> = emptyList()

    override fun save(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val merged = (this.cookies.filterNot { old ->
                cookies.any { new -> old.identityKey() == new.identityKey() }
            } + cookies)
                .filter { !it.isExpired() }
            this.cookies = merged
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
            this.cookies = cookies.filter { !it.isExpired() }.distinctBy { it.identityKey() }
        }
    }

    override fun clear() {
        synchronized(lock) {
            cookies = emptyList()
        }
    }

    private fun Cookie.identityKey(): String = "${domain}|${path}|${name}"

    private fun Cookie.isExpired(): Boolean = expiresAt < System.currentTimeMillis()
}

class StoreBackedCookieJar(
    private val store: CookieStore
) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.save(url, cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store.load(url)
    }
}

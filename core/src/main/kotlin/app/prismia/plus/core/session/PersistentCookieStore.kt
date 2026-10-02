package app.prismia.plus.core.session

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import app.prismia.plus.core.cache.KeyValueStore
import okhttp3.Cookie
import okhttp3.HttpUrl

class PersistentCookieStore(
    private val keyValueStore: KeyValueStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val gson: Gson = Gson()
) : CookieStore {
    private val lock = Any()

    /** [readCookies] 的解析备忘：键是底层存的原始 JSON 串。仅在持有 [lock] 时访问。 */
    private var cachedJson: String? = null
    private var cachedCookies: List<Cookie> = emptyList()

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
            return pruneExpired().filter { it.matches(url) }
        }
    }

    override fun snapshot(): List<Cookie> = synchronized(lock) { pruneExpired() }

    override fun replace(cookies: List<Cookie>) {
        synchronized(lock) {
            writeCookies(cookies.filter { !it.isExpired() }.latestByIdentity())
        }
    }

    override fun clear() {
        synchronized(lock) {
            cachedJson = null
            cachedCookies = emptyList()
            keyValueStore.putString(KEY_COOKIES, null)
        }
    }

    /**
     * 读出未过期的 Cookie；只有确实剔掉了过期项才回写。
     *
     * 原实现每次读都无条件回写一遍全量 JSON。而读路径是每个 HTTP 请求都要走的
     * ——[StoreBackedCookieJar.loadForRequest] 一次请求就要 load + snapshot 两趟——
     * 于是每张封面、每页漫画都在给存储排两次序列化 + 落盘：既拖慢请求发起，
     * 也让进程被杀时排队中的写入更容易丢，而丢掉的正是登录用的 AVS。
     */
    private fun pruneExpired(): List<Cookie> {
        val stored = readCookies()
        val alive = stored.filter { !it.isExpired() }
        if (alive.size != stored.size) writeCookies(alive)
        return alive
    }

    /**
     * @return 反序列化后的 Cookie 表。同一份 JSON 只解析一次：
     *   读路径每请求都要走，Gson 在这里是纯重复开销。写入方一律经过 [writeCookies]，
     *   备忘随之更新；外部改了底层存储时 JSON 串会变，比较即失配，不会读到旧值。
     */
    private fun readCookies(): List<Cookie> {
        val json = keyValueStore.getString(KEY_COOKIES)
        if (json == null) {
            cachedJson = null
            cachedCookies = emptyList()
            return emptyList()
        }
        if (json == cachedJson) return cachedCookies
        val parsed = runCatching {
            val type = object : TypeToken<List<PersistedCookie>>() {}.type
            gson.fromJson<List<PersistedCookie>>(json, type)
                .mapNotNull { it.toCookie() }
        }.getOrDefault(emptyList())
        cachedJson = json
        cachedCookies = parsed
        return parsed
    }

    private fun writeCookies(cookies: List<Cookie>) {
        val normalized = cookies.latestByIdentity()
        val json = if (normalized.isEmpty()) {
            null
        } else {
            gson.toJson(normalized.map { it.toPersistedCookie() })
        }
        cachedJson = json
        cachedCookies = if (json == null) emptyList() else normalized
        keyValueStore.putString(KEY_COOKIES, json)
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

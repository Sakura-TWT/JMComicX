package app.prismia.plus.core.api

/**
 * 组合 JM 服务端搜索语法的 search_query。
 *
 * JM 服务端原生支持标签级的包含/排除过滤（已通过真实接口验证）：
 * - `+标签`：结果必须包含该标签（多个 `+` 之间为 AND）。
 * - `-标签`：结果必须排除该标签（多个 `-` 之间为 NOT ANY）。
 * - 不带前缀的裸词：普通关键字（标题/标签/作者全站匹配），多个裸词之间为 OR。
 * - 服务端自行完成简体/繁体归一化，无需客户端再对同一查询做简繁变体扩散。
 *
 * 因此标签过滤应在服务端一次性完成，而不是拉取每个结果的详情后在客户端过滤。
 *
 * 约束：组合结果必须至少包含一个“正向词”（裸词或 `+标签`）。仅有 `-标签` 的查询
 * 在服务端没有锚点，不会返回有意义的结果，调用方需保证 baseQuery 非空或存在包含标签。
 */
object SearchQueryComposer {

    fun compose(
        baseQuery: String,
        includeTags: List<String> = emptyList(),
        excludeTags: List<String> = emptyList(),
    ): String {
        val base = baseQuery.trim()
        val includes = includeTags.sanitize()
        // 排除项不应与包含项冲突：同一个标签若同时出现在两边，包含优先。
        val excludes = excludeTags.sanitize().filterNot { it in includes }

        val parts = ArrayList<String>(1 + includes.size + excludes.size)
        if (base.isNotEmpty()) parts += base
        includes.forEach { parts += "+$it" }
        excludes.forEach { parts += "-$it" }
        return parts.joinToString(" ")
    }

    /**
     * 单个标签词内部若含空白，会被服务端拆成多个词，破坏 `+`/`-` 语义。
     * 这里将内部空白折叠成单一空格后仅保留首段，并去除已有的 +/- 前缀，避免叠加。
     */
    private fun List<String>.sanitize(): List<String> = this
        .mapNotNull { raw ->
            raw.trim()
                .removePrefix("+")
                .removePrefix("-")
                .trim()
                .split(WHITESPACE)
                .firstOrNull { it.isNotBlank() }
        }
        .filter { it.isNotEmpty() }
        .distinct()

    private val WHITESPACE = Regex("\\s+")
}

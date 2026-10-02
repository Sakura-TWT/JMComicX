package app.prismia.plus.core.network

/**
 * 线路健康度里"失败罚分"的时间衰减。
 *
 * 没有衰减时选路会自锁：A 一直通、B 半小时前抖过两次，B 的罚分永远清不掉
 * （罚分只在 B 成功时清零，而 B 不被选中就永远不会有成功记录）。
 * 于是等到 A 真的开始变慢，我们仍然会守着 A——因为 B 背着一笔早就过期的旧账。
 * 让罚分随时间半衰，就能在"优先用已验证可用的那台"和"不把临时故障判成终身"之间取平衡。
 */
object HostHealth {
    /** 刚失败后的保护期：这段时间内罚分不打折，避免抖动中的机器立刻被重新选上。 */
    const val PENALTY_GRACE_MILLIS = 60_000L

    /** 过了保护期后，每经过这么久罚分减半。 */
    const val PENALTY_HALF_LIFE_MILLIS = 120_000L

    /** 衰减上限；到这一步罚分已不足原值的 1/32，可视为清账。 */
    private const val MAX_HALF_LIVES = 5

    fun decayPenalty(penalty: Int, lastFailureAtMillis: Long?, nowMillis: Long): Int {
        if (penalty <= 0) return 0
        // 有罚分却没有失败时间戳：只可能来自持久化的旧结构，不猜它有多旧，按原值算。
        val lastFailure = lastFailureAtMillis ?: return penalty
        val elapsed = nowMillis - lastFailure
        if (elapsed <= PENALTY_GRACE_MILLIS) return penalty
        val halfLives = ((elapsed - PENALTY_GRACE_MILLIS) / PENALTY_HALF_LIFE_MILLIS)
            .coerceIn(0L, MAX_HALF_LIVES.toLong())
            .toInt()
        return penalty shr halfLives
    }
}

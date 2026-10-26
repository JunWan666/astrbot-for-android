package io.astrbot.android.core

/** Successful checks are daily; interrupted/failed attempts get a one-hour cooldown.
 * A clock correction must not postpone checks indefinitely.
 */
object AutomaticUpdatePolicy {
    const val SUCCESS_INTERVAL = 24L * 60 * 60 * 1000
    const val RETRY_INTERVAL = 60L * 60 * 1000

    fun isDue(now: Long, lastSuccess: Long, lastAttempt: Long): Boolean {
        if (now <= 0) return false
        fun recent(at: Long, interval: Long) = at > 0 && at <= now && now - at < interval
        return !recent(lastSuccess, SUCCESS_INTERVAL) && !recent(lastAttempt, RETRY_INTERVAL)
    }
}

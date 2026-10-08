package app.budgetguard.android.sync

internal const val DASHBOARD_FETCH_PAGE_SIZE = 500
internal const val DASHBOARD_FETCH_MAX_PAGES = 20

data class PagedResult<T>(
    val rows: List<T>,
    val truncated: Boolean,
)

/**
 * Walks a range-based list endpoint until a short page or [maxPages].
 * [fetch] receives inclusive `from`/`to` indexes (`range(from, from+pageSize-1)`).
 * Hitting [maxPages] with a full final page sets [PagedResult.truncated] rather
 * than silently dropping the remainder.
 */
suspend fun <T> fetchAllPages(
    pageSize: Int,
    maxPages: Int,
    fetch: suspend (from: Long, to: Long) -> List<T>,
): PagedResult<T> {
    require(pageSize > 0) { "pageSize must be positive." }
    require(maxPages > 0) { "maxPages must be positive." }
    val rows = ArrayList<T>()
    repeat(maxPages) { page ->
        val from = page.toLong() * pageSize
        val to = from + pageSize - 1
        val chunk = fetch(from, to)
        rows += chunk
        if (chunk.size < pageSize) {
            return PagedResult(rows, truncated = false)
        }
    }
    return PagedResult(rows, truncated = true)
}

package com.shsh.pleos.playground.data

/** Total is reported by the source API, not inferred from this page size. */
data class FacilityPage<T>(val items: List<T>, val totalCount: Int)

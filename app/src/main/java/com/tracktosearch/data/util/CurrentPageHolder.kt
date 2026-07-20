package com.tracktosearch.data.util

object CurrentPageHolder {
    @Volatile
    var currentRoute: String? = null

    @Volatile
    var currentPageName: String? = null
}

package com.tracktosearch.data.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UserActionTracker {
    private const val MAX_ACTIONS = 20
    private val actions = ArrayDeque<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun record(category: String, action: String, detail: String? = null) {
        val entry = buildString {
            append(dateFormat.format(Date()))
            append(" [$category] $action")
            if (detail != null) {
                append(" | $detail")
            }
        }
        synchronized(this) {
            if (actions.size >= MAX_ACTIONS) {
                actions.removeFirst()
            }
            actions.addLast(entry)
        }
    }

    fun getSummary(): String = synchronized(this) {
        actions.joinToString("\n")
    }

    fun clear() = synchronized(this) {
        actions.clear()
    }
}

package com.tracktosearch.data.remote.custom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Simplified JSONPath parser using kotlinx.serialization.
 * Supports: $.field, $.field1.field2, $.field[*], $.field[0], $.field1[*].field2
 */
object JsonPathParser {

    /**
     * Extract a list of JsonElements from the JSON using the given path.
     * The path should start with "$" and use dot notation for fields,
     * [*] for array wildcard, and [n] for array index.
     *
     * Examples:
     * - "$.data.results" -> list with one element (the results array contents flattened)
     * - "$.data.merged_by_type[*]" -> flatten all map values into a single list
     * - "$.data.list[0].items" -> first list item's items array
     */
    fun extractList(root: JsonElement, path: String): List<JsonElement> {
        val segments = parsePath(path)
        var current: List<JsonElement> = listOf(root)

        for (segment in segments) {
            val next = mutableListOf<JsonElement>()
            for (item in current) {
                when (segment) {
                    is PathSegment.Field -> {
                        if (item is JsonObject && item.containsKey(segment.name)) {
                            next.add(item[segment.name]!!)
                        }
                    }
                    is PathSegment.ArrayAll -> {
                        if (item is JsonArray) {
                            next.addAll(item)
                        } else if (item is JsonObject) {
                            // For maps (like merged_by_type), flatten all values
                            next.addAll(item.values)
                        }
                    }
                    is PathSegment.ArrayIndex -> {
                        if (item is JsonArray && segment.index < item.size) {
                            next.add(item[segment.index])
                        }
                    }
                }
            }
            current = next
        }

        return current
    }

    /**
     * Extract a string value from a JsonElement using a relative path.
     * The path should NOT start with "$".
     * Examples: "title", "links[0].url", "note"
     */
    fun extractString(element: JsonElement, relativePath: String): String? {
        val segments = parsePath("$.$relativePath")
        var current: JsonElement = element

        for (segment in segments) {
            when (segment) {
                is PathSegment.Field -> {
                    if (current is JsonObject && current.containsKey(segment.name)) {
                        current = current[segment.name]!!
                    } else {
                        return null
                    }
                }
                is PathSegment.ArrayIndex -> {
                    if (current is JsonArray && segment.index < current.size) {
                        current = current[segment.index]
                    } else {
                        return null
                    }
                }
                is PathSegment.ArrayAll -> {
                    // For single value extraction, take first element
                    if (current is JsonArray && current.isNotEmpty()) {
                        current = current[0]
                    } else {
                        return null
                    }
                }
            }
        }

        return when (current) {
            is JsonPrimitive -> current.contentOrNull
            else -> null
        }
    }

    private sealed class PathSegment {
        data class Field(val name: String) : PathSegment()
        data class ArrayIndex(val index: Int) : PathSegment()
        object ArrayAll : PathSegment()
    }

    private fun parsePath(path: String): List<PathSegment> {
        // Remove leading "$."
        val cleaned = path.removePrefix("$").removePrefix(".")
        if (cleaned.isEmpty()) return emptyList()

        val segments = mutableListOf<PathSegment>()
        val parts = cleaned.split(".")
        for (part in parts) {
            if (part.isEmpty()) continue
            // Check for array notation: field[0], field[*], [*], [0]
            val arrayMatch = Regex("""^([^\[]*)(?:\[(\d+|\*)\])+$""").find(part)
            if (arrayMatch != null) {
                val fieldName = arrayMatch.groupValues[1]
                if (fieldName.isNotEmpty()) {
                    segments.add(PathSegment.Field(fieldName))
                }
                // Extract all array indices
                Regex("""\[(\d+|\*)\]""").findAll(part).forEach { match ->
                    val idx = match.groupValues[1]
                    if (idx == "*") {
                        segments.add(PathSegment.ArrayAll)
                    } else {
                        segments.add(PathSegment.ArrayIndex(idx.toInt()))
                    }
                }
            } else {
                segments.add(PathSegment.Field(part))
            }
        }
        return segments
    }
}

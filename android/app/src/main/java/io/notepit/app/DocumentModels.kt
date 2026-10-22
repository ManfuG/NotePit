package io.notepit.app

import org.json.JSONObject

/** Provider document identity; treeUri keeps the exact persisted SAF grant. */
data class DocumentEntry(
    val uri: String,
    val name: String,
    val mime: String = "application/octet-stream",
    val directory: Boolean = false,
    val writable: Boolean = false,
    val size: Long = 0,
    val modified: Long = 0,
    val treeUri: String = "",
    val parentUri: String? = null,
    val available: Boolean = true,
    val flags: Int = 0,
) {
    val pdf: Boolean get() = name.endsWith(".pdf", ignoreCase = true)
    val conflict: Boolean get() = name.contains(".sync-conflict-")
    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("name", name).put("mime", mime)
        .put("directory", directory).put("writable", writable).put("size", size).put("modified", modified)
        .put("treeUri", treeUri).put("parentUri", parentUri).put("available", available).put("flags", flags)
    companion object {
        fun fromJson(value: JSONObject) = DocumentEntry(
            uri = value.getString("uri"), name = value.getString("name"), mime = value.optString("mime", "application/octet-stream"),
            directory = value.optBoolean("directory"), writable = value.optBoolean("writable"), size = value.optLong("size"),
            modified = value.optLong("modified"), treeUri = value.optString("treeUri"),
            parentUri = value.optString("parentUri").takeIf { it.isNotEmpty() && it != "null" },
            available = value.optBoolean("available", true), flags = value.optInt("flags"),
        )
    }
}

class FileConflictException(message: String) : java.io.IOException(message)

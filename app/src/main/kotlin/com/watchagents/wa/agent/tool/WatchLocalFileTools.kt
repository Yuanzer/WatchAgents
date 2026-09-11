package com.watchagents.wa.agent.tool

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 手表本地文件与媒体工具（非 root 环境下的"最高可用读权限"）：
 *
 * 1. files_* —— App 私有沙盒工作区（filesDir/workspace），创建/读取/改写/删除文本文件，
 *    不需要任何系统权限；所有路径必须留在工作区内（防穿越）。
 * 2. media_* —— 通过 MediaStore 读取相册照片与文档目录中的文本文件，
 *    需要"照片/文档"运行时权限（在 WA 设置中一键授权）；无权限时返回明确提示。
 *
 * 不做超出 Android 沙盒的事：不读 /data、不读其他应用私有目录（那是 root 能力）。
 */
internal class WatchLocalFileTools(
    context: Context,
) {
    private val appContext = context.applicationContext
    val workspaceRoot: File = File(appContext.filesDir, WORKSPACE_DIR)

    // ==================== files_* ====================

    fun list(path: String, maxItems: Int): JSONObject {
        val dir = resolveDir(path) ?: return errorResult("INVALID_PATH", "路径不合法或不在工作区内")
        if (!dir.exists()) {
            return errorResult("NOT_FOUND", "目录不存在：${displayPath(path)}")
        }
        if (!dir.isDirectory) {
            return errorResult("NOT_A_DIRECTORY", "路径不是目录：${displayPath(path)}")
        }
        val entries = dir.listFiles().orEmpty()
            .sortedBy { file -> if (file.isDirectory) 0 else 1 }
            .sortedByDescending { it.lastModified() }
            .take(maxItems)
        val items = JSONArray()
        entries.forEach { file ->
            items.put(
                JSONObject()
                    .put("name", file.name)
                    .put("isDir", file.isDirectory)
                    .put("size", if (file.isFile) file.length() else JSONObject.NULL)
                    .put("modified", file.lastModified()),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("path", displayPath(path))
            .put("count", items.length())
            .put("items", items)
            .put(
                "hint",
                "工作区根目录：${workspaceRoot.absolutePath}；用相对路径读写文件（如 notes/今日.md）",
            )
    }

    fun read(path: String, maxChars: Int): JSONObject {
        val file = resolveFile(path) ?: return errorResult("INVALID_PATH", "路径不合法或不在工作区内")
        if (!file.exists()) return errorResult("NOT_FOUND", "文件不存在：${displayPath(path)}")
        if (!file.isFile) return errorResult("NOT_A_FILE", "路径不是文件：${displayPath(path)}")
        if (file.length() > MAX_READ_BYTES) {
            return errorResult("TOO_LARGE", "文件超过读取上限（${MAX_READ_BYTES / 1024}KB），请缩小文件")
        }
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return errorResult("IO_ERROR", "读取失败：${it.message ?: "未知错误"}")
        }
        if (looksBinary(bytes)) {
            return errorResult(
                "BINARY_FILE",
                "该文件不是 UTF-8 文本（可能是图片/压缩包等二进制），无法直接读取正文",
            )
        }
        val text = decodeText(bytes)
        val truncated = text.length > maxChars
        val visible = if (truncated) text.take(maxChars) else text
        return JSONObject()
            .put("ok", true)
            .put("path", displayPath(path))
            .put("text", visible)
            .put("truncated", truncated)
            .put("totalChars", text.length)
            .put("bytes", bytes.size)
    }

    fun write(path: String, content: String, mode: String): JSONObject {
        val target = resolveFile(path) ?: return errorResult("INVALID_PATH", "路径不合法或不在工作区内")
        if (mode != "append" && mode != "overwrite") {
            return errorResult("INVALID_ARGUMENT", "mode 必须为 overwrite 或 append")
        }
        if (content.length > MAX_WRITE_CHARS) {
            return errorResult("TOO_LARGE", "内容超过写入上限（${MAX_WRITE_CHARS / 1024}KB 字符）")
        }
        return runCatching {
            target.parentFile?.mkdirs()
            if (mode == "append" && target.exists()) {
                target.appendText(content, Charsets.UTF_8)
            } else {
                target.writeText(content, Charsets.UTF_8)
            }
            JSONObject()
                .put("ok", true)
                .put("path", displayPath(path))
                .put("mode", mode)
                .put("bytes", target.length())
                .put("characters", content.length)
        }.getOrElse {
            errorResult("IO_ERROR", "写入失败：${it.message ?: "未知错误"}")
        }
    }

    fun delete(path: String): JSONObject {
        val target = resolveFile(path) ?: return errorResult("INVALID_PATH", "路径不合法或不在工作区内")
        if (!target.exists()) return errorResult("NOT_FOUND", "文件不存在：${displayPath(path)}")
        if (target.isDirectory) {
            return errorResult("IS_DIRECTORY", "请指定文件路径，不直接删除目录：${displayPath(path)}")
        }
        return runCatching {
            target.delete()
            JSONObject()
                .put("ok", true)
                .put("path", displayPath(path))
                .put("deleted", true)
        }.getOrElse {
            errorResult("IO_ERROR", "删除失败：${it.message ?: "未知错误"}")
        }
    }

    // ==================== media_* ====================

    fun mediaList(kind: String, keyword: String, maxItems: Int): JSONObject {
        val permissionOk = hasReadPermission(appContext)
        if (!permissionOk) {
            return JSONObject()
                .put("ok", false)
                .put("code", "PERMISSION_DENIED")
                .put(
                    "message",
                    "读取相册/文档需要系统授权：请打开手表上 WA 的 设置 → 本地文件与照片 → 授权。",
                )
                .put("permission_granted", false)
        }
        return when (kind) {
            "images" -> listImages(keyword, maxItems)
            "documents" -> listDocuments(keyword, maxItems)
            else -> errorResult("INVALID_ARGUMENT", "kind 必须为 images 或 documents")
        }
    }

    fun mediaRead(kind: String, id: String, maxChars: Int): JSONObject {
        if (!hasReadPermission(appContext)) {
            return JSONObject()
                .put("ok", false)
                .put("code", "PERMISSION_DENIED")
                .put(
                    "message",
                    "读取相册/文档需要系统授权：请打开手表上 WA 的 设置 → 本地文件与照片 → 授权。",
                )
                .put("permission_granted", false)
        }
        return when (kind) {
            "images" -> readImage(id)
            "documents" -> readDocument(id, maxChars)
            else -> errorResult("INVALID_ARGUMENT", "kind 必须为 images 或 documents")
        }
    }

    private fun listImages(keyword: String, maxItems: Int): JSONObject {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        val selection = if (keyword.isBlank()) null else "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = if (keyword.isBlank()) null else arrayOf("%$keyword%")
        return queryMedia(
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection = projection,
            selection = selection,
            selectionArgs = selectionArgs,
            idColumn = MediaStore.Images.Media._ID,
            nameColumn = MediaStore.Images.Media.DISPLAY_NAME,
            mimeColumn = MediaStore.Images.Media.MIME_TYPE,
            sizeColumn = MediaStore.Images.Media.SIZE,
            dateColumn = MediaStore.Images.Media.DATE_MODIFIED,
            kind = "images",
            maxItems = maxItems,
        )
    }

    private fun listDocuments(keyword: String, maxItems: Int): JSONObject {
        val idColumn = MediaStore.Files.FileColumns._ID
        val nameColumn = MediaStore.Files.FileColumns.DISPLAY_NAME
        val mimeColumn = MediaStore.Files.FileColumns.MIME_TYPE
        val sizeColumn = MediaStore.Files.FileColumns.SIZE
        val dateColumn = MediaStore.Files.FileColumns.DATE_MODIFIED
        val selection = buildString {
            append("(")
            // 文本类 mime
            append("$mimeColumn LIKE 'text/%'")
            // 常见文本扩展名（部分文档 mime 为空）
            append(" OR LOWER($nameColumn) LIKE '%.txt'")
            append(" OR LOWER($nameColumn) LIKE '%.md'")
            append(" OR LOWER($nameColumn) LIKE '%.json'")
            append(" OR LOWER($nameColumn) LIKE '%.csv'")
            append(" OR LOWER($nameColumn) LIKE '%.tsv'")
            append(" OR LOWER($nameColumn) LIKE '%.log'")
            append(" OR LOWER($nameColumn) LIKE '%.xml'")
            append(" OR LOWER($nameColumn) LIKE '%.html'")
            append(" OR LOWER($nameColumn) LIKE '%.yml'")
            append(" OR LOWER($nameColumn) LIKE '%.yaml'")
            append(")")
            if (keyword.isNotBlank()) {
                append(" AND $nameColumn LIKE ?")
            }
        }
        val selectionArgs = if (keyword.isBlank()) null else arrayOf("%$keyword%")
        return queryMedia(
            uri = documentsCollectionUri(),
            projection = arrayOf(idColumn, nameColumn, mimeColumn, sizeColumn, dateColumn),
            selection = selection.toString(),
            selectionArgs = selectionArgs,
            idColumn = idColumn,
            nameColumn = nameColumn,
            mimeColumn = mimeColumn,
            sizeColumn = sizeColumn,
            dateColumn = dateColumn,
            kind = "documents",
            maxItems = maxItems,
        )
    }

    private fun queryMedia(
        uri: android.net.Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        idColumn: String,
        nameColumn: String,
        mimeColumn: String,
        sizeColumn: String,
        dateColumn: String,
        kind: String,
        maxItems: Int,
    ): JSONObject {
        val resolver: ContentResolver = appContext.contentResolver
        // 兼容层可能不支持 LIMIT 语法：先带 LIMIT 试，失败再退回普通排序
        val cursor = runCatching {
            resolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "$dateColumn DESC LIMIT ${maxItems.coerceIn(1, 200)}",
            )
        }.getOrNull() ?: runCatching {
            resolver.query(uri, projection, selection, selectionArgs, "$dateColumn DESC")
        }.getOrNull()
        if (cursor == null) return errorResult("QUERY_FAILED", "媒体库查询失败或没有该权限")
        cursor.use {
            val items = JSONArray()
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(idColumn))
                val name = it.getString(it.getColumnIndexOrThrow(nameColumn)).orEmpty()
                val mime = it.getString(it.getColumnIndexOrThrow(mimeColumn)).orEmpty()
                val size = it.getLong(it.getColumnIndexOrThrow(sizeColumn))
                val modified = it.getLong(it.getColumnIndexOrThrow(dateColumn)) * 1000L
                items.put(
                    JSONObject()
                        .put("id", id.toString())
                        .put("name", name)
                        .put("mime", mime)
                        .put("size", size)
                        .put("modified", modified),
                )
                if (items.length() >= maxItems) break
            }
            return JSONObject()
                .put("ok", true)
                .put("kind", kind)
                .put("count", items.length())
                .put("items", items)
                .put("permission_granted", true)
                .put(
                    "hint",
                    if (kind == "images") {
                        "照片：当前模型无法直接“看图”，media_read 会返回其元数据；如需照片内文字请先确认模型是否支持视觉。"
                    } else {
                        "文档：文本类文档（txt/md/json/csv/log 等）可读全文；PDF/Office 暂不支持正文解析。"
                    },
                )
        }
    }

    private fun readImage(id: String): JSONObject {
        val imageId = id.toLongOrNull()
            ?: return errorResult("INVALID_ARGUMENT", "无效的照片 id")
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
        )
        return runCatching {
            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Images.Media._ID}=?",
                arrayOf(imageId.toString()),
                null,
            )
        }.getOrNull()?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use errorResult("NOT_FOUND", "未找到该照片（可能已被删除）")
            }
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                .orEmpty()
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))
                .orEmpty()
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
            val added = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) * 1000L
            JSONObject()
                .put("ok", true)
                .put("kind", "images")
                .put("id", imageId.toString())
                .put("name", name)
                .put("mime", mime)
                .put("size", size)
                .put("added", added)
                .put(
                    "content",
                    "照片元数据：名称=$name，类型=$mime，大小=${formatBytes(size)}。" +
                        "说明：当前会话模型为纯文本模型，无法直接识别图片像素内容；如需图片内的文字，请换用支持视觉的模型，或在图上拍/存为文字再问。",
                )
                .put("image_only", true)
        } ?: errorResult("NOT_FOUND", "未找到该照片或媒体库不可用")
    }

    private fun readDocument(id: String, maxChars: Int): JSONObject {
        val docId = id.toLongOrNull()
            ?: return errorResult("INVALID_ARGUMENT", "无效的文档 id")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        val item = runCatching {
            appContext.contentResolver.query(
                documentsCollectionUri(),
                projection,
                "${MediaStore.Files.FileColumns._ID}=?",
                arrayOf(docId.toString()),
                null,
            )
        }.getOrNull()?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME))
                .orEmpty() to cursor.getString(
                cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            ).orEmpty()
        }
        if (item == null) return errorResult("NOT_FOUND", "未找到该文档（可能已被删除）")
        val (name, mime) = item
        if (mime.startsWith("text/") || isPlainTextExtension(name)) {
            val uri = android.content.ContentUris.withAppendedId(
                documentsCollectionUri(),
                docId,
            )
            return runCatching {
                appContext.contentResolver.openInputStream(uri)
            }.getOrNull()?.use { input ->
                val bytes = input.readBytes().take(MAX_READ_BYTES).toByteArray()
                val raw = decodeText(bytes)
                val truncated = raw.length > maxChars
                val visible = if (truncated) raw.take(maxChars) else raw
                JSONObject()
                    .put("ok", true)
                    .put("kind", "documents")
                    .put("id", docId.toString())
                    .put("name", name)
                    .put("mime", mime)
                    .put("text", visible)
                    .put("truncated", truncated)
                    .put("totalChars", raw.length)
            } ?: errorResult("IO_ERROR", "文档内容读取失败（可能没有该文件的读权限）")
        }
        return errorResult(
            "UNSUPPORTED_FORMAT",
            "文档 $name 是 $mime，当前不支持解析其正文（支持 txt/md/json/csv/log/xml/html/yml 等纯文本）；" +
                "可让 WA 联网查找相关内容，或把内容转成文本后放入工作区。",
        )
    }

    // ==================== helpers ====================

    private fun resolveFile(relativePath: String): File? {
        val file = resolveInternal(relativePath) ?: return null
        if (file.isDirectory) return null
        return file
    }

    private fun resolveDir(relativePath: String): File? {
        val file = resolveInternal(relativePath) ?: return null
        if (!file.isDirectory) return null
        return file
    }

    /** 解析相对路径并确认最终文件位于工作区内（防 .. 穿越）。空路径=工作区根目录。 */
    private fun resolveInternal(relativePath: String): File? {
        val raw = relativePath.trim().replace('\\', '/')
        if (raw.isBlank()) {
            return workspaceRoot.takeIf { it.isDirectory }
        }
        if (raw.startsWith("/") || raw.contains("..") || raw.contains('\u0000')) {
            return null
        }
        if (!workspaceRoot.exists() && !workspaceRoot.mkdirs()) return null
        val resolved = runCatching { File(workspaceRoot, raw).canonicalFile }.getOrNull() ?: return null
        val rootCanonical = runCatching { workspaceRoot.canonicalFile }.getOrNull() ?: return null
        return resolved.takeIf { it.path.startsWith(rootCanonical.path) }
    }

    private fun displayPath(relativePath: String): String = relativePath.trim().ifBlank { "." }

    private fun isPlainTextExtension(name: String): Boolean =
        PLAIN_TEXT_EXTENSIONS.any { name.lowercase().endsWith(it) }

    private fun decodeText(bytes: ByteArray): String {
        // 优先严格 UTF-8；失败时退回 UTF-8 替换字符解码，保证不抛异常
        val utf8 = runCatching {
            java.nio.charset.Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        return String(bytes, Charsets.UTF_8)
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val probe = bytes.take(1024).toByteArray()
        if (probe.isEmpty()) return false
        // PDF / zip 等魔数
        if (probe.size >= 4 && probe[0] == 0x25.toByte() && probe[1] == 0x50.toByte()) return true
        if (probe.size >= 2 && probe[0] == 0x50.toByte() && probe[1] == 0x4B.toByte()) return true
        return probe.any { it == 0x00.toByte() }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val WORKSPACE_DIR = "workspace"
        private const val MAX_READ_BYTES = 300_000
        private const val MAX_WRITE_CHARS = 200_000

        /**
         * MediaStore.Files 文档集合的卷名。
         *
         * 不用 `MediaStore.VOLUME_EXTERNAL`：该常量是 API 29 新增，虽然在 Kotlin 里会被
         * 内联成字面量 "external"（不会崩），但版本语义容易误读，这里直接使用等价字面量。
         * `MediaStore.Files.getContentUri(String)` 自 API 11 起可用，所有手表版本都安全。
         */
        private const val EXTERNAL_VOLUME = "external"

        private fun documentsCollectionUri(): android.net.Uri =
            MediaStore.Files.getContentUri(EXTERNAL_VOLUME)

        private val PLAIN_TEXT_EXTENSIONS = listOf(
            ".txt", ".md", ".json", ".csv", ".tsv", ".log", ".xml", ".html", ".htm",
            ".yml", ".yaml", ".ini", ".conf", ".properties",
        )

        /**
         * 读取相册/文档需要申请的运行时权限（按 API 级别给出）。
         *
         * Android 14(API 34)+ 支持"仅选中的照片"：系统会把 READ_MEDIA_IMAGES 降级为
         * READ_MEDIA_VISUAL_USER_SELECTED，所以两个都要申请，任一授予即视为可用。
         */
        fun readPermissions(): List<String> = when {
            Build.VERSION.SDK_INT >= 34 -> listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )

            Build.VERSION.SDK_INT >= 33 -> listOf(android.Manifest.permission.READ_MEDIA_IMAGES)
            else -> listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun hasReadPermission(context: Context): Boolean = runCatching {
            readPermissions().any { permission ->
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
        }.getOrDefault(true)

        internal fun errorResult(code: String, message: String): JSONObject =
            JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
    }
}

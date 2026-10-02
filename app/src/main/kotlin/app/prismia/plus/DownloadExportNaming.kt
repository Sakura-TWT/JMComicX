package app.prismia.plus

internal enum class DownloadExportFormat(val label: String) {
    ZIP("原图 ZIP · 按漫画和章节分目录"),
    PDF("PDF · 每章一份，多章自动打包"),
}

internal fun downloadExportName(value: String, fallback: String): String = value
    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
    .trim(' ', '.')
    .take(80)
    .ifBlank { fallback }

internal fun downloadChapterExportName(index: Int, title: String): String =
    "${(index + 1).toString().padStart(4, '0')}_${downloadExportName(title, "章节")}"

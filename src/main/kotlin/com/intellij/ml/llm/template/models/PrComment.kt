package com.intellij.ml.llm.template.models

enum class PrCommentStatus {
    OPEN,
    RESOLVED,
    OUTDATED,
    REQUESTED_CHANGES
}

data class CodeSnippet(
    val text: String,
    val language: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
)

data class PrComment(
    val id: Long,
    val author: String,
    val body: String,
    val filePath: String? = null,
    val line: Int? = null,

    // novo
    val status: PrCommentStatus = PrCommentStatus.OPEN,
    val codeSnippet: CodeSnippet? = null,
    val isGrouped: Boolean = false,
) {
    override fun toString(): String {
//        val loc = if (filePath != null && line != null) " • $filePath:$line" else ""
//        val st = " • ${status.name.lowercase()}"
        return author
    }
}

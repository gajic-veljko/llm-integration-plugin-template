package com.intellij.ml.llm.template.github

enum class PrCommentStatus {
    OPEN,
    RESOLVED,
    OUTDATED
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
) {
    override fun toString(): String {
        val loc = if (filePath != null && line != null) " • $filePath:$line" else ""
        val st = " • ${status.name.lowercase()}"
        return "#$id • $author$loc$st"
    }
}

package com.intellij.ml.llm.template.models

data class PrComment (
    val id: Long,
    val author: String,
    val body: String,
    val filePath: String? = null,
    val line: Int? = null,
) {
    override fun toString(): String {
        val loc = if (filePath != null && line != null) " • $filePath:$line" else ""
        return "#$id • $author$loc"
    }
}
package com.intellij.ml.llm.template.ui

enum class CommentFilter {
    ALL { override fun toString() = "All Comments" },
    INLINE { override fun toString() = "Inline Comments" },
    DISCUSSION { override fun toString() = "Discussion Comments" }
}
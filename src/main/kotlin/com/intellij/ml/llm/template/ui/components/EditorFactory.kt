package com.intellij.ml.llm.template.ui.components

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI

object EditorFactory {
    fun createCodeEditor(project: Project, code: String, filePathHint: String?): EditorTextField {
        val fileType = if (filePathHint != null) {
            val ext = filePathHint.substringAfterLast('.', "txt")
            FileTypeManager.getInstance().getFileTypeByExtension(ext)
        } else {
            FileTypeManager.getInstance().getFileTypeByExtension("txt")
        }

        return EditorTextField(code, project, fileType).apply {
            setOneLineMode(false)
            border = JBUI.Borders.empty(4)

            addSettingsProvider { editor ->
                (editor as? EditorEx)?.apply {
                    isViewer = true
                    settings.isLineNumbersShown = true
                    settings.isWhitespacesShown = false
                    settings.isFoldingOutlineShown = true
                    colorsScheme = EditorColorsManager.getInstance().globalScheme
                    setVerticalScrollbarVisible(true)
                    setHorizontalScrollbarVisible(true)
                }
            }
        }
    }
}

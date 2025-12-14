package com.example.prresolver

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import javax.swing.JPanel

class PrResolverToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val textArea = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            text = "Zdravo! Ovde će ići PR komentari / rezultat rezolucije.\n"
        }

        val panel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(textArea), BorderLayout.CENTER)
        }

        val content = ContentFactory.getInstance().createContent(panel, /*displayName*/ null, /*isLockable*/ false)
        toolWindow.contentManager.addContent(content)

        // Primer: kasnije možeš da pozoveš textArea.append("...") kad stignu komentari
    }
}

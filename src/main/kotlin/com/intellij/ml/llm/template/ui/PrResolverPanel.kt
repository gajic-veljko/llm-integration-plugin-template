package com.intellij.ml.llm.template.ui

import com.intellij.ml.llm.template.models.PrComment
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class PrResolverPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val repoField = JBTextField("owner/repo")
    private val prField = JBTextField("123") // za sada string, posle validacija
    private val fetchButton = JButton("Fetch comments")
    private val resolveButton = JButton("Resolve selected")
    private val previewButton = JButton("Preview diff")

    private val listModel = CollectionListModel<PrComment>()
    private val commentsList = JBList(listModel)

    private val detailsArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        // TOP bar
        val top = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Repo:"))
            repoField.columns = 22
            add(repoField)

            add(JBLabel("PR #:"))
            prField.columns = 6
            add(prField)

            add(fetchButton)
            add(resolveButton)
            add(previewButton)
        }
        add(top, BorderLayout.NORTH)

        // Lista (levo)
        commentsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        commentsList.emptyText.text = "No comments loaded"
        commentsList.cellRenderer = object : ColoredListCellRenderer<PrComment>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out PrComment>,
                value: PrComment,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                append(value.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(value.body.take(80), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }

        val left = JBScrollPane(commentsList)
        val right = JBScrollPane(detailsArea)

        // Split view (levo lista, desno detalji)
        val splitter = JBSplitter(false, 0.35f).apply {
            firstComponent = left
            secondComponent = right
        }
        add(splitter, BorderLayout.CENTER)

        // Dugmad disabled dok nema selekcije
        resolveButton.isEnabled = false
        previewButton.isEnabled = false

        // Event: selekcija u listi
        commentsList.addListSelectionListener {
            val selected = commentsList.selectedValue
            if (selected == null) {
                detailsArea.text = ""
                resolveButton.isEnabled = false
                previewButton.isEnabled = false
            } else {
                detailsArea.text = buildString {
                    appendLine("Author: ${selected.author}")
                    if (selected.filePath != null && selected.line != null) {
                        appendLine("Location: ${selected.filePath}:${selected.line}")
                    }
                    appendLine()
                    append(selected.body)
                }
                resolveButton.isEnabled = true
                previewButton.isEnabled = true
            }
        }

        // Za sada: dummy data (da UI radi odmah)
        loadDummyComments()

        // Klikovi: trenutno samo placeholder (posle ćemo ubaciti fetch + AI)
        fetchButton.addActionListener {
            // TODO: u sledećem koraku GitHub fetch (u background thread-u)
            loadDummyComments()
        }
        resolveButton.addActionListener {
            // TODO: u sledećem koraku OpenAI poziv + dobijanje patch-a
        }
        previewButton.addActionListener {
            // TODO: u sledećem koraku Diff viewer
        }
    }

    private fun loadDummyComments() {
        listModel.removeAll()
        listModel.add(
            PrComment(
                id = 1,
                author = "octocat",
                body = "Please rename this variable to be more descriptive.",
                filePath = "src/main/kotlin/App.kt",
                line = 42
            )
        )
        listModel.add(
            PrComment(
                id = 2,
                author = "reviewer",
                body = "Potential null pointer here. Add null check or requireNotNull().",
                filePath = "src/main/kotlin/Service.kt",
                line = 10
            )
        )
        listModel.add(
            PrComment(
                id = 3,
                author = "maintainer",
                body = "This function is too long; can we extract helper methods?",
                filePath = null,
                line = null
            )
        )
        commentsList.setSelectedIndex(0)
    }
}
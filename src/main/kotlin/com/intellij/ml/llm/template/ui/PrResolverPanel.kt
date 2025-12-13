package com.intellij.ml.llm.template.ui

import com.intellij.ml.llm.template.models.PrComment
import com.intellij.ml.llm.template.models.PrCommentStatus
import com.intellij.ml.llm.template.services.*
import com.intellij.ml.llm.template.settings.LLMSettingsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.*
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.JButton
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.ListSelectionModel

enum class CommentFilter {
    ALL {
        override fun toString() = "All Comments"
    },
    INLINE {
        override fun toString() = "Inline Comments"
    },
    DISCUSSION {
        override fun toString() = "Discussion Comments"
    }
}

class PrResolverPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(PrResolverPanel::class.java)

    private val repoField = JBTextField("cupacdj/JDBC-project")
    private val prField = JBTextField("1") // za sada string, posle validacija
    private val fetchButton = JButton("Fetch comments")
    private val resolveButton = JButton("Resolve selected")
    private val previewButton = JButton("Preview diff")
    private val filterComboBox = JComboBox(CommentFilter.values())

    private val listModel = CollectionListModel<PrComment>()
    private val commentsList = JBList(listModel)

    // Store all comments for filtering
    private var allComments = mutableListOf<PrComment>()

    private val detailsArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    // Store fetched data
    private var currentSnapshot: PrSnapshot? = null
    private var currentAnalysis: String? = null

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

            // Add separator
            add(JSeparator(SwingConstants.VERTICAL))

            add(JBLabel("Filter:"))
            add(filterComboBox)

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
                    } else {
                        appendLine("Type: Discussion Comment")
                    }
                    appendLine()
                    appendLine("Comment:")
                    appendLine(selected.body)

                    // Show code snippet if available
                    if (selected.codeSnippet != null) {
                        appendLine()
                        appendLine("=".repeat(60))
                        appendLine("Code Snippet:")
                        appendLine("=".repeat(60))
                        appendLine(selected.codeSnippet.text)
                        appendLine("=".repeat(60))
                    }

                    // Show note for discussion comments
                    if (selected.filePath == null) {
                        appendLine()
                        appendLine("Note: AI resolution is only available for inline review comments with code context.")
                    }
                }
                // Enable resolve button only for inline comments (those with filePath)
                resolveButton.isEnabled = selected.filePath != null
                previewButton.isEnabled = true
            }
        }

        // Event: filter combo box change
        filterComboBox.addActionListener {
            applyFilter()
        }

        // Za sada: dummy data (da UI radi odmah)
//        loadDummyComments()

        // Klikovi: currentno samo placeholder (posle ćemo ubaciti fetch + AI)
        fetchButton.addActionListener {
            val repoText = repoField.text.trim()
            val prText = prField.text.trim()

            // Parse repo
            val parts = repoText.split("/")
            if (parts.size != 2) {
                JOptionPane.showMessageDialog(this, "Invalid repo format. Use: owner/repo", "Error", JOptionPane.ERROR_MESSAGE)
                return@addActionListener
            }
            val owner = parts[0]
            val repo = parts[1]

            // Parse PR number
            val prNumber = prText.toIntOrNull()
            if (prNumber == null || prNumber <= 0) {
                JOptionPane.showMessageDialog(this, "Invalid PR number", "Error", JOptionPane.ERROR_MESSAGE)
                return@addActionListener
            }

            // Get GitHub token from settings (optional for public repos)
            val githubToken = System.getenv("GITHUB_TOKEN") // You can also add this to settings

            // Run in background
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching PR Comments from GitHub", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Fetching PR #$prNumber from $owner/$repo..."

                    try {
                        val fetcher = GitHubService(githubToken)
                        val snapshot = fetcher.fetchPrSnapshot(owner, repo, prNumber, snippetContextLines = 3)

                        // Store snapshot
                        currentSnapshot = snapshot

                        // Update UI on EDT
                        ApplicationManager.getApplication().invokeLater {
                            loadCommentsFromSnapshot(snapshot)
                            JOptionPane.showMessageDialog(
                                this@PrResolverPanel,
                                "Fetched ${snapshot.inlineComments.size} inline comments and ${snapshot.discussionComments.size} discussion comments",
                                "Success",
                                JOptionPane.INFORMATION_MESSAGE
                            )
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to fetch PR comments", e)
                        ApplicationManager.getApplication().invokeLater {
                            JOptionPane.showMessageDialog(
                                this@PrResolverPanel,
                                "Failed to fetch: ${e.message}",
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                            )
                        }
                    }
                }
            })
        }
        resolveButton.addActionListener {
            val snapshot = currentSnapshot
            if (snapshot == null) {
                JOptionPane.showMessageDialog(this, "Please fetch PR comments first", "Error", JOptionPane.ERROR_MESSAGE)
                return@addActionListener
            }

            val selectedComment = commentsList.selectedValue
            if (selectedComment == null) {
                JOptionPane.showMessageDialog(this, "Please select a comment", "Error", JOptionPane.ERROR_MESSAGE)
                return@addActionListener
            }

            // Double-check it's an inline comment (button should already be disabled for discussion comments)
            if (selectedComment.filePath == null) {
                JOptionPane.showMessageDialog(this, "AI resolution is only available for inline review comments", "Error", JOptionPane.ERROR_MESSAGE)
                return@addActionListener
            }

            // Get OpenAI API key from settings
            val settingsManager = LLMSettingsManager.getInstance()
            val openAiKey = settingsManager.getOpenAiKey()

            if (openAiKey.isBlank()) {
                JOptionPane.showMessageDialog(
                    this,
                    "Please configure OpenAI API key in Settings",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
                return@addActionListener
            }

            // Run in background
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Analyzing PR with AI", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Calling OpenAI API..."

                    try {
                        val openAi = OpenAiService(openAiKey)
                        val analysis = openAi.analyzePrSnapshot(
                            snapshot = snapshot,
                            model = "gpt-4o",
                            userPrompt = "Focus on resolving this comment: '${selectedComment.body}' at ${selectedComment.filePath}:${selectedComment.line}. Give me exact required changes with file and line ranges."
                        )

                        // Store analysis
                        currentAnalysis = analysis

                        // Update UI on EDT
                        ApplicationManager.getApplication().invokeLater {
                            detailsArea.text = buildString {
                                appendLine("=== AI ANALYSIS ===")
                                appendLine()
                                appendLine("Comment: ${selectedComment.body}")
                                appendLine("Author: ${selectedComment.author}")
                                appendLine("Location: ${selectedComment.filePath}:${selectedComment.line}")

                                // Show code snippet if available
                                if (selectedComment.codeSnippet != null) {
                                    appendLine()
                                    appendLine("=".repeat(60))
                                    appendLine("Code Snippet:")
                                    appendLine("=".repeat(60))
                                    appendLine(selectedComment.codeSnippet.text)
                                    appendLine("=".repeat(60))
                                }

                                appendLine()
                                appendLine("=== Suggested Resolution ===")
                                appendLine()
                                append(analysis)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Failed to analyze PR with OpenAI", e)
                        ApplicationManager.getApplication().invokeLater {
                            JOptionPane.showMessageDialog(
                                this@PrResolverPanel,
                                "Failed to analyze: ${e.message}",
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                            )
                        }
                    }
                }
            })
        }
        previewButton.addActionListener {
            // TODO: u sledećem koraku Diff viewer
        }
    }

    private fun loadCommentsFromSnapshot(snapshot: PrSnapshot) {
        allComments.clear()

        // Add discussion comments first
        snapshot.discussionComments.forEach { comment ->
            allComments.add(
                PrComment(
                    id = comment.id,
                    author = comment.user ?: "unknown",
                    body = comment.body ?: "",
                    filePath = null,
                    line = null,
                    status = PrCommentStatus.OPEN
                )
            )
        }

        // Convert inline comments to PrComment objects
        val inlineCommentsList = snapshot.inlineComments.map { comment ->
            PrComment(
                id = comment.id,
                author = comment.user ?: "unknown",
                body = comment.body ?: "",
                filePath = comment.path,
                line = comment.line,
                status = PrCommentStatus.OPEN,
                codeSnippet = if (comment.snippet != null) {
                    com.intellij.ml.llm.template.models.CodeSnippet(
                        text = comment.snippet,
                        startLine = comment.startLine,
                        endLine = comment.line
                    )
                } else null
            )
        }

        // Group inline comments by their code snippet text, then sort by file path and line
        val sortedInlineComments = inlineCommentsList
            .sortedWith(compareBy(
                { it.filePath ?: "" },
                { it.codeSnippet?.text ?: "" },
                { it.line ?: Int.MAX_VALUE }
            ))

        // Add visual grouping indicator by prepending "  ↳ " to comments with same snippet
        var previousSnippet: String? = null
        val groupedInlineComments = sortedInlineComments.map { comment ->
            val currentSnippet = comment.codeSnippet?.text
            val isGrouped = currentSnippet != null && currentSnippet == previousSnippet
            previousSnippet = currentSnippet

            if (isGrouped && currentSnippet?.isNotEmpty() == true) {
                // Add indent prefix for grouped comments
                comment.copy(author = "  ↳ ${comment.author}")
            } else {
                comment
            }
        }

        // Add grouped inline comments
        allComments.addAll(groupedInlineComments)

        // Apply initial filter
        applyFilter()
    }

    private fun applyFilter() {
        listModel.removeAll()

        val filter = filterComboBox.selectedItem as? CommentFilter ?: CommentFilter.ALL

        val filteredComments = when (filter) {
            CommentFilter.ALL -> allComments
            CommentFilter.INLINE -> allComments.filter { it.filePath != null }
            CommentFilter.DISCUSSION -> allComments.filter { it.filePath == null }
        }

        filteredComments.forEach { listModel.add(it) }

        // Select first item if available
        if (!listModel.isEmpty) {
            commentsList.setSelectedIndex(0)
        } else {
            detailsArea.text = "No comments match the selected filter."
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
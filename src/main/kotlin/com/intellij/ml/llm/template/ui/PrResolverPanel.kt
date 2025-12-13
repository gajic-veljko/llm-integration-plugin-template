package com.intellij.ml.llm.template.ui

import com.intellij.icons.AllIcons
import com.intellij.ml.llm.template.models.PrComment
import com.intellij.ml.llm.template.models.PrCommentStatus
import com.intellij.ml.llm.template.services.*
import com.intellij.ml.llm.template.settings.LLMSettingsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.border.CompoundBorder
import kotlin.math.max

enum class CommentFilter {
    ALL { override fun toString() = "All Comments" },
    INLINE { override fun toString() = "Inline Comments" },
    DISCUSSION { override fun toString() = "Discussion Comments" }
}

class PrResolverPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(PrResolverPanel::class.java)

    companion object {
        val INLINE_BADGE_COLOR = JBColor(Color(0x4CAF50), Color(0x81C784))
        val DISCUSSION_BADGE_COLOR = JBColor(Color(0x2196F3), Color(0x64B5F6))
        val AUTHOR_COLOR = JBColor(Color(0x9C27B0), Color(0xCE93D8))
        val LOCATION_COLOR = JBColor(Color(0xFF9800), Color(0xFFB74D))
        val HEADER_BG = JBColor(Color(0xF5F5F5), Color(0x3C3F41))
        val CARD_BORDER = JBColor(Color(0xE0E0E0), Color(0x515151))
        val AI_SECTION_BG = JBColor(Color(0xE3F2FD), Color(0x1E3A5F))

        // DARKER resize visuals
        val DIVIDER_COLOR = JBColor(Color(0x909090), Color(0x2B2B2B))
        val SECTION_BORDER = JBColor(Color(0xB0B0B0), Color(0x3A3A3A))
    }

    private val repoField = JBTextField("cupacdj/JDBC-project").apply {
        columns = 22
        border = JBUI.Borders.empty(4)
        minimumSize = Dimension(160, preferredSize.height)
    }
    private val prField = JBTextField("1").apply {
        columns = 6
        border = JBUI.Borders.empty(4)
        minimumSize = Dimension(60, preferredSize.height)
    }

    private val fetchButton = createStyledButton("Fetch Comments", AllIcons.Actions.Refresh)
    private val resolveButton = createStyledButton("Resolve with AI", AllIcons.Actions.Lightning)
    private val filterComboBox = JComboBox(CommentFilter.values()).apply {
        border = JBUI.Borders.empty(2)
    }

    private val listModel = CollectionListModel<PrComment>()
    private val commentsList = JBList(listModel)
    private var allComments = mutableListOf<PrComment>()

    // ---- Right panel UI ----
    private val detailsPanel = JPanel(BorderLayout())

    // Comment text
    private val commentBodyArea = createStyledTextArea()

    // Code context
    private val codeContextWrapper = JPanel(BorderLayout())
    private var codeEditorField: EditorTextField? = null
    private val codeLineInfoLabel = JLabel("", AllIcons.General.Information, SwingConstants.LEFT).apply {
        foreground = LOCATION_COLOR
        font = font.deriveFont(11f)
        border = JBUI.Borders.empty(4, 0, 0, 0)
        isVisible = false
    }

    // AI output (hidden until resolve)
    private val aiTextArea = createStyledTextArea().apply { background = AI_SECTION_BG }
    private var aiCodeEditorField: EditorTextField? = null

    // Splitters (drag to resize)
    private lateinit var topSplitter: JBSplitter      // comment <-> code
    private lateinit var aiSplitter: JBSplitter       // ai text <-> ai code
    private lateinit var detailsSplitter: JBSplitter  // top <-> ai

    private lateinit var commentSection: JPanel
    private lateinit var codeSection: JPanel
    private lateinit var aiSectionContainer: JComponent // will be either emptyAiPanel or aiSplitter


    // Placeholder when AI hidden
    private val emptyAiPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(
            JLabel("Press “Resolve with AI” to show the AI solution.", AllIcons.General.Information, SwingConstants.LEFT)
                .apply {
                    border = JBUI.Borders.empty(8)
                    foreground = JBColor.GRAY
                },
            BorderLayout.NORTH
        )
    }

    // Fullscreen button (top-left of right panel)
    private val fullScreenButton = JButton("Fullscreen", AllIcons.Actions.Expandall).apply {
        toolTipText = "Open selected comment / code / AI result in a fullscreen window"
        isFocusPainted = false
        isOpaque = true
        border = CompoundBorder(
            JBUI.Borders.customLine(SECTION_BORDER, 1),
            JBUI.Borders.empty(4, 10)
        )
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    // Data
    private var currentSnapshot: PrSnapshot? = null
    private var currentAnalysis: String? = null

    init {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8)

        add(createTopBarWrapping(), BorderLayout.NORTH)

        setupCommentsList()

        val left = JBScrollPane(commentsList).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(CARD_BORDER, 1),
                JBUI.Borders.empty(4)
            )
        }

        setupDetailsPanel()

        val right = detailsPanel.apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(CARD_BORDER, 1),
                JBUI.Borders.empty(4)
            )
        }

        val mainSplitter = JBSplitter(false, 0.35f).apply {
            firstComponent = left
            secondComponent = right
            dividerWidth = 6
            border = JBUI.Borders.customLine(DIVIDER_COLOR, 1)
            background = DIVIDER_COLOR
        }
        add(mainSplitter, BorderLayout.CENTER)

        resolveButton.isEnabled = false

        setupListeners()
        hideAiSection()
    }

    // ---- TOP BAR (wraps on resize, no overlap) ----
    private fun createTopBarWrapping(): JPanel {
        return JPanel(WrapLayout(FlowLayout.LEFT, 8, 6)).apply {
            background = HEADER_BG
            border = CompoundBorder(
                JBUI.Borders.customLine(CARD_BORDER, 0, 0, 1, 0),
                JBUI.Borders.empty(8, 12)
            )

            add(createLabel("Repository:", AllIcons.Vcs.Vendors.Github))
            add(repoField)
            add(createLabel("PR #:", AllIcons.Vcs.Merge))
            add(prField)
            add(fetchButton)

            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(1, 24) })

            add(createLabel("Filter:", AllIcons.General.Filter))
            add(filterComboBox)
            add(resolveButton)
        }
    }

    private fun createStyledButton(text: String, icon: Icon): JButton {
        return JButton(text, icon).apply {
            isFocusPainted = false
            border = JBUI.Borders.empty(6, 12)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    private fun createLabel(text: String, icon: Icon? = null): JLabel {
        return JLabel(text, icon, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.BOLD)
            foreground = UIUtil.getLabelForeground()
        }
    }

    private fun createStyledTextArea(): JBTextArea {
        return JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(13f)
            border = JBUI.Borders.empty(8)
            background = UIUtil.getPanelBackground()
        }
    }

    private fun setupCommentsList() {
        commentsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        commentsList.emptyText.text = "No comments loaded. Click 'Fetch Comments' to start."
        commentsList.fixedCellHeight = 60
        commentsList.border = JBUI.Borders.empty(4)

        commentsList.cellRenderer = object : ColoredListCellRenderer<PrComment>() {
            override fun customizeCellRenderer(
                list: JList<out PrComment>,
                value: PrComment,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                border = JBUI.Borders.empty(8, 4)

                icon = if (value.filePath != null) AllIcons.Nodes.Folder else AllIcons.Toolwindows.ToolWindowMessages

                val badgeText = if (value.filePath != null) "INLINE" else "DISCUSSION"
                val badgeColor = if (value.filePath != null) INLINE_BADGE_COLOR else DISCUSSION_BADGE_COLOR

                append("[$badgeText] ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, badgeColor))
                append("@${value.author}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, AUTHOR_COLOR))

                if (value.filePath != null) {
                    append(" • ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(
                        value.filePath!!.substringAfterLast('/'),
                        SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, LOCATION_COLOR)
                    )
                    if (value.line != null) {
                        append(":${value.line}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, LOCATION_COLOR))
                    }
                }

                append("\n", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val preview = value.body.take(100).replace("\n", " ")
                append(preview, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                if (value.body.length > 100) append("...", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }

    // ---- DETAILS PANEL (no top author/header at all) ----
    private fun setupDetailsPanel() {
        detailsPanel.background = UIUtil.getPanelBackground()
        detailsPanel.border = JBUI.Borders.empty(8)

        // Small toolbar on the right panel (top-left) with fullscreen
        val detailsToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 6, 6, 6)
            add(fullScreenButton)
        }

        // Comment section
        commentSection = createSection("Comment", AllIcons.Nodes.Tag).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(SECTION_BORDER, 1),
                JBUI.Borders.empty(6)
            )
            add(JBScrollPane(commentBodyArea).apply {
                border = JBUI.Borders.customLine(CARD_BORDER, 1)
            }, BorderLayout.CENTER)
        }

        // Code section
        codeSection = createSection("Code Context", AllIcons.Nodes.Class).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(SECTION_BORDER, 1),
                JBUI.Borders.empty(6)
            )
            add(codeContextWrapper, BorderLayout.CENTER)
            add(codeLineInfoLabel, BorderLayout.SOUTH)
        }
        setCodeContext(null, null, null)

        // Resizable: Comment <-> Code
        topSplitter = JBSplitter(true, 0.52f).apply {
            firstComponent = commentSection
            secondComponent = codeSection
            dividerWidth = 8
            border = JBUI.Borders.customLine(DIVIDER_COLOR, 1)
            background = DIVIDER_COLOR
        }

        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(detailsToolbar, BorderLayout.NORTH)
            add(topSplitter, BorderLayout.CENTER)
        }

        // AI text panel
        val aiTextPanel = createSection("AI Analysis", AllIcons.Actions.Lightning).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(SECTION_BORDER, 1),
                JBUI.Borders.empty(6)
            )

            val aiInnerPanel = JPanel(BorderLayout()).apply {
                background = AI_SECTION_BG
                border = CompoundBorder(
                    JBUI.Borders.customLine(JBColor(Color(0x1976D2), Color(0x42A5F5)), 2),
                    JBUI.Borders.empty(8)
                )

                val aiHeader = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                    background = AI_SECTION_BG
                    add(JLabel(AllIcons.General.Information))
                    add(JLabel("AI-Generated Resolution").apply {
                        font = font.deriveFont(Font.BOLD, 14f)
                        foreground = JBColor(Color(0x1565C0), Color(0x90CAF9))
                    })
                }
                add(aiHeader, BorderLayout.NORTH)

                add(JBScrollPane(aiTextArea).apply {
                    border = JBUI.Borders.empty()
                    background = AI_SECTION_BG
                }, BorderLayout.CENTER)
            }

            add(aiInnerPanel, BorderLayout.CENTER)
        }

        // AI code panel
        val aiCodePanel = createSection("AI Proposed Code", AllIcons.FileTypes.Text).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(SECTION_BORDER, 1),
                JBUI.Borders.empty(6)
            )
            add(JLabel("No code block extracted.", SwingConstants.LEFT).apply {
                border = JBUI.Borders.empty(6)
                foreground = JBColor.GRAY
            }, BorderLayout.CENTER)
        }

        // Resizable: AI text <-> AI code
        aiSplitter = JBSplitter(true, 0.6f).apply {
            firstComponent = aiTextPanel
            secondComponent = aiCodePanel
            dividerWidth = 8
            border = JBUI.Borders.customLine(DIVIDER_COLOR, 1)
            background = DIVIDER_COLOR
        }

        // Resizable: top <-> AI
        detailsSplitter = JBSplitter(true, 0.72f).apply {
            firstComponent = topPanel
            secondComponent = emptyAiPanel
            dividerWidth = 8
            border = JBUI.Borders.customLine(DIVIDER_COLOR, 1)
            background = DIVIDER_COLOR
        }

        detailsPanel.removeAll()
        detailsPanel.add(detailsSplitter, BorderLayout.CENTER)
    }

    private fun createSection(title: String, icon: Icon): JPanel {
        return JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            val titleLabel = JLabel(title, icon, SwingConstants.LEFT).apply {
                font = font.deriveFont(Font.BOLD, 14f)
                border = JBUI.Borders.empty(0, 0, 4, 0)
            }
            add(titleLabel, BorderLayout.NORTH)
        }
    }

    private fun createCodeEditor(code: String, filePathHint: String?): EditorTextField {
        val fileType = if (filePathHint != null) {
            val extension = filePathHint.substringAfterLast('.', "txt")
            FileTypeManager.getInstance().getFileTypeByExtension(extension)
        } else {
            FileTypeManager.getInstance().getFileTypeByExtension("txt")
        }

        return EditorTextField(code, project, fileType).apply {
            setOneLineMode(false)
            border = CompoundBorder(
                JBUI.Borders.customLine(CARD_BORDER, 1),
                JBUI.Borders.empty(4)
            )

            addSettingsProvider { editor ->
                (editor as? EditorEx)?.apply {
                    isViewer = true
                    settings.isLineNumbersShown = true
                    settings.isWhitespacesShown = false
                    settings.isFoldingOutlineShown = true
                    colorsScheme = EditorColorsManager.getInstance().globalScheme
                    setHorizontalScrollbarVisible(true)
                    setVerticalScrollbarVisible(true)
                }
            }
        }
    }

    private fun setupListeners() {
        commentsList.addListSelectionListener {
            val selected = commentsList.selectedValue
            if (selected == null) clearDetails() else showCommentDetails(selected)
        }

        filterComboBox.addActionListener { applyFilter() }
        fetchButton.addActionListener { fetchPrComments() }
        resolveButton.addActionListener { resolveWithAi() }

        fullScreenButton.addActionListener {
            val selected = commentsList.selectedValue
            val dialog = FullScreenViewerDialog(
                commentText = commentBodyArea.text,
                codeText = codeEditorField?.text ?: "",
                codeFileHint = selected?.filePath,
                aiText = aiTextArea.text,
                aiCodeText = aiCodeEditorField?.text ?: "",
                aiCodeFileHint = selected?.filePath
            )
            dialog.show()
        }
    }

    private fun clearDetails() {
        commentBodyArea.text = ""
        setCodeContext(null, null, null)
        hideAiSection()

        resolveButton.isEnabled = false

        detailsPanel.revalidate()
        detailsPanel.repaint()
    }

    private fun showCommentDetails(comment: PrComment) {
        commentBodyArea.text = comment.body

        val isInline = comment.filePath != null

        if (!isInline) {
            // Discussion comment: show only comment
            setCodeContext(null, null, null)
            showDiscussionLayout()
            return
        }

        // Inline comment: show comment + code, AI hidden until resolve button pressed
        showInlineLayout()

        if (comment.codeSnippet != null) {
            setCodeContext(
                code = comment.codeSnippet.text,
                filePathHint = comment.filePath,
                lineInfo = "Lines ${comment.codeSnippet.startLine} - ${comment.codeSnippet.endLine}"
            )
        } else {
            setCodeContext(null, null, null)
        }

        resolveButton.isEnabled = true
    }


    private fun setCodeContext(code: String?, filePathHint: String?, lineInfo: String?) {
        codeContextWrapper.removeAll()

        if (code.isNullOrBlank()) {
            codeContextWrapper.add(
                JLabel("No code context available for this comment.", SwingConstants.LEFT).apply {
                    border = JBUI.Borders.empty(6)
                    foreground = JBColor.GRAY
                },
                BorderLayout.CENTER
            )
            codeEditorField = null
            codeLineInfoLabel.isVisible = false
        } else {
            val editor = createCodeEditor(code, filePathHint)
            codeEditorField = editor
            codeContextWrapper.add(editor, BorderLayout.CENTER)
            codeLineInfoLabel.text = lineInfo ?: ""
            codeLineInfoLabel.isVisible = !lineInfo.isNullOrBlank()
        }

        codeContextWrapper.revalidate()
        codeContextWrapper.repaint()
    }

    // ---- AI show/hide ----
    private fun hideAiSection() {
        currentAnalysis = null
        aiTextArea.text = ""
        setAiCode(null, null)

        detailsSplitter.secondComponent = emptyAiPanel
        detailsSplitter.proportion = 1.0f
    }

    private fun showAiSection() {
        detailsSplitter.secondComponent = aiSplitter
        detailsSplitter.proportion = 0.72f
    }

    private fun setAiOutput(text: String, codeBlock: String?, codeFilePathHint: String?) {
        aiTextArea.text = text
        setAiCode(codeBlock, codeFilePathHint)
        showAiSection()
    }

    private fun setAiCode(codeBlock: String?, codeFilePathHint: String?) {
        val aiCodePanel = aiSplitter.secondComponent as? JPanel ?: return
        aiCodePanel.removeAll()

        if (codeBlock.isNullOrBlank()) {
            aiCodePanel.add(
                JLabel("No code block extracted.", SwingConstants.LEFT).apply {
                    border = JBUI.Borders.empty(6)
                    foreground = JBColor.GRAY
                },
                BorderLayout.CENTER
            )
            aiCodeEditorField = null
        } else {
            val editor = createCodeEditor(codeBlock, codeFilePathHint)
            aiCodeEditorField = editor
            aiCodePanel.add(editor, BorderLayout.CENTER)
        }

        aiCodePanel.revalidate()
        aiCodePanel.repaint()
    }

    private fun showDiscussionLayout() {
        // Only comment section visible
        topSplitter.firstComponent = commentSection
        topSplitter.secondComponent = JPanel() // empty
        topSplitter.proportion = 1.0f

        hideAiSection() // also ensures AI area is empty
        resolveButton.isEnabled = false

        detailsPanel.revalidate()
        detailsPanel.repaint()
    }

    private fun showInlineLayout() {
        // Comment + code section visible
        topSplitter.firstComponent = commentSection
        topSplitter.secondComponent = codeSection
        topSplitter.proportion = 0.52f

        // AI remains hidden until user presses resolve
        hideAiSection()

        detailsPanel.revalidate()
        detailsPanel.repaint()
    }

    private fun extractFirstCodeBlock(markdown: String): Pair<String?, String?> {
        val start = markdown.indexOf("```")
        if (start < 0) return null to null
        val langLineEnd = markdown.indexOf('\n', start + 3).takeIf { it >= 0 } ?: return null to null
        val lang = markdown.substring(start + 3, langLineEnd).trim().ifBlank { null }

        val end = markdown.indexOf("```", langLineEnd + 1)
        if (end < 0) return null to null
        val code = markdown.substring(langLineEnd + 1, end).trimEnd()
        return lang to code
    }

    // ---- Fetch / Resolve ----
    private fun fetchPrComments() {
        val repoText = repoField.text.trim()
        val prText = prField.text.trim()

        val parts = repoText.split("/")
        if (parts.size != 2) {
            showError("Invalid repo format. Use: owner/repo")
            return
        }
        val owner = parts[0]
        val repo = parts[1]

        val prNumber = prText.toIntOrNull()
        if (prNumber == null || prNumber <= 0) {
            showError("Invalid PR number")
            return
        }

        val githubToken = System.getenv("GITHUB_TOKEN")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching PR Comments", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Fetching PR #$prNumber from $owner/$repo..."

                try {
                    val fetcher = GitHubService(githubToken)
                    val snapshot = fetcher.fetchPrSnapshot(owner, repo, prNumber, snippetContextLines = 3)
                    currentSnapshot = snapshot

                    ApplicationManager.getApplication().invokeLater {
                        loadCommentsFromSnapshot(snapshot)
                        JOptionPane.showMessageDialog(
                            this@PrResolverPanel,
                            "Fetched ${snapshot.inlineComments.size} inline and ${snapshot.discussionComments.size} discussion comments",
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Failed to fetch PR comments", e)
                    ApplicationManager.getApplication().invokeLater {
                        showError("Failed to fetch: ${e.message}")
                    }
                }
            }
        })
    }

    private fun resolveWithAi() {
        val snapshot = currentSnapshot ?: run {
            showError("Please fetch PR comments first")
            return
        }

        val selectedComment = commentsList.selectedValue ?: run {
            showError("Please select a comment")
            return
        }

        if (selectedComment.filePath == null) {
            showError("AI resolution is only available for inline review comments")
            return
        }

        val openAiKey = LLMSettingsManager.getInstance().getOpenAiKey()
        if (openAiKey.isBlank()) {
            showError("Please configure OpenAI API key in Settings")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Analyzing with AI", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Calling OpenAI API..."

                try {
                    val openAi = OpenAiService(openAiKey)
                    val analysis = openAi.analyzePrSnapshot(
                        snapshot = snapshot,
                        model = "gpt-4o",
                        userPrompt =
                            "Focus on resolving this comment: '${selectedComment.body}' at ${selectedComment.filePath}:${selectedComment.line}. " +
                                    "Give me exact required changes with file and line ranges."
                    )

                    currentAnalysis = analysis

                    ApplicationManager.getApplication().invokeLater {
                        val (_, codeBlock) = extractFirstCodeBlock(analysis)
                        setAiOutput(
                            text = analysis,
                            codeBlock = codeBlock,
                            codeFilePathHint = selectedComment.filePath
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Failed to analyze PR with OpenAI", e)
                    ApplicationManager.getApplication().invokeLater {
                        showError("Failed to analyze: ${e.message}")
                    }
                }
            }
        })
    }

    private fun showError(message: String) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)
    }

    private fun loadCommentsFromSnapshot(snapshot: PrSnapshot) {
        allComments.clear()

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

        snapshot.inlineComments.forEach { comment ->
            allComments.add(
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
            )
        }

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

        if (!listModel.isEmpty) {
            commentsList.selectedIndex = 0
        } else {
            clearDetails()
            commentBodyArea.text = "No comments match the selected filter."
        }
    }

    // ---- Fullscreen viewer dialog ----
    private inner class FullScreenViewerDialog(

        private val commentText: String,
        private val codeText: String,
        private val codeFileHint: String?,
        private val aiText: String,
        private val aiCodeText: String,
        private val aiCodeFileHint: String?
    ) : DialogWrapper(this@PrResolverPanel.project) {

        init {
            title = "Fullscreen Viewer"
            setResizable(true)
            init()
        }

        override fun createCenterPanel(): JComponent {
            val tabs = JTabbedPane()

            tabs.addTab("Comment", JBScrollPane(JBTextArea(commentText).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(10)
            }))

            tabs.addTab("Code Context", createEditorPanel(this@PrResolverPanel.project, codeText, codeFileHint, emptyText = "No code context."))

            tabs.addTab("AI Analysis", JBScrollPane(JBTextArea(aiText).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(10)
            }))

            tabs.addTab("AI Code", createEditorPanel(this@PrResolverPanel.project, aiCodeText, aiCodeFileHint, emptyText = "No AI code extracted."))

            return tabs
        }

        private fun createEditorPanel(project: Project, text: String, fileHint: String?, emptyText: String): JComponent {
            if (text.isBlank()) {
                return JPanel(BorderLayout()).apply {
                    add(JLabel(emptyText).apply { border = JBUI.Borders.empty(12) }, BorderLayout.NORTH)
                }
            }

            val fileType = if (fileHint != null) {
                val ext = fileHint.substringAfterLast('.', "txt")
                FileTypeManager.getInstance().getFileTypeByExtension(ext)
            } else {
                FileTypeManager.getInstance().getFileTypeByExtension("txt")
            }

            val editor = EditorTextField(text, project, fileType).apply {
                setOneLineMode(false)
                addSettingsProvider { e ->
                    (e as? EditorEx)?.apply {
                        isViewer = true
                        settings.isLineNumbersShown = true
                        colorsScheme = EditorColorsManager.getInstance().globalScheme
                        setHorizontalScrollbarVisible(true)
                        setVerticalScrollbarVisible(true)
                    }
                }
            }

            return JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(8)
                add(editor, BorderLayout.CENTER)
            }
        }
    }



    /**
     * WrapLayout: FlowLayout that wraps items to the next line instead of overlapping.
     */
    private class WrapLayout(
        align: Int = FlowLayout.LEFT,
        hgap: Int = 5,
        vgap: Int = 5
    ) : FlowLayout(align, hgap, vgap) {

        override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)
        override fun minimumLayoutSize(target: Container): Dimension {
            val minimum = layoutSize(target, preferred = false)
            minimum.width -= hgap + 1
            return minimum
        }

        private fun layoutSize(target: Container, preferred: Boolean): Dimension {
            synchronized(target.treeLock) {
                val insets = target.insets
                val maxWidth = max(0, target.width - (insets.left + insets.right + hgap * 2))

                var width = 0
                var height = insets.top + vgap
                var rowWidth = 0
                var rowHeight = 0

                for (i in 0 until target.componentCount) {
                    val m = target.getComponent(i)
                    if (!m.isVisible) continue

                    val d = if (preferred) m.preferredSize else m.minimumSize
                    if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                        width = max(width, rowWidth)
                        height += rowHeight + vgap
                        rowWidth = 0
                        rowHeight = 0
                    }

                    rowWidth += d.width + hgap
                    rowHeight = max(rowHeight, d.height)
                }

                width = max(width, rowWidth)
                height += rowHeight + insets.bottom + vgap

                return Dimension(width + insets.left + insets.right + hgap * 2, height)
            }
        }
    }
}

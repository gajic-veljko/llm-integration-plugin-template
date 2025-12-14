package com.intellij.ml.llm.template.ui

import com.intellij.icons.AllIcons
import com.intellij.ml.llm.template.models.PrComment
import com.intellij.ml.llm.template.models.PrCommentStatus
import com.intellij.ml.llm.template.services.*
import com.intellij.ml.llm.template.settings.LLMSettingsManager
import com.intellij.ml.llm.template.ui.components.EditorFactory
import com.intellij.ml.llm.template.ui.components.LoadingPopup
import com.intellij.ml.llm.template.ui.components.SectionWithExpand
import com.intellij.ml.llm.template.ui.layout.WrapLayout
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.*
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.Timer
import javax.swing.*
import javax.swing.border.CompoundBorder

class PrResolverPanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        private val NEW_CODE_PATTERNS = listOf(
            "3. **NEW CODE**",
            "3. NEW CODE",
            "**NEW CODE**",
            "NEW CODE:",
            "new code",
            "### NEW CODE",
            "## NEW CODE"
        )

        fun formatRelativeTime(isoTimestamp: String?): String {
            if (isoTimestamp.isNullOrBlank()) return ""

            try {
                // Parse ISO 8601 timestamp (e.g., "2024-01-15T10:30:00Z")
                val timestamp = java.time.Instant.parse(isoTimestamp)
                val now = java.time.Instant.now()
                val duration = java.time.Duration.between(timestamp, now)

                return when {
                    duration.toDays() > 365 -> "${duration.toDays() / 365} years ago"
                    duration.toDays() > 30 -> "${duration.toDays() / 30} months ago"
                    duration.toDays() > 0 -> "${duration.toDays()} days ago"
                    duration.toHours() > 0 -> "${duration.toHours()} hours ago"
                    duration.toMinutes() > 0 -> "${duration.toMinutes()} minutes ago"
                    else -> "just now"
                }
            } catch (e: Exception) {
                return ""
            }
        }
    }

    private val logger = Logger.getInstance(PrResolverPanel::class.java)

    private val repoField = JBTextField("cupacdj/JDBC-project").apply { columns = 22; border = JBUI.Borders.empty(4) }
    private val prField = JBTextField("1").apply { columns = 6; border = JBUI.Borders.empty(4) }

    private val fetchButton = JButton("Fetch Comments", AllIcons.Actions.Refresh)
    private val resolveButton = JButton("Resolve with AI", AllIcons.Actions.Lightning)
    private val filterComboBox = JComboBox(CommentFilter.values())

    private val listModel = CollectionListModel<PrComment>()
    private val commentsList = JBList(listModel)
    private var allComments = mutableListOf<PrComment>()

    private val detailsPanel = JPanel(CardLayout())
    private val normalSectionsContainer = JPanel()
    private val normalScroll = JBScrollPane(normalSectionsContainer)

    private val expandedWrapper = JPanel(BorderLayout())
    private val expandedScroll = JBScrollPane(expandedWrapper)

    private var expandedSection: SectionWithExpand? = null
    private var expandedOriginalIndex: Int = -1

    private val commentBodyArea = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; border = JBUI.Borders.empty(8) }
    private val commentScroll = JBScrollPane(commentBodyArea).apply { border = JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1) }

    private val editButton = JButton("Edit", AllIcons.Actions.Edit)
    private val saveButton = JButton("Save", AllIcons.Actions.MenuSaveall).apply { isVisible = false }
    private val cancelButton = JButton("Cancel", AllIcons.Actions.Cancel).apply { isVisible = false }
    private var originalCommentText: String = ""
    private var isEditingComment: Boolean = false

    private val codeContextWrapper = JPanel(BorderLayout())
    private val copyCodeContextButton = JButton("Copy", AllIcons.Actions.Copy).apply {
        isVisible = false
        toolTipText = "Copy code to clipboard"
    }
    private var currentCodeContext: String? = null

    private val codeLineInfoLabel = JLabel("", AllIcons.General.Information, SwingConstants.LEFT).apply {
        foreground = PrUiTheme.LOCATION_COLOR
        font = font.deriveFont(11f)
        border = JBUI.Borders.empty(4, 0, 0, 0)
        isVisible = false
    }

    private val aiTextArea = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; border = JBUI.Borders.empty(8); background = PrUiTheme.AI_SECTION_BG }
    private val aiTextScroll = JBScrollPane(aiTextArea).apply { border = JBUI.Borders.empty(); background = PrUiTheme.AI_SECTION_BG }
    private var aiCodeEditorField: EditorTextField? = null

    private val copyAiCodeButton = JButton("Copy", AllIcons.Actions.Copy).apply {
        isVisible = false
        toolTipText = "Copy AI proposed code to clipboard"
    }
    private var currentAiCode: String? = null

    private lateinit var commentSection: SectionWithExpand
    private lateinit var codeSection: SectionWithExpand
    private lateinit var aiTextSection: SectionWithExpand
    private lateinit var aiCodeSection: SectionWithExpand

    private var currentSnapshot: PrSnapshot? = null

    init {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8)

        add(createTopBar(), BorderLayout.NORTH)
        setupCommentsList()

        val left = JBScrollPane(commentsList).apply {
            border = CompoundBorder(JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1), JBUI.Borders.empty(4))
        }

        setupRightPanel()

        val splitter = com.intellij.ui.JBSplitter(false, 0.35f).apply {
            firstComponent = left
            secondComponent = JPanel(BorderLayout()).apply {
                border = CompoundBorder(JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1), JBUI.Borders.empty(4))
                add(detailsPanel, BorderLayout.CENTER)
            }
            dividerWidth = 6
            border = JBUI.Borders.customLine(PrUiTheme.DIVIDER_COLOR, 1)
            background = PrUiTheme.DIVIDER_COLOR
        }

        add(splitter, BorderLayout.CENTER)

        resolveButton.isEnabled = false
        setupListeners()
        hideAi()
    }

    private fun createTopBar(): JPanel =
        JPanel(WrapLayout(FlowLayout.LEFT, 8, 6)).apply {
            background = PrUiTheme.HEADER_BG
            border = CompoundBorder(JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 0, 0, 1, 0), JBUI.Borders.empty(8, 12))

            add(JLabel("Repository:", AllIcons.Vcs.Vendors.Github, SwingConstants.LEFT).apply { font = font.deriveFont(Font.BOLD) })
            add(repoField)
            add(JLabel("PR #:", AllIcons.Vcs.Merge, SwingConstants.LEFT).apply { font = font.deriveFont(Font.BOLD) })
            add(prField)
            add(fetchButton)

            add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(1, 24) })

            add(JLabel("Filter:", AllIcons.General.Filter, SwingConstants.LEFT).apply { font = font.deriveFont(Font.BOLD) })
            add(filterComboBox)
            add(resolveButton)
        }

    private fun setupRightPanel() {
        normalSectionsContainer.isOpaque = false
        normalSectionsContainer.layout = BoxLayout(normalSectionsContainer, BoxLayout.Y_AXIS)
        normalSectionsContainer.border = JBUI.Borders.empty(6)

        normalScroll.border = JBUI.Borders.empty()
        normalScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        expandedScroll.border = JBUI.Borders.empty()
        expandedScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        // Sections
        commentSection = SectionWithExpand("Comment", AllIcons.Nodes.Tag) { toggleExpand(commentSection) }.apply { setFixedHeight(190) }
        commentSection.contentPanel.border = JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1)
        commentSection.contentPanel.add(commentScroll, BorderLayout.CENTER)

        // Add edit button panel at the bottom of comment section
        val editButtonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1, 0, 0, 0)
            add(editButton)
            add(saveButton)
            add(cancelButton)
        }
        commentSection.add(editButtonPanel, BorderLayout.SOUTH)

        codeSection = SectionWithExpand("Code Context", AllIcons.Nodes.Class) { toggleExpand(codeSection) }.apply { setFixedHeight(260) }
        codeSection.contentPanel.border = JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1)
        codeSection.contentPanel.add(codeContextWrapper, BorderLayout.CENTER)

        // Add copy button and info label at the bottom of code section
        val codeSectionBottom = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1, 0, 0, 0)
            add(codeLineInfoLabel, BorderLayout.WEST)
            add(copyCodeContextButton, BorderLayout.EAST)
        }
        codeSection.add(codeSectionBottom, BorderLayout.SOUTH)

        aiTextSection = SectionWithExpand("AI Analysis", AllIcons.Actions.Lightning) { toggleExpand(aiTextSection) }.apply { setFixedHeight(240) }
        aiTextSection.contentPanel.background = PrUiTheme.AI_SECTION_BG
        aiTextSection.contentPanel.add(aiTextScroll, BorderLayout.CENTER)

        aiCodeSection = SectionWithExpand("AI Proposed Code", AllIcons.FileTypes.Text) { toggleExpand(aiCodeSection) }.apply { setFixedHeight(260) }
        aiCodeSection.contentPanel.border = JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1)
        setAiCode(null, null)

        normalSectionsContainer.add(commentSection)
        normalSectionsContainer.add(codeSection)
        normalSectionsContainer.add(aiTextSection)
        normalSectionsContainer.add(aiCodeSection)

        detailsPanel.add(normalScroll, "NORMAL")
        detailsPanel.add(expandedScroll, "EXPANDED")
        (detailsPanel.layout as CardLayout).show(detailsPanel, "NORMAL")

        // Hide all sections initially
        hideAllSections()
    }

    private fun setupCommentsList() {
        commentsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        commentsList.emptyText.text = "No comments loaded. Click 'Fetch Comments' to start."
        commentsList.fixedCellHeight = 60
        commentsList.border = JBUI.Borders.empty(4)

        commentsList.cellRenderer = object : ColoredListCellRenderer<PrComment>() {
            override fun customizeCellRenderer(list: JList<out PrComment>, value: PrComment, index: Int, selected: Boolean, hasFocus: Boolean) {
                // Apply indentation for grouped comments
                val leftPadding = if (value.isGrouped) 32 else 4
                border = JBUI.Borders.empty(8, leftPadding, 8, 4)

                // Show icon only for first comment in group (non-grouped)
                icon = if (!value.isGrouped) {
                    if (value.filePath != null) AllIcons.Nodes.Folder else AllIcons.Toolwindows.ToolWindowMessages
                } else {
                    null
                }

                val badgeText = if (value.filePath != null) "INLINE" else "DISCUSSION"
                val badgeColor = if (value.filePath != null) PrUiTheme.INLINE_BADGE_COLOR else PrUiTheme.DISCUSSION_BADGE_COLOR

                // Add visual indicator for grouped comments
                if (value.isGrouped) {
                    append("└─ ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY))
                }

                append("[$badgeText] ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, badgeColor))
                append("@${value.author}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, PrUiTheme.AUTHOR_COLOR))

                if (value.filePath != null) {
                    append(" • ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(value.filePath!!.substringAfterLast('/'), SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, PrUiTheme.LOCATION_COLOR))
                    if (value.line != null) append(":${value.line}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, PrUiTheme.LOCATION_COLOR))
                }

                // Add timestamp
                val timeAgo = formatRelativeTime(value.createdAt)
                if (timeAgo.isNotEmpty()) {
                    append(" • ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(timeAgo, SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY))
                }

                append("\n", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val preview = value.body.take(100).replace("\n", " ")
                append(preview, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                if (value.body.length > 100) append("...", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
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

        editButton.addActionListener { enableCommentEditing() }
        saveButton.addActionListener { saveCommentEdit() }
        cancelButton.addActionListener { cancelCommentEdit() }

        copyCodeContextButton.addActionListener { copyToClipboard(currentCodeContext, copyCodeContextButton) }
        copyAiCodeButton.addActionListener { copyToClipboard(currentAiCode, copyAiCodeButton) }
    }

    private fun copyToClipboard(text: String?, button: JButton) {
        if (text.isNullOrBlank()) {
            showError("No code to copy")
            return
        }

        try {
            // Strip line numbers from the code before copying
            val cleanedText = stripLineNumbers(text)

            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(cleanedText), null)

            // Change button text and icon to "Copied" with double check
            val originalText = button.text
            val originalIcon = button.icon
            button.text = "Copied"
            button.icon = AllIcons.Actions.Checked

            // Reset button text and icon after 2 seconds
            Timer(2000) {
                button.text = originalText
                button.icon = originalIcon
            }.apply {
                isRepeats = false
                start()
            }
        } catch (e: Exception) {
            logger.error("Failed to copy to clipboard", e)
            showError("Failed to copy: ${e.message}")
        }
    }

    private fun stripLineNumbers(code: String): String {
        // Pattern matches line numbers with format: "   12345 | " or ">>  12345 | "
        // The pattern is: optional ">>" or spaces, followed by digits, followed by " | "
        return code.lines().joinToString("\n") { line ->
            // Remove line number prefix: "   12 | " or ">>  12 | "
            line.replace(Regex("^(>>)?\\s*\\d+\\s*\\|\\s?"), "")
        }
    }


    private fun enableCommentEditing() {
        originalCommentText = commentBodyArea.text
        commentBodyArea.isEditable = true
        commentBodyArea.background = JBColor.WHITE
        commentBodyArea.requestFocus()

        editButton.isVisible = false
        saveButton.isVisible = true
        cancelButton.isVisible = true
        isEditingComment = true

        // Keep resolve button enabled so users can send edited text directly
        val selected = commentsList.selectedValue
        resolveButton.isEnabled = selected?.filePath != null
    }

    private fun saveCommentEdit() {
        commentBodyArea.isEditable = false
        commentBodyArea.background = UIUtil.getPanelBackground()

        editButton.isVisible = true
        saveButton.isVisible = false
        cancelButton.isVisible = false
        isEditingComment = false

        val selected = commentsList.selectedValue
        resolveButton.isEnabled = selected?.filePath != null
    }

    private fun cancelCommentEdit() {
        commentBodyArea.text = originalCommentText
        commentBodyArea.isEditable = false
        commentBodyArea.background = UIUtil.getPanelBackground()

        editButton.isVisible = true
        saveButton.isVisible = false
        cancelButton.isVisible = false
        isEditingComment = false

        val selected = commentsList.selectedValue
        resolveButton.isEnabled = selected?.filePath != null
    }

    private fun toggleExpand(section: SectionWithExpand) {
        if (!section.isVisible) return
        if (expandedSection == section) collapseExpanded() else expandSection(section)
    }

    private fun expandSection(section: SectionWithExpand) {
        if (expandedSection != null) collapseExpanded()

        val parent = section.parent ?: return
        expandedOriginalIndex = parent.components.indexOf(section)

        parent.remove(section)
        parent.revalidate()
        parent.repaint()

        section.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        section.preferredSize = Dimension(0, 900)
        section.minimumSize = Dimension(0, 250)

        expandedWrapper.removeAll()
        expandedWrapper.add(section, BorderLayout.CENTER)
        (detailsPanel.layout as CardLayout).show(detailsPanel, "EXPANDED")

        section.setExpandedIcon(true)
        expandedSection = section
    }

    private fun collapseExpanded() {
        val section = expandedSection ?: return

        expandedWrapper.remove(section)

        // restore fixed heights
        when (section) {
            commentSection -> section.setFixedHeight(190)
            codeSection -> section.setFixedHeight(260)
            aiTextSection -> section.setFixedHeight(240)
            aiCodeSection -> section.setFixedHeight(260)
        }

        val idx = expandedOriginalIndex.takeIf { it >= 0 } ?: normalSectionsContainer.componentCount
        normalSectionsContainer.add(section, idx.coerceAtMost(normalSectionsContainer.componentCount))
        normalSectionsContainer.revalidate()
        normalSectionsContainer.repaint()

        (detailsPanel.layout as CardLayout).show(detailsPanel, "NORMAL")
        section.setExpandedIcon(false)

        expandedSection = null
        expandedOriginalIndex = -1
    }

    private fun hideAllSections() {
        commentSection.isVisible = false
        codeSection.isVisible = false
        aiTextSection.isVisible = false
        aiCodeSection.isVisible = false
    }

    private fun clearDetails() {
        commentBodyArea.text = ""
        setCodeContext(null, null, null)
        hideAllSections()
        resolveButton.isEnabled = false
    }

    private fun showCommentDetails(comment: PrComment) {
        // Always show comment section
        commentSection.isVisible = true
        commentBodyArea.text = comment.body

        if (comment.filePath == null) {
            // discussion: hide code + hide AI and disable resolve
            codeSection.isVisible = false
            hideAi()
            resolveButton.isEnabled = false
            return
        }

        // inline
        codeSection.isVisible = true
        if (comment.codeSnippet != null) {
            setCodeContext(comment.codeSnippet.text, comment.filePath, "Lines ${comment.codeSnippet.startLine} - ${comment.codeSnippet.endLine}")
        } else setCodeContext(null, null, null)

        hideAi() // AI only after resolve
        resolveButton.isEnabled = true
    }

    private fun hideAi() {
        aiTextSection.isVisible = false
        aiCodeSection.isVisible = false
        aiTextArea.text = ""
        setAiCode(null, null)
        if (expandedSection == aiTextSection || expandedSection == aiCodeSection) collapseExpanded()
    }

    private fun showAi(analysis: String, codeBlock: String?, fileHint: String?) {
        aiTextArea.text = analysis
        setAiCode(codeBlock, fileHint)
        aiTextSection.isVisible = true
        aiCodeSection.isVisible = true
    }

    private fun setCodeContext(code: String?, fileHint: String?, lineInfo: String?) {
        codeContextWrapper.removeAll()
        currentCodeContext = code

        if (code.isNullOrBlank()) {
            codeContextWrapper.add(JLabel("No code context available for this comment.").apply {
                border = JBUI.Borders.empty(6); foreground = JBColor.GRAY
            }, BorderLayout.CENTER)
            codeLineInfoLabel.isVisible = false
            copyCodeContextButton.isVisible = false
        } else {
            codeContextWrapper.add(EditorFactory.createCodeEditor(project, code, fileHint), BorderLayout.CENTER)
            codeLineInfoLabel.text = lineInfo ?: ""
            codeLineInfoLabel.isVisible = !lineInfo.isNullOrBlank()
            copyCodeContextButton.isVisible = true
        }

        codeContextWrapper.revalidate()
        codeContextWrapper.repaint()
    }

    private fun setAiCode(codeBlock: String?, fileHint: String?) {
        val p = aiCodeSection.contentPanel
        p.removeAll()
        p.border = JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1)
        currentAiCode = codeBlock

        if (codeBlock.isNullOrBlank()) {
            p.add(JLabel("No code block extracted.").apply { border = JBUI.Borders.empty(6); foreground = JBColor.GRAY }, BorderLayout.NORTH)
            aiCodeEditorField = null
            copyAiCodeButton.isVisible = false
        } else {
            val ed = EditorFactory.createCodeEditor(project, codeBlock, fileHint)
            aiCodeEditorField = ed
            p.add(ed, BorderLayout.CENTER)

            // Add copy button at the bottom
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1, 0, 0, 0)
                add(copyAiCodeButton)
            }
            aiCodeSection.add(buttonPanel, BorderLayout.SOUTH)
            copyAiCodeButton.isVisible = true
        }
        p.revalidate()
        p.repaint()
    }


    private fun extractNewCodeBlock(markdown: String): Pair<String?, String?> {
        logger.info("Starting NEW CODE block extraction")

        // Strategy 1: Look for NEW CODE marker and extract following code block
        extractCodeBlockAfterMarker(markdown)?.let { return it }

        // Strategy 2: Extract all code blocks and return the second one (NEW CODE after OLD CODE)
        return extractSecondCodeBlock(markdown)
    }

    private fun extractCodeBlockAfterMarker(markdown: String): Pair<String?, String?>? {
        for (pattern in NEW_CODE_PATTERNS) {
            val markerIndex = markdown.indexOf(pattern, ignoreCase = true)
            if (markerIndex >= 0) {
                logger.info("Found NEW CODE marker: '$pattern' at position $markerIndex")
                val codeBlockStart = markdown.indexOf("```", markerIndex)

                if (codeBlockStart >= 0) {
                    extractCodeBlock(markdown, codeBlockStart)?.let { (lang, code) ->
                        logger.info("Extracted NEW CODE block with marker '$pattern': language=$lang, code length=${code.length}")
                        return lang to code
                    }
                }
            }
        }
        return null
    }

    private fun extractSecondCodeBlock(markdown: String): Pair<String?, String?> {
        logger.info("NEW CODE marker not found, extracting all code blocks")
        val codeBlocks = collectAllCodeBlocks(markdown)
        logger.info("Found ${codeBlocks.size} code blocks total")

        return when {
            codeBlocks.size >= 2 -> {
                val (lang, code) = codeBlocks[1]
                logger.info("Returning second code block: language=$lang, code length=${code.length}")
                lang to code
            }
            codeBlocks.size == 1 -> {
                val (lang, code) = codeBlocks[0]
                logger.info("Only one code block found, returning it: language=$lang, code length=${code.length}")
                lang to code
            }
            else -> {
                logger.warn("No code blocks found in response")
                null to null
            }
        }
    }

    private fun extractCodeBlock(markdown: String, start: Int): Pair<String?, String>? {
        val langLineEnd = markdown.indexOf('\n', start + 3)

        return if (langLineEnd < 0) {
            // Single-line code block
            val end = markdown.indexOf("```", start + 3)
            if (end >= 0) null to markdown.substring(start + 3, end).trim() else null
        } else {
            // Multi-line code block
            val lang = markdown.substring(start + 3, langLineEnd).trim().ifBlank { null }
            val end = markdown.indexOf("```", langLineEnd + 1)
            if (end >= 0) lang to markdown.substring(langLineEnd + 1, end).trimEnd() else null
        }
    }

    private fun collectAllCodeBlocks(markdown: String): List<Pair<String?, String>> {
        val codeBlocks = mutableListOf<Pair<String?, String>>()
        var pos = 0

        while (true) {
            val start = markdown.indexOf("```", pos)
            if (start < 0) break

            extractCodeBlock(markdown, start)?.let { codeBlocks.add(it) }

            // Move past this code block
            val langLineEnd = markdown.indexOf('\n', start + 3)
            val end = if (langLineEnd >= 0) {
                markdown.indexOf("```", langLineEnd + 1)
            } else {
                markdown.indexOf("```", start + 3)
            }

            pos = if (end >= 0) end + 3 else break
        }

        return codeBlocks
    }

    private fun fetchPrComments() {
        val repoText = repoField.text.trim()
        val parts = repoText.split("/")
        if (parts.size != 2) return showError("Invalid repo format. Use: owner/repo")
        val owner = parts[0]
        val repo = parts[1]
        val prNumber = prField.text.trim().toIntOrNull()?.takeIf { it > 0 } ?: return showError("Invalid PR number")

        val popup = LoadingPopup(this)
        popup.show("Fetching PR comments from GitHub...")
        fetchButton.isEnabled = false
        resolveButton.isEnabled = false

        val githubToken = System.getenv("GITHUB_TOKEN")

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching PR Comments", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val snapshot = GitHubService(githubToken).fetchPrSnapshot(owner, repo, prNumber, snippetContextLines = 3)
                    currentSnapshot = snapshot
                    ApplicationManager.getApplication().invokeLater { loadCommentsFromSnapshot(snapshot) }
                } catch (e: Exception) {
                    logger.error("Failed to fetch PR comments", e)
                    ApplicationManager.getApplication().invokeLater { showError("Failed to fetch: ${e.message}") }
                }
            }
            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    popup.hide()
                    fetchButton.isEnabled = true
                    val selected = commentsList.selectedValue
                    resolveButton.isEnabled = selected?.filePath != null
                }
            }
        })
    }

    private fun resolveWithAi() {
        val snapshot = currentSnapshot ?: return showError("Please fetch PR comments first")
        val selected = commentsList.selectedValue ?: return showError("Please select a comment")
        if (selected.filePath == null) return showError("AI resolution is only available for inline review comments")

        val key = LLMSettingsManager.getInstance().getOpenAiKey()
        if (key.isBlank()) return showError("Please configure OpenAI API key in Settings")

        // Auto-save if user is in edit mode
        if (isEditingComment) {
            commentBodyArea.isEditable = false
            commentBodyArea.background = UIUtil.getPanelBackground()
            editButton.isVisible = true
            saveButton.isVisible = false
            cancelButton.isVisible = false
            isEditingComment = false
        }

        // Use the edited comment text from the text area
        val commentText = commentBodyArea.text.trim()
        if (commentText.isBlank()) return showError("Comment text cannot be empty")

        // Get the specific code snippet for this comment
        val codeSnippet = selected.codeSnippet
        val targetCode = codeSnippet?.text ?: currentCodeContext ?: ""
        val startLine = codeSnippet?.startLine ?: selected.line
        val endLine = codeSnippet?.endLine ?: selected.line


        val popup = LoadingPopup(this)
        popup.show("Analyzing with OpenAI...")
        fetchButton.isEnabled = false
        resolveButton.isEnabled = false

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Analyzing with AI", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val analysis = OpenAiService(key).analyzePrSnapshot(
                        snapshot = snapshot,
                        model = "gpt-4o",
                        userPrompt = """
                            You are helping to resolve a code review comment.
                            
                            FILE: ${selected.filePath}
                            LINES: $startLine-$endLine
                            COMMENT: $commentText
                            
                            TARGET CODE TO MODIFY (lines $startLine-$endLine):
                            ```
                            $targetCode
                            ```
                            
                            CRITICAL INSTRUCTIONS:
                            - ONLY modify the code shown in "TARGET CODE TO MODIFY" section above (lines $startLine-$endLine)
                            - DO NOT modify any other lines outside this range
                            - DO NOT add or remove methods outside the target lines
                            - Keep your solution focused ONLY on the specified lines
                            - If the comment mentions other parts of the file, IGNORE them - only fix lines $startLine-$endLine
                            
                            Provide your response in EXACTLY this format:
                            
                            1. Brief explanation of what needs to be changed in lines $startLine-$endLine
                            
                            2. **OLD CODE** (lines $startLine-$endLine):
                            ```java
                            $targetCode
                            ```
                            
                            3. **NEW CODE** (lines $startLine-$endLine only):
                            ```java
                            // your proposed solution for ONLY lines $startLine-$endLine here
                            ```
                            
                            Remember: Your NEW CODE must ONLY contain the refactored version of lines $startLine-$endLine. Nothing else.
                        """.trimIndent()
                    )
                    ApplicationManager.getApplication().invokeLater {
                        logger.info("AI Response received, length: ${analysis.length}")
                        val (lang, code) = extractNewCodeBlock(analysis)
                        if (code != null) {
                            logger.info("NEW CODE extracted successfully, language: $lang, code length: ${code.length}")
                        } else {
                            logger.warn("No NEW CODE block extracted from AI response")
                        }
                        showAi(analysis, code, selected.filePath)
                    }
                } catch (e: Exception) {
                    logger.error("Failed to analyze PR with OpenAI", e)
                    ApplicationManager.getApplication().invokeLater { showError("Failed to analyze: ${e.message}") }
                }
            }
            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    popup.hide()
                    fetchButton.isEnabled = true
                    resolveButton.isEnabled = commentsList.selectedValue?.filePath != null
                }
            }
        })
    }

    private fun showError(msg: String) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE)
    }

    private fun loadCommentsFromSnapshot(snapshot: PrSnapshot) {
        allComments.clear()

        // Add discussion comments first
        logger.info("Loading ${snapshot.discussionComments.size} discussion comments")
        snapshot.discussionComments.forEach { comment ->
            allComments.add(
                PrComment(
                    id = comment.id,
                    author = comment.user ?: "unknown",
                    body = comment.body ?: "",
                    filePath = null,
                    line = null,
                    status = PrCommentStatus.OPEN,
                    isGrouped = false,
                    createdAt = comment.createdAt
                )
            )
        }
        logger.info("Added ${allComments.size} discussion comments to allComments")

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
                } else null,
                createdAt = comment.createdAt
            )
        }

        // SAME APPROACH: sort so equal snippets are adjacent, then use previousSnippet scan.
        fun normalizeSnippet(s: String?): String {
            // keeps your "string compare" approach but avoids false negatives due to whitespace/newlines
            return s
                ?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?: ""
        }

        // Keep your sort style (filePath -> snippet -> line), but use normalized snippet for stability
        val sortedInlineComments = inlineCommentsList.sortedWith(
            compareBy<PrComment>(
                { it.filePath ?: "" },
                { normalizeSnippet(it.codeSnippet?.text) },
                { it.line ?: Int.MAX_VALUE }
            )
        )

        var previousSnippetKey: String? = null

        val groupedInlineComments = sortedInlineComments.map { comment ->
            val currentSnippetKey = normalizeSnippet(comment.codeSnippet?.text)

            val isGrouped =
                currentSnippetKey.isNotEmpty() &&
                        currentSnippetKey == previousSnippetKey

            previousSnippetKey = if (currentSnippetKey.isNotEmpty()) currentSnippetKey else null

            comment.copy(isGrouped = isGrouped)
        }

        allComments.addAll(groupedInlineComments)
        logger.info("Total comments after adding inline: ${allComments.size} (${allComments.count { it.filePath == null }} discussion, ${allComments.count { it.filePath != null }} inline)")

        applyFilter()
    }



    private fun applyFilter() {
        listModel.removeAll()
        val filter = filterComboBox.selectedItem as? CommentFilter ?: CommentFilter.ALL
        logger.info("Applying filter: $filter, allComments size: ${allComments.size}")
        val filtered = when (filter) {
            CommentFilter.ALL -> allComments
            CommentFilter.INLINE -> allComments.filter { it.filePath != null }
            CommentFilter.DISCUSSION -> allComments.filter { it.filePath == null }
        }
        logger.info("Filtered result: ${filtered.size} comments")
        filtered.forEach { listModel.add(it) }
        if (!listModel.isEmpty) commentsList.selectedIndex = 0 else clearDetails()
    }
}

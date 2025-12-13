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
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.*
import com.intellij.util.ui.AsyncProcessIcon
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

        val DIVIDER_COLOR = JBColor(Color(0x909090), Color(0x2B2B2B))
        val SECTION_BORDER = JBColor(Color(0xB0B0B0), Color(0x3A3A3A))
    }

    // ---- Top bar ----
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
    private val filterComboBox = JComboBox(CommentFilter.values()).apply { border = JBUI.Borders.empty(2) }

    // ---- Left list ----
    private val listModel = CollectionListModel<PrComment>()
    private val commentsList = JBList(listModel)
    private var allComments = mutableListOf<PrComment>()

    // ---- Right panel ----
    private val detailsPanel = JPanel(BorderLayout())

    private val commentBodyArea = createStyledTextArea()
    private val commentScroll = JBScrollPane(commentBodyArea).apply {
        border = JBUI.Borders.customLine(CARD_BORDER, 1)
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val codeContextWrapper = JPanel(BorderLayout())
    private var codeEditorField: EditorTextField? = null
    private val codeLineInfoLabel = JLabel("", AllIcons.General.Information, SwingConstants.LEFT).apply {
        foreground = LOCATION_COLOR
        font = font.deriveFont(11f)
        border = JBUI.Borders.empty(4, 0, 0, 0)
        isVisible = false
    }

    private val aiTextArea = createStyledTextArea().apply { background = AI_SECTION_BG }
    private val aiTextScroll = JBScrollPane(aiTextArea).apply {
        border = JBUI.Borders.empty()
        background = AI_SECTION_BG
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }
    private var aiCodeEditorField: EditorTextField? = null

    // ---- Fixed sections + expand ----
    private lateinit var detailsCards: JPanel
    private lateinit var normalSectionsContainer: JPanel
    private lateinit var normalScroll: JBScrollPane
    private lateinit var expandedWrapper: JPanel
    private lateinit var expandedScroll: JBScrollPane

    private lateinit var commentSection: JPanel
    private lateinit var codeSection: JPanel
    private lateinit var aiTextSection: JPanel
    private lateinit var aiCodeSection: JPanel

    private var expandedSection: JPanel? = null
    private var expandedOriginalIndex: Int = -1
    private val sectionExpandButtons = mutableMapOf<JPanel, JButton>()
    private val sectionFixedHeights = mutableMapOf<JPanel, Int>()
    private val sectionContent = mutableMapOf<JPanel, JPanel>()

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

        val mainSplitter = com.intellij.ui.JBSplitter(false, 0.35f).apply {
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

    // ---- TOP BAR ----
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
                    if (value.line != null) append(":${value.line}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, LOCATION_COLOR))
                }

                append("\n", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val preview = value.body.take(100).replace("\n", " ")
                append(preview, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                if (value.body.length > 100) append("...", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }

    // ---- Right side sections (fixed heights) ----
    private fun setupDetailsPanel() {
        detailsPanel.background = UIUtil.getPanelBackground()
        detailsPanel.border = JBUI.Borders.empty(8)

        normalSectionsContainer = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6)
        }

        fun fixedHeight(section: JPanel, height: Int) {
            sectionFixedHeights[section] = height
            section.maximumSize = Dimension(Int.MAX_VALUE, height)
            section.preferredSize = Dimension(0, height)
            section.minimumSize = Dimension(0, height)
        }

        commentSection = createSectionWithExpand("Comment", AllIcons.Nodes.Tag) { toggleExpand(commentSection) }
        fixedHeight(commentSection, 190)
        sectionContent[commentSection]!!.apply {
            border = JBUI.Borders.customLine(CARD_BORDER, 1)
            add(commentScroll, BorderLayout.CENTER)
        }

        codeSection = createSectionWithExpand("Code Context", AllIcons.Nodes.Class) { toggleExpand(codeSection) }
        fixedHeight(codeSection, 250)
        sectionContent[codeSection]!!.apply {
            border = JBUI.Borders.customLine(CARD_BORDER, 1)
            add(codeContextWrapper, BorderLayout.CENTER)
        }
        codeSection.add(codeLineInfoLabel, BorderLayout.SOUTH)
        setCodeContext(null, null, null)

        aiTextSection = createSectionWithExpand("AI Analysis", AllIcons.Actions.Lightning) { toggleExpand(aiTextSection) }
        fixedHeight(aiTextSection, 240)
        sectionContent[aiTextSection]!!.apply {
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
            add(aiTextScroll, BorderLayout.CENTER)
        }

        aiCodeSection = createSectionWithExpand("AI Proposed Code", AllIcons.FileTypes.Text) { toggleExpand(aiCodeSection) }
        fixedHeight(aiCodeSection, 260)
        setAiCode(null, null)

        // Normal order
        normalSectionsContainer.add(commentSection)
        normalSectionsContainer.add(codeSection)
        normalSectionsContainer.add(aiTextSection)
        normalSectionsContainer.add(aiCodeSection)

        // One outer scroll for the right panel (sections can exceed window height)
        normalScroll = JBScrollPane(normalSectionsContainer).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        // Expanded view
        expandedWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6)
        }
        expandedScroll = JBScrollPane(expandedWrapper).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        detailsCards = JPanel(CardLayout()).apply {
            isOpaque = false
            add(normalScroll, "NORMAL")
            add(expandedScroll, "EXPANDED")
        }

        detailsPanel.removeAll()
        detailsPanel.add(detailsCards, BorderLayout.CENTER)

        // IMPORTANT: AI hidden by default (only appears after Resolve)
        aiTextSection.isVisible = false
        aiCodeSection.isVisible = false
    }

    private fun createSectionWithExpand(title: String, icon: Icon, onToggle: () -> Unit): JPanel {
        val section = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = CompoundBorder(
                JBUI.Borders.customLine(SECTION_BORDER, 1),
                JBUI.Borders.empty(6)
            )
        }

        val titleLabel = JLabel(title, icon, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }

        val expandBtn = JButton(AllIcons.Actions.Expandall).apply {
            toolTipText = "Expand / collapse"
            isFocusPainted = false
            isOpaque = false
            border = JBUI.Borders.empty(2, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { onToggle() }
        }
        sectionExpandButtons[section] = expandBtn

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 4, 0)
            add(titleLabel, BorderLayout.WEST)
            add(expandBtn, BorderLayout.EAST)
        }

        val content = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
        }
        sectionContent[section] = content

        section.add(header, BorderLayout.NORTH)
        section.add(content, BorderLayout.CENTER)
        return section
    }

    private fun toggleExpand(section: JPanel) {
        if (!section.isVisible) return
        if (expandedSection == section) collapseExpanded() else expandSection(section)
    }

    private fun expandSection(section: JPanel) {
        if (expandedSection != null) collapseExpanded()

        val parent = section.parent ?: return
        expandedOriginalIndex = parent.components.indexOf(section)

        parent.remove(section)
        parent.revalidate()
        parent.repaint()

        // Let it grow
        section.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        section.preferredSize = Dimension(0, 900)
        section.minimumSize = Dimension(0, 250)

        expandedWrapper.removeAll()
        expandedWrapper.add(section, BorderLayout.CENTER)

        (detailsCards.layout as CardLayout).show(detailsCards, "EXPANDED")

        sectionExpandButtons[section]?.icon = AllIcons.Actions.Collapseall
        expandedSection = section
    }

    private fun collapseExpanded() {
        val section = expandedSection ?: return

        expandedWrapper.remove(section)

        // Restore fixed height
        sectionFixedHeights[section]?.let { h ->
            section.maximumSize = Dimension(Int.MAX_VALUE, h)
            section.preferredSize = Dimension(0, h)
            section.minimumSize = Dimension(0, h)
        }

        val idx = expandedOriginalIndex.takeIf { it >= 0 } ?: normalSectionsContainer.componentCount
        normalSectionsContainer.add(section, idx.coerceAtMost(normalSectionsContainer.componentCount))

        normalSectionsContainer.revalidate()
        normalSectionsContainer.repaint()

        (detailsCards.layout as CardLayout).show(detailsCards, "NORMAL")

        sectionExpandButtons[section]?.icon = AllIcons.Actions.Expandall
        expandedSection = null
        expandedOriginalIndex = -1
    }

    private fun collapseExpandedIfHiddenNow() {
        val section = expandedSection ?: return
        if (!section.isVisible) collapseExpanded()
    }

    // ---- Code Editor ----
    private fun createCodeEditor(code: String, filePathHint: String?): EditorTextField {
        val fileType = if (filePathHint != null) {
            val extension = filePathHint.substringAfterLast('.', "txt")
            FileTypeManager.getInstance().getFileTypeByExtension(extension)
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

                    // allow scrolling in editor itself
                    setVerticalScrollbarVisible(true)
                    setHorizontalScrollbarVisible(true)
                }
            }
        }
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

    // ---- Listeners ----
    private fun setupListeners() {
        commentsList.addListSelectionListener {
            val selected = commentsList.selectedValue
            if (selected == null) clearDetails() else showCommentDetails(selected)
        }

        filterComboBox.addActionListener { applyFilter() }
        fetchButton.addActionListener { fetchPrComments() }
        resolveButton.addActionListener { resolveWithAi() }
    }

    private fun clearDetails() {
        commentBodyArea.text = ""
        setCodeContext(null, null, null)
        hideAiSection()
        resolveButton.isEnabled = false
        collapseExpandedIfHiddenNow()
    }

    private fun showCommentDetails(comment: PrComment) {
        commentBodyArea.text = comment.body

        val isInline = comment.filePath != null
        if (!isInline) {
            // Discussion: hide code + hide AI entirely
            setCodeContext(null, null, null)
            showDiscussionLayout()
            return
        }

        // Inline: show comment + code, but AI hidden until Resolve
        showInlineLayout()

        if (comment.codeSnippet != null) {
            setCodeContext(
                code = comment.codeSnippet.text,
                filePathHint = comment.filePath,
                lineInfo = "Lines ${comment.codeSnippet.startLine} - ${comment.codeSnippet.endLine}"
            )
        } else setCodeContext(null, null, null)

        resolveButton.isEnabled = true
    }

    private fun showDiscussionLayout() {
        commentSection.isVisible = true
        codeSection.isVisible = false
        // Hide AI completely for discussion comments
        aiTextSection.isVisible = false
        aiCodeSection.isVisible = false
        currentAnalysis = null
        aiTextArea.text = ""
        setAiCode(null, null)

        resolveButton.isEnabled = false
        collapseExpandedIfHiddenNow()
    }

    private fun showInlineLayout() {
        commentSection.isVisible = true
        codeSection.isVisible = true
        hideAiSection()
        collapseExpandedIfHiddenNow()
    }

    // ---- AI show/hide ----
    private fun hideAiSection() {
        currentAnalysis = null
        aiTextArea.text = ""
        setAiCode(null, null)

        // IMPORTANT: no placeholder - just hide AI sections completely
        aiTextSection.isVisible = false
        aiCodeSection.isVisible = false

        collapseExpandedIfHiddenNow()
    }

    private fun showAiSection() {
        aiTextSection.isVisible = true
        aiCodeSection.isVisible = true
        collapseExpandedIfHiddenNow()
    }

    private fun setAiOutput(text: String, codeBlock: String?, codeFilePathHint: String?) {
        aiTextArea.text = text
        setAiCode(codeBlock, codeFilePathHint)
        showAiSection()
    }

    private fun setAiCode(codeBlock: String?, codeFilePathHint: String?) {
        val content = sectionContent[aiCodeSection] ?: return
        content.removeAll()
        content.border = JBUI.Borders.customLine(CARD_BORDER, 1)

        if (codeBlock.isNullOrBlank()) {
            content.add(
                JLabel("No code block extracted.", SwingConstants.LEFT).apply {
                    border = JBUI.Borders.empty(6)
                    foreground = JBColor.GRAY
                },
                BorderLayout.NORTH
            )
            aiCodeEditorField = null
        } else {
            val editor = createCodeEditor(codeBlock, codeFilePathHint)
            aiCodeEditorField = editor
            content.add(editor, BorderLayout.CENTER)
        }

        content.revalidate()
        content.repaint()
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

    // ---- Loading popup (spinner) ----
    private fun showLoadingPopup(message: String): JDialog {
        val owner = SwingUtilities.getWindowAncestor(this)
        val dialog = JDialog(owner).apply {
            isUndecorated = true
            isModal = false
            setAlwaysOnTop(true)
        }

        val spinner = AsyncProcessIcon("loading")
        val label = JLabel(message).apply { border = JBUI.Borders.empty(0, 8, 0, 0) }

        val panel = JPanel(BorderLayout()).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(CARD_BORDER, 1),
                JBUI.Borders.empty(10)
            )
            background = UIUtil.getPanelBackground()
            add(spinner, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
        }

        dialog.contentPane = panel
        dialog.pack()

        // center on owner
        val loc = owner?.locationOnScreen
        val size = owner?.size
        if (loc != null && size != null) {
            dialog.setLocation(
                loc.x + (size.width - dialog.width) / 2,
                loc.y + (size.height - dialog.height) / 2
            )
        } else {
            dialog.setLocationRelativeTo(null)
        }

        dialog.isVisible = true
        return dialog
    }

    // ---- Fetch / Resolve (with spinner popup) ----
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

        // show popup loader + disable buttons
        fetchButton.isEnabled = false
        resolveButton.isEnabled = false
        val loading = showLoadingPopup("Fetching PR comments from GitHub...")

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
                    ApplicationManager.getApplication().invokeLater { showError("Failed to fetch: ${e.message}") }
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    loading.dispose()
                    fetchButton.isEnabled = true
                    // resolve enabled only if inline selected
                    val selected = commentsList.selectedValue
                    resolveButton.isEnabled = selected?.filePath != null
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

        // show popup loader + disable buttons
        resolveButton.isEnabled = false
        fetchButton.isEnabled = false
        val loading = showLoadingPopup("Analyzing with OpenAI...")

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
                    ApplicationManager.getApplication().invokeLater { showError("Failed to analyze: ${e.message}") }
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    loading.dispose()
                    fetchButton.isEnabled = true
                    // still inline? then allow resolve again
                    val selected = commentsList.selectedValue
                    resolveButton.isEnabled = selected?.filePath != null
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

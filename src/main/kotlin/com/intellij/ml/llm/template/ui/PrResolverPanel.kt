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
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.border.CompoundBorder
import com.intellij.ui.components.*
import com.intellij.ui.EditorTextField

class PrResolverPanel(private val project: Project) : JPanel(BorderLayout()) {

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

    private val codeContextWrapper = JPanel(BorderLayout())
    private val codeLineInfoLabel = JLabel("", AllIcons.General.Information, SwingConstants.LEFT).apply {
        foreground = PrUiTheme.LOCATION_COLOR
        font = font.deriveFont(11f)
        border = JBUI.Borders.empty(4, 0, 0, 0)
        isVisible = false
    }

    private val aiTextArea = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; border = JBUI.Borders.empty(8); background = PrUiTheme.AI_SECTION_BG }
    private val aiTextScroll = JBScrollPane(aiTextArea).apply { border = JBUI.Borders.empty(); background = PrUiTheme.AI_SECTION_BG }
    private var aiCodeEditorField: EditorTextField? = null

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

        codeSection = SectionWithExpand("Code Context", AllIcons.Nodes.Class) { toggleExpand(codeSection) }.apply { setFixedHeight(260) }
        codeSection.contentPanel.border = JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1)
        codeSection.contentPanel.add(codeContextWrapper, BorderLayout.CENTER)
        codeSection.add(codeLineInfoLabel, BorderLayout.SOUTH)

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
    }

    private fun setupCommentsList() {
        commentsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        commentsList.emptyText.text = "No comments loaded. Click 'Fetch Comments' to start."
        commentsList.fixedCellHeight = 60
        commentsList.border = JBUI.Borders.empty(4)

        commentsList.cellRenderer = object : ColoredListCellRenderer<PrComment>() {
            override fun customizeCellRenderer(list: JList<out PrComment>, value: PrComment, index: Int, selected: Boolean, hasFocus: Boolean) {
                border = JBUI.Borders.empty(8, 4)
                icon = if (value.filePath != null) AllIcons.Nodes.Folder else AllIcons.Toolwindows.ToolWindowMessages

                val badgeText = if (value.filePath != null) "INLINE" else "DISCUSSION"
                val badgeColor = if (value.filePath != null) PrUiTheme.INLINE_BADGE_COLOR else PrUiTheme.DISCUSSION_BADGE_COLOR

                append("[$badgeText] ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, badgeColor))
                append("@${value.author}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, PrUiTheme.AUTHOR_COLOR))

                if (value.filePath != null) {
                    append(" • ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(value.filePath!!.substringAfterLast('/'), SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, PrUiTheme.LOCATION_COLOR))
                    if (value.line != null) append(":${value.line}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, PrUiTheme.LOCATION_COLOR))
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

    private fun clearDetails() {
        commentBodyArea.text = ""
        setCodeContext(null, null, null)
        hideAi()
        resolveButton.isEnabled = false
    }

    private fun showCommentDetails(comment: PrComment) {
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

        if (code.isNullOrBlank()) {
            codeContextWrapper.add(JLabel("No code context available for this comment.").apply {
                border = JBUI.Borders.empty(6); foreground = JBColor.GRAY
            }, BorderLayout.CENTER)
            codeLineInfoLabel.isVisible = false
        } else {
            codeContextWrapper.add(EditorFactory.createCodeEditor(project, code, fileHint), BorderLayout.CENTER)
            codeLineInfoLabel.text = lineInfo ?: ""
            codeLineInfoLabel.isVisible = !lineInfo.isNullOrBlank()
        }

        codeContextWrapper.revalidate()
        codeContextWrapper.repaint()
    }

    private fun setAiCode(codeBlock: String?, fileHint: String?) {
        val p = aiCodeSection.contentPanel
        p.removeAll()
        p.border = JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1)

        if (codeBlock.isNullOrBlank()) {
            p.add(JLabel("No code block extracted.").apply { border = JBUI.Borders.empty(6); foreground = JBColor.GRAY }, BorderLayout.NORTH)
            aiCodeEditorField = null
        } else {
            val ed = EditorFactory.createCodeEditor(project, codeBlock, fileHint)
            aiCodeEditorField = ed
            p.add(ed, BorderLayout.CENTER)
        }
        p.revalidate()
        p.repaint()
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
                        userPrompt = "Focus on resolving this comment: '${selected.body}' at ${selected.filePath}:${selected.line}. Give me exact required changes with file and line ranges."
                    )
                    ApplicationManager.getApplication().invokeLater {
                        val (_, code) = extractFirstCodeBlock(analysis)
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

        snapshot.discussionComments.forEach { c ->
            allComments.add(PrComment(c.id, c.user ?: "unknown", c.body ?: "", null, null, PrCommentStatus.OPEN))
        }
        snapshot.inlineComments.forEach { c ->
            allComments.add(
                PrComment(
                    id = c.id,
                    author = c.user ?: "unknown",
                    body = c.body ?: "",
                    filePath = c.path,
                    line = c.line,
                    status = PrCommentStatus.OPEN,
                    codeSnippet = if (c.snippet != null)
                        com.intellij.ml.llm.template.models.CodeSnippet(
                            c.snippet,
                            c.startLine?.toString(),
                            c.line
                        )
                    else null
                )
            )
        }
        applyFilter()
    }

    private fun applyFilter() {
        listModel.removeAll()
        val filter = filterComboBox.selectedItem as? CommentFilter ?: CommentFilter.ALL
        val filtered = when (filter) {
            CommentFilter.ALL -> allComments
            CommentFilter.INLINE -> allComments.filter { it.filePath != null }
            CommentFilter.DISCUSSION -> allComments.filter { it.filePath == null }
        }
        filtered.forEach { listModel.add(it) }
        if (!listModel.isEmpty) commentsList.selectedIndex = 0 else clearDetails()
    }
}

package com.intellij.ml.llm.template.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ml.llm.template.ui.PrUiTheme
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.CompoundBorder

class SectionWithExpand(
    title: String,
    icon: Icon,
    onToggle: () -> Unit
) : JPanel(BorderLayout()) {

    val contentPanel: JPanel = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = UIUtil.getPanelBackground()
    }

    val expandButton: JButton = JButton(AllIcons.Actions.Expandall).apply {
        toolTipText = "Expand / collapse"
        isFocusPainted = false
        isOpaque = false
        border = JBUI.Borders.empty(2, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onToggle() }
    }

    init {
        background = UIUtil.getPanelBackground()
        border = CompoundBorder(
            JBUI.Borders.customLine(PrUiTheme.SECTION_BORDER, 1),
            JBUI.Borders.empty(6)
        )

        val titleLabel = JLabel(title, icon, SwingConstants.LEFT).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 14f)
        }

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 4, 0)
            add(titleLabel, BorderLayout.WEST)
            add(expandButton, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    fun setFixedHeight(h: Int) {
        maximumSize = Dimension(Int.MAX_VALUE, h)
        preferredSize = Dimension(0, h)
        minimumSize = Dimension(0, h)
    }

    fun setExpandedIcon(isExpanded: Boolean) {
        expandButton.icon = if (isExpanded) AllIcons.Actions.Collapseall else AllIcons.Actions.Expandall
    }
}

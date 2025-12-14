package com.intellij.ml.llm.template.ui.components

import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.CompoundBorder
import com.intellij.ml.llm.template.ui.PrUiTheme

class LoadingPopup(private val anchor: java.awt.Component) {
    private var dialog: JDialog? = null

    fun show(message: String) {
        val owner: Window? = SwingUtilities.getWindowAncestor(anchor)

        val d = JDialog(owner).apply {
            isUndecorated = true
            isModal = false
            setAlwaysOnTop(true)
        }

        val spinner = AsyncProcessIcon("loading")
        val label = JLabel(message).apply { border = JBUI.Borders.empty(0, 8, 0, 0) }

        val panel = JPanel(BorderLayout()).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(PrUiTheme.CARD_BORDER, 1),
                JBUI.Borders.empty(10)
            )
            background = UIUtil.getPanelBackground()
            add(spinner, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
        }

        d.contentPane = panel
        d.pack()
        d.setLocationRelativeTo(owner)
        d.isVisible = true
        dialog = d
    }

    fun hide() {
        dialog?.dispose()
        dialog = null
    }
}

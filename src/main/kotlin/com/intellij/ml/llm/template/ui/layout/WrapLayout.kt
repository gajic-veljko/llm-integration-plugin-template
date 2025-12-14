package com.intellij.ml.llm.template.ui.layout

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import kotlin.math.max

/**
 * FlowLayout that wraps items to the next line instead of overlapping.
 */
class WrapLayout(
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
                val c = target.getComponent(i)
                if (!c.isVisible) continue

                val d = if (preferred) c.preferredSize else c.minimumSize
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
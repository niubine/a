package tw.firemaples.onscreenocr.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import tw.firemaples.onscreenocr.floatings.manager.OverlayTextBlock
import tw.firemaples.onscreenocr.floatings.manager.OverlayTextSource
import kotlin.math.roundToInt

class TextAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun snapshotTextBlocksInternal(excludePackageName: String): List<OverlayTextBlock> {
        val roots = windows?.mapNotNull { it.root }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(rootInActiveWindow)
        if (roots.isEmpty()) {
            return emptyList()
        }

        val minSizePx = (MIN_TEXT_BLOCK_DP * resources.displayMetrics.density).roundToInt()
        val blocks = mutableListOf<OverlayTextBlock>()
        roots.forEach { root ->
            collectTextBlocks(root, excludePackageName, minSizePx, blocks)
            root.recycle()
        }
        return blocks
    }

    private fun collectTextBlocks(
        node: AccessibilityNodeInfo,
        excludePackageName: String,
        minSizePx: Int,
        out: MutableList<OverlayTextBlock>,
    ): Boolean {
        if (!node.isVisibleToUser) {
            return false
        }

        if (node.packageName?.toString() == excludePackageName) {
            return false
        }

        var childAdded = false
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val added = collectTextBlocks(child, excludePackageName, minSizePx, out)
            childAdded = childAdded || added
            child.recycle()
        }

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val content = if (text.isNotBlank()) text else desc
        if (content.isBlank()) {
            return childAdded
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val tooSmall = rect.width() < minSizePx || rect.height() < minSizePx
        if (!tooSmall && !childAdded) {
            out.add(
                OverlayTextBlock(
                    text = content,
                    boundingBox = rect,
                    lineCountHint = countLineBreaks(content),
                    source = OverlayTextSource.Accessibility,
                )
            )
            return true
        }

        return childAdded
    }

    companion object {
        private const val MIN_TEXT_BLOCK_DP = 8
        @Volatile
        private var instance: TextAccessibilityService? = null

        private fun countLineBreaks(text: String): Int {
            val trimmed = text.trim()
            if (trimmed.isBlank()) {
                return 1
            }
            return trimmed.count { it == '\n' } + 1
        }

        fun snapshotTextBlocks(excludePackageName: String): List<OverlayTextBlock> {
            val service = instance ?: return emptyList()
            return service.snapshotTextBlocksInternal(excludePackageName)
        }
    }
}

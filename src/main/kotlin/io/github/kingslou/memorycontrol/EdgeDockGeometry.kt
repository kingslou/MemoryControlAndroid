package io.github.kingslou.memorycontrol

/** 贴边判断和坐标换算，不依赖 Android，便于验证窄窗口及左右拖出边界。 */
internal object EdgeDockGeometry {
    /** 完整面板可自由移动。 */
    const val NONE = 0
    /** 拉手固定在物理左侧，不随布局方向改变。 */
    const val LEFT = -1
    /** 拉手固定在物理右侧。 */
    const val RIGHT = 1

    /** 仅在拖动松手后判断停靠；两边同时接近时选择距离较近的一侧。 */
    fun sideAtEdge(x: Int, panelWidth: Int, viewportWidth: Int, threshold: Int): Int {
        val maximumX = maxOf(0, viewportWidth - panelWidth)
        val leftDistance = x.coerceIn(0, maximumX)
        val rightDistance = maximumX - leftDistance
        return when {
            leftDistance <= threshold && leftDistance <= rightDistance -> LEFT
            rightDistance <= threshold -> RIGHT
            else -> NONE
        }
    }

    /** 隐藏时贴紧指定边缘，正常显示时只约束窗口边界。 */
    fun windowX(side: Int, x: Int, windowWidth: Int, viewportWidth: Int): Int {
        val maximumX = maxOf(0, viewportWidth - windowWidth)
        return when (side) {
            LEFT -> 0
            RIGHT -> maximumX
            else -> x.coerceIn(0, maximumX)
        }
    }

    /** 右侧拉出时保留抓取点与面板右边缘的关系，避免窗口突然跳到屏幕另一边。 */
    fun restoredX(side: Int, handleX: Int, panelWidth: Int, handleWidth: Int, deltaX: Int): Int =
        handleX + deltaX - if (side == RIGHT) panelWidth - handleWidth else 0

    /** 只有超过触摸阈值的向内横拖才恢复面板，竖向移动仍保持贴边。 */
    fun isInwardDrag(side: Int, deltaX: Float, touchSlop: Int): Boolean = when (side) {
        LEFT -> deltaX > touchSlop
        RIGHT -> deltaX < -touchSlop
        else -> false
    }
}

package io.github.kingslou.memorycontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证边缘阈值、恢复抓取点及旋转后坐标，不依赖设备或 Android 方法桩。 */
class EdgeDockGeometryTest {
    /** 16dp 范围内允许停靠，超出范围和页面中间不自动隐藏。 */
    @Test fun docksOnlyNearReleasedEdge() {
        assertEquals(EdgeDockGeometry.LEFT, EdgeDockGeometry.sideAtEdge(16, 240, 800, 16))
        assertEquals(EdgeDockGeometry.NONE, EdgeDockGeometry.sideAtEdge(17, 240, 800, 16))
        assertEquals(EdgeDockGeometry.NONE, EdgeDockGeometry.sideAtEdge(280, 240, 800, 16))
        assertEquals(EdgeDockGeometry.RIGHT, EdgeDockGeometry.sideAtEdge(544, 240, 800, 16))
        assertEquals(EdgeDockGeometry.NONE, EdgeDockGeometry.sideAtEdge(543, 240, 800, 16))
    }

    /** 极窄窗口同时接近两侧时优先选择最近边缘，同距时稳定选择左侧。 */
    @Test fun narrowWindowsChooseNearestEdge() {
        assertEquals(EdgeDockGeometry.LEFT, EdgeDockGeometry.sideAtEdge(4, 240, 260, 16))
        assertEquals(EdgeDockGeometry.RIGHT, EdgeDockGeometry.sideAtEdge(16, 240, 260, 16))
        assertEquals(EdgeDockGeometry.LEFT, EdgeDockGeometry.sideAtEdge(0, 240, 200, 16))
    }

    /** 旋转或缩小页面后，右拉手仍贴右侧，正常面板保持完整可见。 */
    @Test fun resizedWindowsKeepHandleOnItsSide() {
        assertEquals(776, EdgeDockGeometry.windowX(EdgeDockGeometry.RIGHT, 776, 24, 800))
        assertEquals(376, EdgeDockGeometry.windowX(EdgeDockGeometry.RIGHT, 776, 24, 400))
        assertEquals(0, EdgeDockGeometry.windowX(EdgeDockGeometry.LEFT, 200, 24, 400))
        assertEquals(160, EdgeDockGeometry.windowX(EdgeDockGeometry.NONE, 776, 240, 400))
        assertEquals(0, EdgeDockGeometry.windowX(EdgeDockGeometry.NONE, -10, 240, 400))
        assertEquals(0, EdgeDockGeometry.windowX(EdgeDockGeometry.RIGHT, 0, 24, 12))
    }

    /** 从左右拉出时分别保留左边缘和右边缘抓取点，不以旧拉手宽度作为面板宽度。 */
    @Test fun restoringPreservesGrabbedEdge() {
        assertEquals(40, EdgeDockGeometry.restoredX(EdgeDockGeometry.LEFT, 0, 240, 24, 40))
        assertEquals(520, EdgeDockGeometry.restoredX(EdgeDockGeometry.RIGHT, 776, 240, 24, -40))
        assertEquals(736, EdgeDockGeometry.restoredX(EdgeDockGeometry.RIGHT, 776, 24, 24, -40))
    }

    /** 向外拖动、竖向拖动和阈值以内的抖动都不触发拉出。 */
    @Test fun restoresOnlyAfterInwardMotionPassesSlop() {
        assertFalse(EdgeDockGeometry.isInwardDrag(EdgeDockGeometry.LEFT, 8f, 8))
        assertTrue(EdgeDockGeometry.isInwardDrag(EdgeDockGeometry.LEFT, 9f, 8))
        assertFalse(EdgeDockGeometry.isInwardDrag(EdgeDockGeometry.LEFT, -20f, 8))
        assertFalse(EdgeDockGeometry.isInwardDrag(EdgeDockGeometry.RIGHT, -8f, 8))
        assertTrue(EdgeDockGeometry.isInwardDrag(EdgeDockGeometry.RIGHT, -9f, 8))
        assertFalse(EdgeDockGeometry.isInwardDrag(EdgeDockGeometry.RIGHT, 20f, 8))
        assertFalse(EdgeDockGeometry.isInwardDrag(EdgeDockGeometry.RIGHT, 0f, 8))
        assertFalse(EdgeDockGeometry.isInwardDrag(EdgeDockGeometry.NONE, 20f, 8))
    }
}

package io.github.kingslou.memorycontrol.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.kingslou.memorycontrol.MemoryPressureFloatingPanel

/** 提供跨页面和卸载入口，便于手动观察面板生命周期。 */
class MainActivity : Activity() {
    /** 创建示例操作页；加压参数通过悬浮面板设置。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 80, 24, 24)
        }
        content.addView(TextView(this).apply {
            text = "MemoryControlAndroid\n\n点击悬浮面板展开，拖动标题移动。\n暂停、收起和切页保留测试内存，点击释放清除。\nAndroid 6/7 仅支持 Java 堆加压。"
        })
        content.addView(Button(this).apply {
            text = "打开第二个页面"
            setOnClickListener { startActivity(Intent(this@MainActivity, SecondActivity::class.java)) }
        })
        content.addView(Button(this).apply {
            text = "卸载面板并释放测试内存"
            setOnClickListener { MemoryPressureFloatingPanel.uninstall() }
        })
        setContentView(content)
    }
}

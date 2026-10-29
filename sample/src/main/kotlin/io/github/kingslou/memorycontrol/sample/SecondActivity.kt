package io.github.kingslou.memorycontrol.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button

/** 使用普通 Activity 演示面板跟随前台页面及保留测试内存。 */
class SecondActivity : Activity() {
    /** 返回上一页时保留压力状态和面板位置。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(Button(this).apply {
            text = "第二个页面：点击返回"
            setOnClickListener { finish() }
        })
    }
}

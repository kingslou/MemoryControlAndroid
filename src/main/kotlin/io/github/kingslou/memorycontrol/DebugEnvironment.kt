package io.github.kingslou.memorycontrol

import android.content.Context
import android.content.pm.ApplicationInfo

/** 使用宿主调试标记，确保 release AAR 在宿主调试包中也能工作。 */
internal object DebugEnvironment {
    /** 返回宿主是否可调试，库自身的构建类型不参与判断。 */
    fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}

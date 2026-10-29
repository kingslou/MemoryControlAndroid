package io.github.kingslou.memorycontrol;

import android.content.Context;
import android.content.pm.ApplicationInfo;

/** 使用宿主的调试标记，保证发布为 release AAR 后仍可用于宿主调试包。 */
final class DebugEnvironment {
    /** 只提供内部环境判断，不创建实例。 */
    private DebugEnvironment() {
    }

    /** 返回宿主是否允许调试；空 Context 视为调用错误。 */
    static boolean isDebuggable(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context 不能为空");
        }
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}

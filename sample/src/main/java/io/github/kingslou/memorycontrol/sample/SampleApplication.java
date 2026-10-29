package io.github.kingslou.memorycontrol.sample;

import android.app.Application;

import io.github.kingslou.memorycontrol.MemoryPressureFloatingPanel;

/** 演示在 Application 中安装面板，无需悬浮窗权限或业务基类。 */
public final class SampleApplication extends Application {
    /** 在第一个页面创建前注册；非 debuggable 宿主会自动跳过安装。 */
    @Override
    public void onCreate() {
        super.onCreate();
        MemoryPressureFloatingPanel.install(this);
    }
}

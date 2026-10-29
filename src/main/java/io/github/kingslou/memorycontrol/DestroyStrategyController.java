package io.github.kingslou.memorycontrol;

/**
 * 可选的宿主页面销毁策略适配器。方法均在主线程调用，不能持有 Activity。
 * 库只读写选择状态，实际 finish 或移除登记由宿主自己的页面逻辑执行。
 */
public interface DestroyStrategyController {
    /** 返回当前是否采用仅移除登记的策略，false 表示调用 finish 的策略。 */
    boolean isRemoveEnabled();

    /**
     * 保存后续页面销毁时采用的策略，不应在这里立即销毁页面。
     * @param enabled true 选择 removeActivity，false 选择 killActivity
     */
    void setRemoveEnabled(boolean enabled);
}

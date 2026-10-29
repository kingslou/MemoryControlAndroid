# Changelog

## 1.0.0

- 提取独立的 Android library 和 9 个矢量图标，移除业务包和资源依赖。
- 保留悬浮面板、后台快照、Java/Native 加压、暂停和释放行为。
- 使用宿主调试标记替代库的 BuildConfig，支持通过 release AAR 接入。
- 通过可选 DestroyStrategyController 适配宿主页面销毁策略。
- 增加卸载接口，阻止卸载后旧窗口挂载任务重新创建面板。
- Android 6/7 默认选择 Java 堆模式，未采样的 PSS 使用 -1。
- 提供示例 app、Maven Publish 配置和 JitPack 版本依赖。

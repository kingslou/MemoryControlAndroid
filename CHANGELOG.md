# Changelog

## 1.1.1

- 修复 Kotlin 1.7 宿主接入 1.1.0 时的元数据版本不兼容错误。
- 发布产物采用 Kotlin 1.7 语言/API 级别和元数据，显式依赖 kotlin-stdlib 1.7.10，关闭插件自动引入 1.9 标准库。
- 保留 Kotlin 实现、原有 API 和运行行为，无需宿主升级 Kotlin 编译器。

## 1.1.0

- 库和 Demo 的全部 Java 源码迁移为 Kotlin，保留原有功能和宿主调试开关。
- Gradle 构建脚本迁移为 Kotlin DSL，使用 Kotlin 1.9.24。
- 公开入口保留 Java 静态调用，Snapshot 保留公开只读字段。
- 接入文档统一采用 Kotlin 示例，继续提供展开和收起的实机截图。

## 1.0.1

- 移除页面销毁策略的界面、适配器接口、专用图标和接入文档。
- 面板仅保留内存监控与加压功能，安装统一使用 install(Application)。

## 1.0.0

- 提取独立的 Android library 和 9 个矢量图标，移除业务包和资源依赖。
- 保留悬浮面板、后台快照、Java/Native 加压、暂停和释放行为。
- 使用宿主调试标记替代库的 BuildConfig，支持通过 release AAR 接入。
- 通过可选 DestroyStrategyController 适配宿主页面销毁策略。
- 增加卸载接口，阻止卸载后旧窗口挂载任务重新创建面板。
- Android 6/7 默认选择 Java 堆模式，未采样的 PSS 使用 -1。
- 提供示例 app、Maven Publish 配置和 JitPack 版本依赖。

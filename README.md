# arXiv Physics · Android 0.5.0

原生 Kotlin / Jetpack Compose / Material 3，Android 8.0+。覆盖全部 51 个物理分类，提供动态、历史检索、本地收藏、PDF、支持公式的离线 Markdown 阅读。

[下载最新正式版](https://github.com/SuiYueMengHen/arxiv-physics/releases/latest) · [发布与更新维护](docs/RELEASING.md)

## 使用

1. 动态支持全站最近 30 天、全部物理、订阅领域；检索支持分类、关键词、日期和时间排序。
2. 论文详情可单独下载 PDF，或下载后翻译，也可“下载并翻译”。
3. 在应用内 DeepSeek 官方网页登录。应用准备本篇 PDF 和全文翻译提示词，**你确认附件解析完成后手动发送**。不自动发送，不自动复制，不自动续写。
4. 长文中断时，网页菜单可“填入继续翻译指令”，仍由你发送。强化提示词要求逐页逐段、保留公式、脚注、图表、附录与参考文献，标注续译断点，仅全文覆盖后输出完成标记。提示词无法解除平台输出限制或保证模型绝不漏译。
5. 手动复制回答：当前论文网页位于前台时，新剪贴板超过 500 个 Unicode 字符，自动保存到该论文 Markdown，并返回全屏阅读。旧剪贴板、短文本不导入；重新复制相同长文本可导入。多段回答需先合并为所需完整内容再复制，保存会覆盖该论文文件。
6. 已保存内容可离线重读、分享 PDF / Markdown；收藏和下载列表展示译文中文文档标题与原英文题名。旧译文无中文标题时保留英文。
7. 主页右上角设置：白天、夜间、跟随系统；选择立即生效并保存，阅读器与系统栏同步。

## 应用内更新

设置 → 检查更新 → 下载并安装。读取本仓库最新正式 Release 的 `update.json` 与 APK；校验文件大小、SHA-256、包名、版本号和签名一致性后打开 Android 系统安装器。首次需要允许“安装未知应用”，最终安装由用户确认，不能静默绕过系统权限。系统安全策略或 Play Protect 扫描可能拒绝安装，应以系统提示为准。

- 不自动轮询更新；仅用户点击时请求 GitHub，无 GitHub token 内嵌。
- 支持百分比进度、错误重试、临时文件清理；完整下载并校验的安装包在应用重启后可继续安装。
- 下载过程中杀死进程，下次重新下载；不宣称后台下载或断点续传。
- 已在同一正式签名下安装的版本可以覆盖更新并保留本地资料。
- **0.5.0 是首个独立正式签名版本，与之前调试版签名不同。旧调试版先导出 PDF / Markdown 后再切换正式版；不能直接覆盖安装，卸载会删除旧版私有数据。应用不会替你卸载旧版。**

## 网页交互与性能

只在用户主动打开本篇翻译页面、应用前台时准备附件和提示词。准备完成停止 DOM 检查，不读取或序列化回答，不自动发送、复制或批量翻译，不使用后台服务、私有 API、指纹伪装、代理轮换或验证码绕过。遇到网站 403 / 429 或验证提示暂停，等待用户处理；附件准备重试次数有限，已有草稿和对话不覆盖。

这种辅助交互仍需遵循 [DeepSeek 官方使用条款](https://cdn.deepseek.com/policies/en-US/deepseek-terms-of-use.html)，不保证账号不受限制。网站改变附件控件时，可手动点击附件入口，应用提供本篇文件。

查询缓存 SQLite 15 分钟，联网失败回退已有缓存；刷新保留收藏。PDF 流式下载、64 MB 上限、原子保存；PDF / Markdown 按版本化论文 ID 独立存储。全站使用 UTC 最近 30 天日期范围，避免 `all:*` 引发的 HTTP 500。

## 构建

Android Studio / JDK 17+ / Android SDK 36。调试构建无需正式密钥：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w org.arxiv.physics.test/org.arxiv.physics.SmokeInstrumentation
```

设备测试使用受控网页验证真实附件字节、提示词填入、不会自动发送及准备后停止检查；真实 DeepSeek 登录账户仍需实际验收。离线 Markdown / 公式、SQLite 收藏、原生剪贴板与主题偏好也有设备测试。

[arXiv API 手册](https://info.arxiv.org/help/api/user-manual.html) · [物理分类](https://arxiv.org/category_taxonomy)。第三方阅读器，与 arXiv / DeepSeek 无隶属关系。离线渲染依赖 marked 与 KaTeX，许可证保存在 `app/src/main/assets/vendor/`。

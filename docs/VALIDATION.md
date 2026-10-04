# 验证记录 · 2026-10-04

## 构建

- JDK：Android Studio 内置 JBR；Gradle 8.13；AGP 8.13.1；Kotlin 2.2.20；Android SDK 36。
- `:app:assembleDebug` 通过，生成可安装调试 APK。
- `:app:testDebugUnitTest`：8 项通过，0 失败。
- `:app:lintDebug` 通过，无 Error；依赖新版本、目标 SDK 更新提示及 KTX 风格等 Warning 保留。

## Android 36 / Pixel 8 模拟器

- APK 安装、启动、通知许可、原生 Material 3 首页通过。
- 实际请求 arXiv 物理分类 API，成功显示新提交论文、日期、作者和摘要，结果不使用演示数据。
- 设备测试 `SmokeInstrumentation` 全部通过：Android Atom 解析与 DOCTYPE 拒绝；SQLite 元数据刷新保留收藏；离线 Markdown 标题、表格、行内 / 块级公式渲染与不受信任 HTML 清理。
- 首页截图：`screenshots/home.png`。
- 真实论文 `2610.02209v1` PDF 下载成功（401,957 字节），临时文件原子替换为正式 PDF；运行日志未出现 AndroidRuntime 崩溃。

设备测试运行：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w org.arxiv.physics.test/org.arxiv.physics.SmokeInstrumentation
```

## 未验证

- 未使用真实 DeepSeek 账户登录，未发送论文或翻译请求。公开页面探测 HTTP 403；DOM 上传、自动发送、自动续写、译文抓取及后台网页连续运行尚未端到端验证。
- 译文结束标记不能证明论文逐段翻译无遗漏。
- 未覆盖 Android 8 真机、厂商省电系统、平板、大字体及所有横竖屏组合。
- 当前产物使用调试签名，尚非应用商店发布版。

# 0.2.0 修复验证 · 2026-10-04

- 原请求 `search_query=all:*` 真实复现 HTTP 500。
- 有界 UTC 最近 30 天全站请求返回 HTTP 200，36,170 篇结果，第一页 30 篇。
- `assembleDebug`、`assembleDebugAndroidTest`、9 项单元测试与 Android Lint 通过。
- Android 36 设备测试 4 项全部通过，新增：受控网页回答结束自动点击复制、优先取得原始 Markdown、公式保留、写入对应论文、另一篇论文不受影响、应用不自动发送请求。
- 取消后台翻译服务及相关权限；只在全屏网页可见且应用前台时观察并保存回答。
- 网页 / 译文分别使用独立全屏页面，取消网页底部导航、长说明与 840dp 宽度上限，提供 IME 与系统栏避让。
- 真实 DeepSeek 账户登录、网页附件自动添加、网站复制控件与手机键盘组合尚需真实账户验收。设备测试使用受控网页，不声称官网端到端已通过。
- 真正的 DeepSeek 登录页已在模拟器 WebView 显示；网页区域覆盖 `[0,300][1080,2337]`，无底部导航。截图 `screenshots/deepseek-fullscreen.png`。没有输入账号、登录或发送真实翻译请求。
- 全站修复后的应用界面实际显示 36,170 篇结果，截图 `screenshots/all-fixed.png`。
- DeepSeek 实际登录页空输入框弹出键盘后，登录按钮仍完整可见，截图 `screenshots/deepseek-keyboard.png`；未输入凭据。

# 0.3.0 剪贴板导入验证 · 2026-10-04

- 保存触发替换为 Android OnPrimaryClipChangedListener：当前论文网页前台的新复制文本超过 500 Unicode 字符，即落盘并触发对应全屏预览回调。
- 原有网页回答结束检测不再用于保存，不再自动点击复制按钮。
- 进入页面前的旧剪贴板不导入，500 字符及以下不导入；新复制相同长文本也允许导入。离开页面 / 退到后台停止监听。
- 构建、Android Lint、11 项单元测试通过。
- Android 36 设备测试 4 项通过，其中原生剪贴板测试验证旧内容不导入、500 字符边界、新复制文本与公式原样存储、论文 ID 与预览回调对应、离开后停止监听。
- 保存会覆盖对应论文 Markdown；本策略按用户指定的长文本启发式判断，不执行完整性检测或分段合并。

# 0.4.0 自动上传、发送与主题 · 2026-10-04

- Gradle 构建、12 项单元测试、Android Lint 全部通过。
- Android 36 / Pixel 8 设备测试 6 项全部通过。新受控网页测试使用隐藏文件输入，验证真实触摸产生用户激活、PDF 实际字节通过 FileProvider 交给网页、上传解析完成后再发送，继续等待不会重复发送。
- 修复发送按钮辅助标签与可见文字重复时的识别问题；触摸坐标使用 visualViewport，适配缩放。
- 发送前要求当前论文附件文件名出现、翻译指令仍包含论文 ID、网页无上传/解析/生成状态，且发送按钮启用。
- 本地收藏和下载与翻译列表从已保存 Markdown 提取中文文档标题，同时显示原英文标题。新增翻译指令要求首行中文题名；旧文件无中文题名时保留英文标题，不猜测翻译。
- 设置提供白天、夜间、跟随系统，立即生效并保存偏好；Markdown 阅读器使用同一主题选择。修正深色模式状态栏图标对比度，覆盖安装后深色选择保留；浅色与深色实际截图见 `screenshots/theme-light.png` 与 `screenshots/theme-dark.png`。
- 原生剪贴板超过 500 Unicode 字符导入、文件落盘和全屏预览回调继续通过设备测试。
- 测试采用受控网页，尚未使用真实 DeepSeek 登录账户验证官网的附件与发送控件；不声称官网端到端已验证。

# 0.5.0 手动发送、性能与更新 · 2026-10-04

- 正式 / 调试构建、13 项单元测试、Android Lint 与 Release Lint 通过。
- Android 36 / Pixel 8 设备测试 6 项全部通过：原生 XML、SQLite 收藏、离线 Markdown / 公式、剪贴板导入、PDF 实际字节与强化提示词填入后不会自动发送、附件准备完成后监控协程已结束、主题保存。
- 移除发送控件适配和回答 DOM / Markdown 抓取脚本；附件准备结束停止轮询。验证或 403 / 429 暂停，不覆盖草稿、不批量操作账号。
- 全文翻译提示词约束逐页逐段完整覆盖、禁止省略、保留公式/图表/脚注/附录/参考文献，长度不足明确标注断点；网页菜单可填入续译指令，由用户手动发送。
- 新增 GitHub 正式 Release 检查、流式下载、百分比进度、大小 / SHA-256 / Android 包名 / 版本 / 签名验证、进程重启恢复完整下载包、安装未知应用授权与系统确认安装。
- 使用独立 RSA 4096 位正式密钥，正式 APK 非 debuggable；密钥与口令被 Git 忽略，不包含在公共源代码或发布资产中。首个正式签名不能覆盖旧调试签名安装，旧资料须先导出。
- 真实 DeepSeek 登录账户未用于验收，无法保证网站行为永远兼容、模型翻译完整或账号不受限制。

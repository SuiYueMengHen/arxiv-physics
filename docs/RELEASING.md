# 正式签名与 Release 维护

公开仓库不包含任何签名密钥、口令、local.properties、构建目录或下载数据。首次正式发布使用独立 RSA 4096 位密钥，口令随机生成。当前电脑的 `signing/arxiv-release.jks` 与 `signing.properties` 已被 Git 忽略且权限限制为本人读写。

必须安全备份这两个文件；后续正式更新须使用同一密钥，丢失密钥将无法覆盖原正式安装。不要将它们提交或放入 Release。0.4.0 及更早为调试签名，首次切换正式签名前需从旧应用分享导出 PDF / Markdown，之后再切换；卸载会删除旧私有资料。

其他开发者构建时在根目录创建被忽略的 `signing.properties`：

```properties
storeFile=signing/your-private-key.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_PRIVATE_PASSWORD
```

用自己的密钥只能更新自己签名的安装，不能替换官方发布签名。未配置密钥的 Release 构建为未签名产物，打包脚本拒绝发布该产物。

## 发布一个新版本

1. 提升 `app/build.gradle.kts` 的 versionCode（严格递增）和 versionName。
2. 更新 README、验收记录、发布说明。
3. 运行正式构建、单元测试、Lint 和所需设备测试：

```sh
./gradlew :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
python3 scripts/package_release.py
```

4. 校验 APK 为正式签名且非 debuggable；提交并推送源代码，在该提交创建 `v<versionName>` 标签并推送标签。
5. 用 `gh release create` 上传打包目录中的 APK、update.json、SHA256SUMS，使用 `--verify-tag --latest --notes-file`。不能使用草稿或预发布作为应用内正式更新。

示例（改成当前版本）：

```sh
git tag v0.5.0
git push origin main v0.5.0
gh release create v0.5.0 dist/v0.5.0/arxiv-physics-v0.5.0.apk dist/v0.5.0/update.json dist/v0.5.0/SHA256SUMS --repo SuiYueMengHen/arxiv-physics --verify-tag --latest --title 'arXiv Physics 0.5.0' --notes-file docs/releases/v0.5.0.md
```

更新器从 GitHub latest 正式 Release 获得清单和 APK，校验 GitHub 资产大小及 digest（存在时）、SHA-256、Android 包名、versionCode、versionName 和当前签名。仅版本号更大才提供安装；下载完再次校验才启动安装器。

## 更新验收

以同一正式签名的较低版本测试 APK（仅测试设备用 `-PupdateTestVersionCode=4 -PupdateTestVersionName=0.4.99` 构建，不发布）检查 v0.5.0：下载、未知应用授权、系统确认安装、升级后版本与本地文件保留。中止安装后可再次安装；完整下载包跨进程恢复。正式版 0.5.0 检查当前最新 Release 应显示已是最新版本。

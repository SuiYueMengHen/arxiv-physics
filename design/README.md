# 0.5.2 图标

使用内置 imagegen 生图工具生成 `icon-concept-v0.5.2.png`，再手工复刻为 `icon.svg`。SVG 是可编辑的矢量源，不嵌入位图；深蓝背景、米白折页与青绿色轨道保留设计稿的构图和配色，细节简化以适配小尺寸。

`python3 scripts/generate_icon.py` 从 SVG 的同一组路径与渐变生成 Android 背景、前景、完整矢量和主题单色图标。自适应前景缩小至安全区域，系统负责圆形或其他遮罩。`icon-preview-v0.5.2.png` 是 SVG 预览，`icon-android-v0.5.2.png` 是 Android 实际资源渲染。

生成提示词：

Use case: logo-brand. Asset type: Android adaptive launcher icon design for arXiv Physics, a scholarly physics paper reader and translator. Design one exceptionally elegant, memorable vector-friendly emblem: a warm ivory folded research-paper silhouette gently interwoven with a single turquoise orbital ellipse and a tiny luminous turquoise particle. Deep midnight navy background, restrained mint/teal accent, subtle tonal sophistication. Calm premium scientific identity, Material Design 3 compatible. Crisp geometric construction, thick enough shapes to read beautifully at 48px, spacious centered silhouette entirely inside central 60 percent safe area. Square full-bleed background, no rounded-square frame (OS will apply mask). Flat vector aesthetic, no textures, no 3D, no tiny text, no letters, no watermark, no generic three-orbit atom clipart. Single centered icon, no mockup, no grid. Suitable for faithful SVG reconstruction.

# Raidian 图标

0.5.2 使用维护者选定的 C 款「二次元电弧」。

- `raidian-icon-source.png`：上轮通过内置图像生成工具生成的原稿，完整保留。
- `raidian-icon.png`：README 用的 256 × 256 预览。
- Android 资源：`app/src/main/res/drawable-nodpi/raidian_launcher_art.png` 与各密度 `mipmap-*/ic_launcher*.png`。

本轮仅进行确定性的缩放与 Android 资源适配，没有重新生成或重绘人物。自适应图标使用 108 dp 图层布局，将原画放入中央 72 dp 区域，再由桌面应用施加圆形、圆角矩形等遮罩。外围补色为薄荷绿，主体面部保留在安全区域；不同遮罩可能裁去羽尖和肩部的外围部分。未将整张彩色图片用作 monochrome 遮罩，避免主题图标显示为实心方块。

资源已纳入 Git，正常编译无需运行生成脚本。需要重新导出时，在 Windows PowerShell 执行：

```powershell
./tools/Generate-LauncherIcons.ps1
```

## 原始生成提示词

工具：内置 `image_gen`，非 CLI。下列为所选 C 稿的原始提示词，保留供后续调整参考：

> Use case: stylized-concept. Create ONE square Android app icon for Raidian, a performance monitoring utility named after Arknights operator 电弧 / Raidian. Design C: charming refined anime portrait icon, a fresh original illustration of that character, not a cropped promotional poster. Bust close-up centered; short ash-grey bob with soft bangs, the two distinctive tall BLACK FEATHER tufts on her head (bird feathers, not animal ears), tiny white and dusty red flower ornament at one side, warm closed-eye smiling expression, graphite tactical collar with cyan details. Her pink-and-black gloved hand holds a single small cyan translucent telemetry panel at chest level bearing only a simple performance graph; keep the panel secondary so her face reads at app-icon scale. Clean thick silhouette, simplified cel shading, delicate but economical linework; friendly mature character, not childlike, not sexualized. Background solid pale cool mint with one subdued curved electrical trace, no busy scenery. Character mostly within central 76 percent safe area; feathers must not touch frame edges. Opaque full square background, no external frame. No words, numbers, logos, watermarks, or official Arknights branding. Single icon only.

名称与人物灵感来自《明日方舟》电弧；这不是官方图标，也不表示获得官方授权。代码组件的许可证不覆盖角色、美术或商标权利。

# 当前交付状态

番匣 **0.1.12 / versionCode 13**，包名 `com.animeshelf.app`，Android 8.0（API 26）及以上。

- 源码提交：`c47b1644108af38e70ca2d2465e0665d4990c220`。
- [构建 40281734929](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/40281734929)：测试、编译、Lint、签名和交付成功。
- 交付提交：`839b88bbeb246179ccf02a7bba0de63f308bac1b`。
- [APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/839b88bbeb246179ccf02a7bba0de63f308bac1b/downloads/anime-shelf-0.1.12.apk) · [安装 ZIP](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/839b88bbeb246179ccf02a7bba0de63f308bac1b/downloads/anime-shelf-0.1.12-install.zip)。签名与旧版一致，可覆盖安装保留数据，不要先卸载。

## 改动

- 每集的离线下载卡片增加 8dp 圆角进度条、百分比和已下载 / 总大小。继续显示下载速度、预计剩余时间、观看记录与原有操作。
- 番剧分组显示已加入下载器的各版本合计进度，按实际音视频字节加权；尚未加入下载器的后台准备任务仍由页面顶部单独统计。
- 单集总量沿用下载器实际报告的音画合计；任一所需轨道大小未知时不生成百分比。正在下载时使用活动动画，暂停、等待、失败时不动画；总量已知则保留实际进度。
- 下载中跟随主题色，暂停和等待为灰色、失败为红色、完成为绿色。未全部完成时上限 99%，完成后为 100%；已完成旧记录可采用已保存字节作为最终大小。
- 本轮未改动请求解析、下载队列控制、离线文件、观看记录及两份固定存档。关于文件校验、空间预警、全屏选集侧栏和手势设置仅提供建议，尚未实现。

## 验证

- ParserSelfTest：132 项离线解析断言通过。
- JUnit/Robolectric：335 项，37 个 suite，0 失败、0 错误、0 跳过。
- 新增 3 项行为/原生布局验证，覆盖按字节合计进度、未知轨道不产生百分比、暂停/失败/等待不活动、真正完成前不显示 100%，以及 360dp 宽手机上的条形高度、文字与边界。
- 检查了真实 Android View 渲染的 `download-progress-360.png`：下载中、暂停、失败、完成、大小未知五种状态。测试素材为模拟下载数据，不代表已连接安卓真机。
- Android 编译与 Lint 通过；Lint 0 错误、33 警告。apksigner 验证通过，APK 内嵌证书摘要与旧版一致。
- Artifact 摘要、APK/ZIP CRC、包名、版本、minSdk、签名、公开文件与更新清单核验通过。90 个源码/配置/公开下载文件的 Git blob 与字节长度和本地一致。
- 未连接小米 14，实际下载时的网络数据与刷新效果需安装复测。

## 文件身份

- Artifact：`12632651144`；SHA-256 `221f073a27fe0847863058be0349193b7236bec8ac7fd3cc586b25be17611a00`。
- APK：5,071,037 字节；SHA-256 `5dded75c59c1c1910659e71aefcd5bf76282147528b96b4f09a17b5fc82f5b57`。
- ZIP：5,071,179 字节；SHA-256 `c0525b784b9cbc81359b57f4d39d81a6bce62b48c3da6c76f6f657e1d25a0e72`。
- 证书 SHA-256：`0c1ce08f08273587d4930f96e524e2f192dbbe80f46c4cc36f4d599f4605ed6c`。

## 固定存档

- `archive/anime-shelf-0.1.10-20261026`：`81293df64257ef7ff1d5b2e3d4d4ccd63c48b5f2`。
- `archive/anime-shelf-0.1.6-20261023`：`1f21e9b8ca9d009797f20d95551558472f1021f7`。

本轮源码和交付均在 `anime-shelf-apk`，未改动 main 或固定存档。

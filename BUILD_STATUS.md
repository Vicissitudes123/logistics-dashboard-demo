# 当前交付状态

番匣 **0.1.13 / versionCode 14**，包名 `com.animeshelf.app`，Android 8.0（API 26）及以上。

- 源码提交：`de5f9f1884e67e12652daa93da0164f69c541624`。
- [构建 40597855157](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/40597855157)：测试、编译、Lint、签名和交付成功。
- 交付提交：`73b0db709c8055a94fbbe75092c11d265122442e`。
- [APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/73b0db709c8055a94fbbe75092c11d265122442e/downloads/anime-shelf-0.1.13.apk) · [安装 ZIP](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/73b0db709c8055a94fbbe75092c11d265122442e/downloads/anime-shelf-0.1.13-install.zip)。签名与旧版一致，可覆盖安装保留数据，不要先卸载。

## 改动

- 已完成的离线条目增加「检查文件」。本机检查缓存覆盖、文件长度和 SHA-256 摘要；发现异常后可确认修复，重新获取相同档位地址、清除异常片段并补下缺失部分，保留正常缓存和观看记录。资源路径更换时重新下载该版本。
- 新下载完成后在后台建立校验记录。旧下载第一次检查只能判断结构并建立本机基线，不能证明此前没有同长度损坏；不把校验当作解码兼容性或服务端原文件校验。
- 下载页提示可用空间不足；传输中约每 500 ms 检查一次空间，低于 200 MB 暂停并持久保存状态，避免继续占满存储。清理到至少 300 MB 后手动继续；保留每集、全局暂停和仅 Wi-Fi 设置。实际写入报空间不足时也转为可继续的暂停状态。
- 全屏「选集」打开右侧面板，当前播放继续。剧集按集数排序，显示已下载、本机观看位置和看完标记，选择后沿用离线优先及画质记忆；竖屏继续使用原选集入口。
- 「设置 → 手势设置」或播放器「画面 → 手势设置」可独立开关亮度、音量、横滑进度、长按加速、双击播放，横滑一屏的最大跳转时间可选 90 / 180 / 300 秒。关闭手势后控制按钮和进度条仍可用。
- 元数据备份升级 v4，保存手势设置并兼容 v1–v3；导入旧备份保留本机手势选择。重置外观与播放设置可恢复默认手势。

## 验证

- ParserSelfTest：132 项离线解析断言通过。
- JUnit/Robolectric：347 项，40 个 suite，0 失败、0 错误、0 跳过。
- 新增 12 项测试：真实 SimpleCache 的缺失片段、截断、同长度变更、局部修复与取消；下载中低空间拦截及跨实例暂停恢复；手势开关与灵敏度；备份迁移；全屏侧栏不断播、保持全屏、选择已下载版本并按本机进度续播。
- 查看真实 Android View 渲染的 `episode-sidebar-360.png`，确认集数顺序、当前集、离线与进度标记和触控尺寸；构建同时保留首页、播放器、下载进度条原生布局验证。测试使用模拟番剧与播放器，未连接小米 14。
- Android 编译与 Lint 通过；Lint 0 错误、36 警告。apksigner 验证通过，APK 内嵌证书摘要与旧版一致。
- Artifact 摘要、APK/ZIP CRC、包名、版本、minSdk、签名及更新清单核验通过。103 个源码/配置/公开下载文件在文档收尾前与本地 Git blob 和字节长度一致。
- 真实设备上的存储紧张、修复下载、视频输出及系统后台行为仍需安装复测；本机首次摘要不能替代服务端提供的可信校验值。

## 文件身份

- Artifact：`12777735611`；SHA-256 `3691f3958b51e32e3b392d16e0d9adf377225ab21d286d5e07e200f0161560f1`。
- APK：5,086,537 字节；SHA-256 `4b8a48b1981cde0adf22b2753903f686c0f45c5a324c6a4d71ca66153b31154d`。
- ZIP：5,086,679 字节；SHA-256 `5e896df8062ac7bdc69faaf2c46399201bd6fa11a0ed09d5b4089f231defa6af`。
- 证书 SHA-256：`0c1ce08f08273587d4930f96e524e2f192dbbe80f46c4cc36f4d599f4605ed6c`。

## 固定存档

- `archive/anime-shelf-0.1.13-20261028`：当前基线，固定应用快照 `73b0db709c8055a94fbbe75092c11d265122442e`；详见 [存档说明](archives/0.1.13/README.md)。
- `archive/anime-shelf-0.1.10-20261026`：`81293df64257ef7ff1d5b2e3d4d4ccd63c48b5f2`。
- `archive/anime-shelf-0.1.6-20261023`：`1f21e9b8ca9d009797f20d95551558472f1021f7`。

本轮源码和交付均在 `anime-shelf-apk`，未改动 main 或固定存档。原有两个存档分支和 main 已重新核对；本次新增 0.1.13 存档。

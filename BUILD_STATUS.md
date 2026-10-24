# 当前交付状态

番匣 **0.1.10 / versionCode 11**，包名 `com.animeshelf.app`，Android 8.0（API 26）及以上。源码提交 `c5f972e87913d66ffc35adf6545d0fad152eaad0` 在[构建 40055560862](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/40055560862)通过测试、编译、签名及发布。

## 本轮改动

- 竖屏播放器只保留底部进度条与一排快捷按钮，倍速、选集和画面设置从省略号更多菜单进入；横屏保留原直接入口。图标触摸区域至少 48dp，锁的位置不变。
- 长按剧集或离线条目进入多选后，下载/删除操作固定在列表外的底部；滚动不会把按钮带走。退出多选恢复导航。
- 离线列表直接读取本机观看记录，显示最近观看集数、逐集进度和续看按钮。最近一集未下载时明确标注，并可续看最近看过的已下载集。续播、记录展示均不依赖媒体接口。
- 封面移除已验证公开 CDN 地址的缩放后缀，按原图请求；解码上限从 512px 提高至 1536px，内存缓存 12MiB，保留每张文件 2MiB、磁盘 16MiB/64 文件限额。已有原图磁盘缓存可重新清晰解码；不对低分辨率源编造细节。
- 普通选集、首页继续、上下集及自动连播优先已完整下载的音视频；同集多版本先匹配上次画质，没有匹配则选已下载最高档位。主动选择在线画质仍可在线播放，未完整下载的版本不算离线可用。
- 单集、批量与失败重试先检查空间再确认下载。逐集匹配画质、跳过已有同档位任务，合计包含视频和声音。接口文件大小优先；否则由码率和时长估算，缺信息显示未知；失败任务只在同媒体资源时扣除已保存字节。取消、切页、退后台不开始下载，确认后使用准备好的地址入队。
- 存储说明列出各集、合计和设备可用空间；为索引保留至少 32MiB，不把其他正在下载任务算进该合计。估算不是实际大小承诺，空间不足时需先清理。

## 验证

- ParserSelfTest：132 项离线解析断言通过。
- 316 项 JUnit/Robolectric 测试，34 个 suite，0 失败、0 错误、0 跳过；本轮新增 18 项，并更新竖屏布局及离线优先的既有行为断言。
- 覆盖实际播放器上下集/续播、离线最近观看与各集记录、取消/后台下载准备、多选按钮滚动后可达、封面像素和原图缓存、音视频大小解析/估算/缺项/溢出及批量计划。
- Android 编译、Lint、apksigner 均通过；Lint 无错误，20 项警告。
- 已检查实际 Android View 原生渲染的新版竖屏与横屏控制界面。界面预览使用本地测试素材；控件尺寸与遮挡由实际布局测试验证。
- Artifact SHA-256 与平台摘要一致；APK/安装 ZIP CRC、包名、版本、最小系统版本、CI 签名报告及 APK 内嵌证书摘要均核对通过。
- 公开 APK、ZIP、校验文件与 latest.json 的 Git blob、大小和 SHA-256 与本地验证产物一致；更新清单指向本轮源码。
- 未连接安卓真机。小米 14 上的真实播放、HDR、媒体抽帧及网络行为仍需安装验证。大小估算取决于接口数据，真实文件可能不同；无法读取时明确显示未知。

## 安装包核验

- Artifact：`12544244928`；SHA-256 `bdda7e0069bc3c7eb9a68de0e90fce72a2f4d4786ec2ab6c39da7fce873baccc`。
- anime-shelf-0.1.10.apk：5,050,969 字节；SHA-256 `650c5fdb6a6f62e68efef7fbe0cd20b926a4746abbcde80c1426336a8d17e7b6`；Git blob `ef129d1a66201579854f339a43d575233e118f23`。
- anime-shelf-0.1.10-install.zip：5,051,111 字节；SHA-256 `5818b5fcd416bf68029cb05c83067dc963e0cdd1aaf6e1eb9cf8a4cce6716b2a`；Git blob `ab6968038f57eb6dc6397a0651d680ecc5d57a6e`。
- 更新清单 SHA-256：`4b9d39a3ae6844317f979cf90aa07f5aa1ad0e9bbb47200fb1ebebb08d738fa5`；schemaVersion 1。
- 签名证书 SHA-256：`0c1ce08f08273587d4930f96e524e2f192dbbe80f46c4cc36f4d599f4605ed6c`，与旧版一致，可覆盖安装。
- 交付提交：`19bcfc57cbb597a8e497f3ca65939ba4592b7876`。
- [ZIP 安装包](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/19bcfc57cbb597a8e497f3ca65939ba4592b7876/downloads/anime-shelf-0.1.10-install.zip)；[直接 APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/19bcfc57cbb597a8e497f3ca65939ba4592b7876/downloads/anime-shelf-0.1.10.apk)。解压 ZIP 后打开 APK 覆盖安装，不要先卸载。

仅开发分支 anime-shelf-apk 更新。main 保持 `414ff14c6c5a2bc34adee7fa4cbf40893c67817b`；0.1.6 固定存档保持 `1f21e9b8ca9d009797f20d95551558472f1021f7`。

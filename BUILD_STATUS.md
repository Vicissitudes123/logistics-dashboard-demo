# 当前交付状态

番匣 **0.1.8 / versionCode 9**，包名 `com.animeshelf.app`，Android 8.0（API 26）及以上。源码提交 `f3493c20cb4fa5446bd4b4fee6550a1de35d5382` 在[构建 39978534430](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/39978534430)通过测试、编译、签名及发布。

## 本轮改动

- 设置中手动检查本项目公开更新清单，显示版本、说明与 APK/ZIP 下载入口。检查不传账号信息，限制清单大小与来源；点击后打开浏览器，安装由用户确认。
- 默认仅 Wi-Fi 下载。根据当前网络及 VPN 底层传输类型判定，底层不明时等待；启动和下载请求打开/读取时均检查。网络恢复不覆盖用户手动暂停，已删除任务不会被旧失败回调重新加入。
- 开放移动下载前提示流量费用。重置与备份导入立即应用网络策略；旧 v1 备份保留本机新增设置，v2 备份允许移动数据时在恢复前明确提示。
- 每部番剧设置片头、片尾秒数，默认关闭。片头仅从头播放时跳过并可撤销；片尾留出至少 5 秒取消窗口，随后在下一个 500ms 轮询时跳转。暂停/缓冲/后台后重新留出完整取消窗口。短视频或不足 30 秒正文时不跳。
- 本集临时取消、手动拖动优先于自动跳过；同一集刷新地址重试保留临时取消。片尾沿用已有结束与自动连播逻辑，未改变离线连播的网络边界。
- 首页全部、未看完、已看完、有离线筛选，保留搜索输入与最近/置顶/其他顺序；只将音画均完整下载的番剧归为有离线。

## 验证

- ParserSelfTest：132 项离线解析断言通过。
- 245 项 JUnit/Robolectric 测试，25 个 suite，0 失败、0 错误、0 跳过；本轮新增 54 项。覆盖 Wi-Fi/VPN/移动网络判断、下载请求拦截与恢复、更新清单和网络边界、片库设置/备份兼容、筛选及跳过的 Activity 集成。
- 初轮修复测试构造使用 SDK 隐藏网络 API 的问题；第二轮修正对话框 onShow 派发顺序与倒计时轮询边界的测试时序，保留原业务断言。最终完整测试通过。
- Android 编译、Lint、apksigner 均通过；Lint 无错误，19 项警告。
- Artifact SHA-256 与平台摘要一致；APK/安装 ZIP 的 CRC、包名、版本、最小系统版本、CI 签名报告及 APK 内嵌证书摘要核对通过。
- 公开 APK、ZIP、校验文件与 latest.json 的 Git blob、字节数和 SHA-256 均与本地验证产物一致；实际更新入口指向本次构建。
- 未连接安卓真机。小米 14 的实际 Wi-Fi/VPN 切换、后台下载和播放表现仍需安装验证；自动测试不等同真机验收。

## 安装包核验

- Artifact：`12512323008`；SHA-256 `773749ed3efab4da74acdecf69279985060c22018415499a339393b18a70aaab`。
- anime-shelf-0.1.8.apk：5,021,705 字节；SHA-256 `3858b58733e3f9cca1fa69e17592c43f6e77d608b3618b1a2cc139990dda5cb5`；Git blob `f9dd5c447b4addb94a34c0768243fa1a6fbcad6a`。
- anime-shelf-0.1.8-install.zip：5,021,845 字节；SHA-256 `f82786c898be68ad767b8553a15b41f4f39a2f3b2897a91e7ad7b0a115d806ba`；Git blob `6b752c2f7064be9eadf9381e80f148aa28ab443b`。
- 更新清单 SHA-256：`cd74df52cb2f33dc1ba249811571368dc4bd4463bc43ffd2fb39034201d465be`；schemaVersion 1。
- 签名证书 SHA-256：`0c1ce08f08273587d4930f96e524e2f192dbbe80f46c4cc36f4d599f4605ed6c`，与旧版一致，可覆盖安装。
- 交付提交：`2fea4c04e45513828886dbebd1e4c3c3f498e754`。
- [ZIP 安装包](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/2fea4c04e45513828886dbebd1e4c3c3f498e754/downloads/anime-shelf-0.1.8-install.zip)；[直接 APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/2fea4c04e45513828886dbebd1e4c3c3f498e754/downloads/anime-shelf-0.1.8.apk)。解压 ZIP 后打开 APK 覆盖安装，不要先卸载。

仅开发分支 anime-shelf-apk 更新。main 仍为 `414ff14c6c5a2bc34adee7fa4cbf40893c67817b`；0.1.6 固定存档仍为 `1f21e9b8ca9d009797f20d95551558472f1021f7`。

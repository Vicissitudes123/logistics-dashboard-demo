# 当前交付状态

番匣 **0.1.9 / versionCode 10**，包名 `com.animeshelf.app`，Android 8.0（API 26）及以上。源码提交 `3e4fd5901b607d5d603f2e194738faed98dab6d6` 在[构建 40045441137](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/40045441137)通过测试、编译、签名及发布。

## 本轮改动

- 锁定与解锁图标固定在画面左侧同一位置。新增上一集、下一集、当前画质快捷入口；横屏单行、窄屏分行，图标保持无常驻底色与至少 48dp 点击区域。
- 每部番剧记录成功播放的画质、编码和在线/离线模式；首页继续观看、选集及上下集优先沿用。没有对应档位时显示选择，不静默降档；缺少离线版本时不自动联网，改在线必须主动点击。
- 播放中换画质保留选择时的实际位置、暂停状态和本集临时取消跳片。取消弹窗、后台、换集后不接受旧解析回调；明确重播会重启一轮跳片状态。
- 拖动时显示真实目标附近关键帧缩略图与简短时间。单后台工作线程、300ms 请求间隔、2 秒位置桶与 24 张小图缓存，单次抽帧限制 8MiB、24 次打开与 8 秒读取预算。网络监听也统计服务器忽略 Range 时内部跳过的字节；松手、换集、后台取消过期结果。
- 预览使用只读缓存，离线没有网络上游，不生成下载缓存片段。原生解码不可用时仅显示时间，并短暂退避。时间预算限制读取，不能保证强行中断已进入系统原生层的解码调用；不在 UI 线程等待释放。
- 首页采用大封面最近观看卡片和自适应海报网格，统一搜索、筛选及底部导航；保留最近观看、置顶、其他顺序及长按管理。备份 v3 加入各番播放偏好，兼容 v1/v2。

## 验证

- ParserSelfTest：132 项离线解析断言通过。
- 298 项 JUnit/Robolectric 测试，32 个 suite，0 失败、0 错误、0 跳过；本轮新增 53 项。覆盖续播/画质切换、备份兼容、预览代次取消/读取限额/离线边界、播放器手势及界面布局。
- Android 编译、Lint、apksigner 均通过；Lint 无错误，20 项警告。
- 已检查实际 Android View 原生渲染的 360/412dp 首页、横屏/竖屏播放器、锁定与缩略预览共 6 张 PNG。图片使用本地测试素材，几何测试验证控件可达、48dp 区域及无重叠；没有冒充真实番剧视频抽帧。
- Artifact SHA-256 与平台摘要一致；APK/安装 ZIP CRC、包名、版本、最小系统版本、CI 签名报告及 APK 内嵌证书摘要均核对通过。
- 公开 APK、ZIP、校验文件与 latest.json 的 Git blob、大小和 SHA-256 与本地验证产物一致；更新清单指向本轮源码构建。
- 未连接安卓真机，真实 B站音视频格式的抽帧、HDR 和小米 14 上的实际操作仍需安装验证。缩略图来自附近关键帧，不保证每个格式均支持或与目标毫秒完全一致；此限制不影响主播放器继续播放。

## 安装包核验

- Artifact：`12541615404`；SHA-256 `dfbd94236d5fcfc1f84fbd16b78fdb5a3df2e5a57bc6db41eb4bc3ab59b3bcfe`。
- anime-shelf-0.1.9.apk：5,042,461 字节；SHA-256 `980f337bbd456b42fe0b89252018473969b3313fedf3d16d67173435ce146875`；Git blob `5ce93f57d9e4e025fd19c94fc309f467525c0092`。
- anime-shelf-0.1.9-install.zip：5,042,601 字节；SHA-256 `4bb7a62835eb2f8a4da6421af6f18ac97dae2b500977c47730aeede394ff0c37`；Git blob `e9c55f710aa8fa2503b962a3bc37fb11a0fe524b`。
- 更新清单 SHA-256：`8d41073ab2252fa3bf2c58fae83bbcc4cad6843cef6d43f42ce584c63bf21a6f`；schemaVersion 1。
- 签名证书 SHA-256：`0c1ce08f08273587d4930f96e524e2f192dbbe80f46c4cc36f4d599f4605ed6c`，与旧版一致，可覆盖安装。
- 交付提交：`40503515b362e1f6298b250c2cc502d95d8d591c`。
- [ZIP 安装包](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/40503515b362e1f6298b250c2cc502d95d8d591c/downloads/anime-shelf-0.1.9-install.zip)；[直接 APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/40503515b362e1f6298b250c2cc502d95d8d591c/downloads/anime-shelf-0.1.9.apk)。解压 ZIP 后打开 APK 覆盖安装，不要先卸载。

仅开发分支 anime-shelf-apk 更新。main 仍为 `414ff14c6c5a2bc34adee7fa4cbf40893c67817b`；0.1.6 固定存档仍为 `1f21e9b8ca9d009797f20d95551558472f1021f7`。

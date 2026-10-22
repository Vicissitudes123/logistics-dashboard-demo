# 当前交付状态

番匣 **0.1.4 / versionCode 5**，包名 `com.animeshelf.app`，Android 8.0（API 26）及以上。源码提交 `63d3a001fc627e81583bfc249006a12df1ba6255` 在[构建 39719378308](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/39719378308)通过检查，APK 与 ZIP 均已发布。

## 本轮改动

- 离线页按番剧 ID 分组、按数字集数排序，保留同集多个清晰度；分组支持进入、返回和删除整部。
- 批量勾选集数、全选、统一清晰度与编码。逐集解析，已有任务跳过，失败任务重试，缺档报告并跳过，不自动降档。停止或离开前台仅停止后续加入，已排队下载继续。
- 批量删除确认具体版本数与大小，按确认时的 key 快照操作，不删除片库与观看记录。删除后迟到的重试/解析不会复活任务。
- 高密度屏幕图标使用 32dp 画布与 48dp 点击区域；统一背景后设置 padding，固定菜单标签关闭不需要的水平滚动。
- 播放层改用 SurfaceView；视频信息展示实际输入轨的分辨率、色彩传递、已知位深、编码、码率及屏幕 HDR 能力，不把档位标签或能力当成 HDR 输出证明。
- 缺少 DASH 档位标识的视频不借用顶层清晰度；刷新缺档列出实际选项。I/O 诊断区分提前结束、连接中断、协议、证书、文件等原因，保留安全主机/音画轨而不暴露签名 URL 或 Cookie。
- 截断检测只在无限长度请求有已解析长度时执行，有限长度请求跨资源尾或从文件尾读取仍允许正常结束。
- 不改既有媒体 User-Agent、缓存键、签名身份和下载存储方式；没有额外转码或画质重编码。

## 验证

- 解析检查：105 项断言通过，离线合成接口响应，不访问用户账号。
- JUnit/Robolectric：86 项，0 失败、0 错误、0 跳过。目录 7、批量 9、导航 14、安全诊断 12、媒体传输 12、手势 10、图标密度 4、触摸层 9、视频信息 9。
- Android 编译、Lint、APK 签名验证通过。发布 job 再次验证字节、版本、证书，ZIP 仅包含同一个 APK。
- 前两轮界面测试暴露背景覆盖 padding 和固定单行标签的无界水平滚动，最终修复后保留原测试约束全部通过。单行标签异常坐标是内容滚动坐标，不说明真机按钮框曾移出屏幕。
- 本地 artifact 与远程公开文件的 Git blob SHA、大小一致；APK/ZIP CRC、SHA-256、包信息和证书报告已核对。
- 公开网络样本测试：API 返回 412，不能据此确认账号接口状态；公开媒体样本手机 UA 返回 403，桌面 UA 返回 206 和有效 MP4 头。这不代表用户每一集已实测。
- 无安卓真机连接；用户报告小米 14 在 B站 App 能播放 HDR。新版实际 HDR 输出、SurfaceView 缩放和此前具体 I/O 故障仍需在其手机上复测。截图不能单独判断 HDR；可查看「画面 → 视频信息 / HDR」。

## 安装包核验

- Artifact：`12400113819`。
- APK：4,946,189 字节；SHA-256 `05155e117ff74c5ee463f0bbf118467f91db26b50b3a01c9e586b9fe1f21fa44`。
- APK Git blob：`3d3354f7106549fe7606fdff44611fe24ef2f1fc`。
- ZIP：4,946,329 字节；SHA-256 `7860f719f4aaa794061429de46f74639eddb76523df906f77225108f9740432b`。
- ZIP Git blob：`06aaf677fb1b9f19bab478a25bb96f7699f6b05d`。
- 签名证书 SHA-256：`0c1ce08f08273587d4930f96e524e2f192dbbe80f46c4cc36f4d599f4605ed6c`，与既有版本一致。
- 交付提交：`6b08644939175c1a3cfd1e404c83cf95235f49b6`。
- [ZIP 安装包](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/6b08644939175c1a3cfd1e404c83cf95235f49b6/downloads/anime-shelf-0.1.4-install.zip)；[直接 APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/6b08644939175c1a3cfd1e404c83cf95235f49b6/downloads/anime-shelf-0.1.4.apk)。解压 ZIP 后打开 APK 覆盖安装，保留旧数据，不要先卸载。

源码和下载文件仅在 `anime-shelf-apk` 分支；原 main 核对仍为 `414ff14c6c5a2bc34adee7fa4cbf40893c67817b`。

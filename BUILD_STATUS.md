# 当前交付状态

当前交付为番匣 **0.1.2**，`versionCode=3`，包名 `com.animeshelf.app`，最低 Android 8.0（API 26）。源码提交 `ffc5315f9e5f976ab477d4e9acb6dc3bd1265bab` 已在[构建 39707232387](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/39707232387)通过全部检查并完成浏览器下载交付。

## 本轮问题与改动

- 用户在 0.1.1 的实际截图显示：同一视频轨的三个地址均为 HTTP 403，其他集下载也被拒绝。不能由此断定 VPN、解码器或 DRM 是原因。
- 用户补充确认：番匣的登录核验已成功，同一集可在手机浏览器网页内播放。这进一步支持比较客户端媒体请求差异，但尚不是同一签名 URL 的 UA 对照实验。
- 此前 API 与媒体统一使用 Android Mobile UA。网页 CDN 现在使用桌面浏览器标识，并在最终请求层覆盖旧任务保存的 UA；API、登录、原签名 URL、Range 和缓存键保持原样。
- [同类安卓客户端历史提交](https://github.com/yearzen1/bilibili-pure-apk/commit/61908e12db99acbcb2df135a100cecc4ac2e6147)记录了该兼容问题，构成本轮改动依据。尚未对用户同一条签名 URL 做设备上的 A/B 请求。
- 首页与下载页此前只更新列表，播放器仍持续占据上半屏。现在有明确首页入口，切换页面先保存进度、释放并收起播放器，全屏和错误弹窗也可返回首页。

## 验证与交付

- 本地与 CI 解析回归：92 项断言通过。
- JUnit/Robolectric：24 项测试全部通过，0 失败、0 跳过；其中导航 7 项、媒体请求 8 项、安全诊断 9 项。编译与 Lint 通过。
- 同一公开番剧媒体链接、同一 CI 出口与 Range 请求下，手机 UA 返回 HTTP 403 / 0 字节，桌面 UA 返回 HTTP 206 / 1024 字节，Content-Type 为 video/mp4 且包含 MP4 文件头。证实该样本的 UA 兼容性问题；不等于用户 JOJO 剧集已完成真机验收。普通 BV API 探测返回 412，随后使用公开番剧样本完成对照。未使用用户账号、未打印签名 URL。
- CI 完成测试、编译、Lint 和签名验证后，在独立 `anime-shelf-apk` 分支写入已验证 APK 及校验文件，提供浏览器直接下载入口。
- 继续使用 `anime-shelf-debug-signing-v2` 缓存密钥；必须实际核对签名证书 SHA-256 为 `0c1ce08f08273587d4930f96e524e2f192dbbe80f46c4cc36f4d599f4605ed6c`，才交付覆盖更新包。

## APK 文件核验

- Artifact：`12395154304`，构建与交付 job 均验证 APK v2 签名；证书与 0.1.0 / 0.1.1 一致。
- APK 大小：4,906,938 字节；SHA-256：`fa30c03c40a953ad9160677993cc80078848b78055d97d7fe6da3956888decef`。
- 独立分支交付提交：`c999a5e8dfcae020d95246b3e187a46cceb0ac79`，远程 Git blob 与本地已验证 APK 一致。
- [直接下载 APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/c999a5e8dfcae020d95246b3e187a46cceb0ac79/downloads/anime-shelf-0.1.2.apk)。

## 既有版本

0.1.1 源码提交 `44a2e79f2e65c2d5dfcb6bf101f344df57b1b11a` 在[构建 39703457406](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/39703457406)通过 87 项解析断言、16 项单元测试、编译、Lint 和签名校验。用户真机随后确认该版仍发生 403，因此这些通过结果不等于视频可用。

## 设备验证边界

当前仍需用户复测该账号、JOJO 剧集和手机网络中的播放、下载及首页导航。未宣称所有 HTTP 403 已消失。原仓库 main 分支保持不变。

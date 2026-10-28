# 番匣 0.1.13 固定存档

用户于 2026-10-28（北京时间）要求保存当前版本，作为后续修改的回退基线。本次不修改应用功能，也不重新生成安装包。

- 存档分支：[archive/anime-shelf-0.1.13-20261028](https://github.com/Vicissitudes123/logistics-dashboard-demo/tree/archive/anime-shelf-0.1.13-20261028)。完成存档后不要向此分支提交或移动它。
- 固定应用快照：[73b0db709c8055a94fbbe75092c11d265122442e](https://github.com/Vicissitudes123/logistics-dashboard-demo/tree/73b0db709c8055a94fbbe75092c11d265122442e)。源码、构建配置和安装包以此提交为准，不依赖开发分支后续状态；存档分支还补齐发布说明、校验清单和恢复说明。
- [完整应用快照 ZIP](https://github.com/Vicissitudes123/logistics-dashboard-demo/archive/73b0db709c8055a94fbbe75092c11d265122442e.zip)。包含源码、测试、构建配置及仓库中的安装包。
- [0.1.13 安装 ZIP](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/73b0db709c8055a94fbbe75092c11d265122442e/downloads/anime-shelf-0.1.13-install.zip)；[0.1.13 APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/73b0db709c8055a94fbbe75092c11d265122442e/downloads/anime-shelf-0.1.13.apk)。链接固定到发布提交。
- 包名 `com.animeshelf.app`，versionName `0.1.13`，versionCode `14`，最低 Android 8.0 / API 26。原[构建 40597855157](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/40597855157)通过 132 项解析断言和 347 项 JUnit/Robolectric 测试，Lint 0 错误、36 警告，签名验证通过。
- [manifest.json](manifest.json) 记录固定源码、构建与交付提交、文件 Git blob、安装包 SHA-256、大小、证书摘要以及原有存档。本次再次核对 98 个源码/配置/安装文件与已验证发布一致；APK、安装 ZIP 校验及 ZIP CRC 通过。
- 本地还保留源码 ZIP、安装文件、验证报告和校验副本，目录为 `/workspace/archives/anime-shelf-0.1.13-20261028/`。持久恢复入口以 GitHub 固定提交和存档分支为准。

此版本包含离线文件检查与修复、下载空间保护、全屏选集侧栏、手势设置，以及此前的下载进度条、批量管理、离线优先播放和观看记录等功能。旧下载首次校验只能建立本机基线，不证明文件从一开始就无损坏。

## 存档范围

存档保存应用代码、安装包和验证资料，不包含手机上的个人片库、观看记录、登录信息、下载视频或签名私钥。手机数据仍在本机，应用内备份可另行保存片库和记录，但不包含视频文件。

0.1.10 和 0.1.6 的固定存档继续保留，不移动它们的分支。

## 未来恢复步骤

1. 从固定应用快照或本存档分支创建独立恢复分支，保留后续工作，不移动任何存档分支或 main。
2. 保持包名和原签名身份，把 versionCode 设为高于手机已安装版本的值，同步构建工作流的版本预期。安卓通常拒绝低版本号覆盖高版本号，不要先卸载应用来降级。
3. 比较数据库、偏好、备份和下载索引格式，必要时补充兼容迁移。此版使用元数据备份 v4，并兼容 v1–v3。
4. 重跑相关测试、构建和签名验证。证书 SHA-256 必须与 manifest 一致；CI 使用签名缓存 `anime-shelf-debug-signing-v2`。存档不包含签名私钥，若密钥丢失，必须恢复同一密钥才能继续覆盖安装。
5. 交付基于此存档功能、版本号递增的恢复更新包。保留本机数据须同时满足原签名和数据兼容要求。

以后用户说“回退到 0.1.13 存档”时，按以上固定快照和流程执行。

# 番匣 0.1.10 固定存档

用户于 2026-10-26（北京时间）要求保留当前版本作为后续修改的回退基线。

- 存档分支：[archive/anime-shelf-0.1.10-20261026](https://github.com/Vicissitudes123/logistics-dashboard-demo/tree/archive/anime-shelf-0.1.10-20261026)。后续开发不要向该分支提交或移动它。
- 固定应用快照：[81293df64257ef7ff1d5b2e3d4d4ccd63c48b5f2](https://github.com/Vicissitudes123/logistics-dashboard-demo/tree/81293df64257ef7ff1d5b2e3d4d4ccd63c48b5f2)。即使分支名以后被移动，也以此提交为准。
- [下载完整仓库快照 ZIP](https://github.com/Vicissitudes123/logistics-dashboard-demo/archive/81293df64257ef7ff1d5b2e3d4d4ccd63c48b5f2.zip)，包含该版本源码、测试、构建配置及仓库内安装包。
- [0.1.10 安装 ZIP](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/19bcfc57cbb597a8e497f3ca65939ba4592b7876/downloads/anime-shelf-0.1.10-install.zip)；[0.1.10 APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/19bcfc57cbb597a8e497f3ca65939ba4592b7876/downloads/anime-shelf-0.1.10.apk)。安装链接固定到发布提交，不随开发分支变化。
- 包名 `com.animeshelf.app`，versionName `0.1.10`，versionCode `11`，最低 Android 8.0 / API 26。原[构建 40055560862](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/40055560862)通过 132 项解析断言与 316 项 JUnit/Robolectric 测试。
- [manifest.json](manifest.json) 记录源码文件 Git blob、安装包 SHA-256、大小、签名证书摘要和构建提交关系。本次核对源码与已验证构建一致、安装文件字节一致；存档不改应用功能，也不重新生成不同的安装包。
- 同时保存了本地源码 ZIP、安装包和校验副本，目录为 `/workspace/archives/anime-shelf-0.1.10-20261026/`。持久回退入口以以上 GitHub 固定提交为准。

此版本包含竖屏底部控制、固定多选下载/删除按钮、离线观看记录、清晰封面、完整下载优先播放，以及下载前空间确认。此前的 0.1.6 存档继续保留。

## 存档范围

存档包含应用代码、安装包和验证资料，不包含手机上的个人片库、观看记录、登录信息、下载视频或签名私钥。手机数据仍保存在本机；应用内 JSON 备份可另行保存片库和记录，但不包含视频文件。

## 未来恢复步骤

1. 从固定快照创建独立修复分支，保留后续工作，不覆盖未提交修改；不要移动任何存档分支或仓库 main。
2. 若手机已安装更高版本，保持包名与原签名身份，将 versionCode 设置为高于手机已安装版本的值，并同步构建工作流的预期版本。
3. 检查后续版本是否改动数据库、偏好、备份或下载索引，必要时添加兼容迁移；恢复旧代码不代表手机数据可以任意降级。
4. 重跑相关测试、Android 构建及签名检查。签名证书 SHA-256 必须与 manifest 一致。原测试签名使用 CI 缓存 `anime-shelf-debug-signing-v2`；若缓存中的密钥丢失，需恢复同一密钥，不能用新密钥承诺覆盖安装。
5. 提供基于存档源码、带更高 versionCode 的恢复更新包供覆盖安装。安卓通常拒绝低版本号覆盖高版本号，不要通过卸载绕过，卸载会清除本机数据和离线视频。

以后用户说“回退到 0.1.10 存档”时，按上述固定快照和恢复更新流程执行。

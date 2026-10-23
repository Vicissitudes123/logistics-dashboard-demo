# 番匣 0.1.6 固定存档

用户于 2026-10-23 要求保留此版本作为后续修改出错时的回退基线。

- 存档分支：[archive/anime-shelf-0.1.6-20261023](https://github.com/Vicissitudes123/logistics-dashboard-demo/tree/archive/anime-shelf-0.1.6-20261023)。此分支仅用于存档，后续开发不要向它提交或移动它。
- 固定快照：[1f21e9b8ca9d009797f20d95551558472f1021f7](https://github.com/Vicissitudes123/logistics-dashboard-demo/tree/1f21e9b8ca9d009797f20d95551558472f1021f7)。即使分支名称以后被移动，也以这个提交为准。
- [下载仓库快照 ZIP](https://github.com/Vicissitudes123/logistics-dashboard-demo/archive/1f21e9b8ca9d009797f20d95551558472f1021f7.zip)（包含源码和仓库内安装包）。
- [0.1.6 安装 ZIP](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/c1c2570d311b7cc8550dd6210dc413851322e9c1/downloads/anime-shelf-0.1.6-install.zip)；[0.1.6 APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/c1c2570d311b7cc8550dd6210dc413851322e9c1/downloads/anime-shelf-0.1.6.apk)。这些链接固定到发布提交，不跟随开发分支变化。
- 包名 `com.animeshelf.app`，versionName `0.1.6`，versionCode `7`。105 项解析断言、127 项 JUnit/Robolectric 测试通过。[原构建记录](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/39728043508)。
- 完整 SHA-256、证书摘要、源码文件 Git blob 校验值与提交关系见 [manifest.json](manifest.json)。本存档包含应用代码和安装产物，不包含手机里的个人片库、登录信息或下载视频，也不包含签名私钥。

## 未来恢复步骤

1. 保留现有工作与提交，在独立修复分支中从固定快照恢复源码；不要覆盖未提交修改，不要修改本存档分支，也不要修改仓库 main。
2. 如果只是查阅旧代码，可直接浏览或下载快照。如果要向已安装新版的手机交付恢复版，保留包名与原签名身份，将 versionCode 设置为高于手机已安装版本的值，并同步工作流预期版本。
3. 检查新版是否改动数据库、偏好或下载索引；必要时提供兼容迁移。恢复源码不代表手机数据能任意降级。
4. 重跑相应测试、Android 构建与签名校验，确认签名证书 SHA-256 与 manifest 一致，再提供恢复版 APK 覆盖安装。原测试签名使用 CI 缓存 `anime-shelf-debug-signing-v2`；若密钥丢失，应先恢复同一密钥，不能以新密钥替代来保证覆盖安装。

安卓通常拒绝较低 versionCode 覆盖较高版本，因此不要承诺直接点旧 APK 就能降级，也不要建议通过卸载绕过：卸载会清除本机片库、观看记录和离线文件。未来用户说“回退到 0.1.6 存档”时，优先采用上述保留签名、提高版本号的恢复更新流程。

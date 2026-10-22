# 当前交付状态

番匣 **0.1.6 / versionCode 7**，包名 `com.animeshelf.app`，Android 8.0（API 26）及以上。源码提交 `25d11edacd40205151642830b6d5de54bd1d5f0f` 在[构建 39728043508](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/39728043508)完成测试、编译、签名与发布。

## 本轮改动

- 首页片名关键词搜索，支持部分片名、空格分词、大小写和全半角归一；例如「高木」「JOJO 钻石」。输入时只刷新结果，保留焦点与光标，不提供拼音或错别字纠正。
- 最近观看一部 → 置顶 → 其他的分组顺序；最近观看不重复，新导入不抢占。
- 首页长按或管理可置顶/取消置顶、确认移除；保留离线文件、元数据与观看记录，并可在设置恢复。首页移除不等于删除下载。
- 默认蓝色及六种主题，切换后立即更新按钮、播放进度条和后续弹窗强调色；不重启 Activity。
- 可设置常用倍速、临时长按加速和控制栏隐藏时间。松手恢复原速，原速更高时不降速。
- 设置显示离线占用及已移除番剧入口；重置仅影响外观/播放偏好，不清除番剧、置顶、隐藏状态、下载、观看记录或登录。
- 保留亮度与临时倍速手势、无底色播放器控件、列表多选、HDR 输出和既有媒体传输。

## 验证

- 解析检查 105 项断言通过。
- JUnit/Robolectric 127 项通过，0 失败、0 错误、0 跳过。本轮新增 21 项，覆盖搜索焦点与光标、排序去重、隐藏恢复保数据、设置持久化与重置、实际按钮/进度条换色、长按速度和自动隐藏配置。
- 首轮 CI 中两项确认弹窗测试未处理已暂停主线程的回调；修正测试点击辅助方法后重跑通过，原业务断言保留。
- Android 编译与 Lint 完成，无错误；Lint 报告含 17 项警告。签名校验及发布阶段的再次校验通过。
- Artifact SHA-256 与 GitHub 提供的摘要一致；外层 artifact 和内层 APK ZIP CRC、版本、CI 签名报告及 APK 内实际证书摘要均核对。
- 本地 APK、确定性安装 ZIP 的 SHA-256、Git blob SHA 和大小与公开分支文件一致。
- 没有连接的安卓真机，用户仍需在小米 14 上确认实际交互；自动化通过不等同真机验收。

## 安装包核验

- Artifact：`12403698576`，SHA-256 `2b749cca2e9601c71dc7c4a4a74424170c4d79da66bd234de2678682adb95d04`。
- APK：4,963,965 字节；SHA-256 `ef263850d3f523b5515a9ab4ca701791da2bfa2096c3737ef74be008608f1eda`；Git blob `8482aec5d4db2f387ec419526aefcd1ef342d9c5`。
- ZIP：4,964,105 字节；SHA-256 `724d65c0aab5e5b872c9e70c9bfaa5be8a328e811c260b9c48d73900dab2f1b0`；Git blob `18c5a433447d8a41136490e6e66273110b433ae7`。
- 签名证书 SHA-256：`0c1ce08f08273587d4930f96e524e2f192dbbe80f46c4cc36f4d599f4605ed6c`，与原有版本一致，可覆盖安装。
- 交付提交：`c1c2570d311b7cc8550dd6210dc413851322e9c1`。
- [ZIP 安装包](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/c1c2570d311b7cc8550dd6210dc413851322e9c1/downloads/anime-shelf-0.1.6-install.zip)；[直接 APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/c1c2570d311b7cc8550dd6210dc413851322e9c1/downloads/anime-shelf-0.1.6.apk)。解压 ZIP 后打开 APK 覆盖安装，不要先卸载，保留旧数据。

源码与下载仅在 `anime-shelf-apk` 分支；main 核对仍为 `414ff14c6c5a2bc34adee7fa4cbf40893c67817b`。

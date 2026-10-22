# 当前交付状态

已生成可安装的安卓测试 APK：番匣 0.1.0，包名 `com.animeshelf.app`，最低 Android 8.0（API 26）。

- 已写入：B 站接口解析、实际清晰度选择、双轨下载、离线播放器、选集、倍速、缩放、观看记录、官方网页登录、本地文件导入。
- 已完成：源码人工审查、Java 语法解析、XML 格式检查、60 项解析回归断言、Android 编译、Android Lint、APK 签名校验。
- 未完成：安卓实体设备运行测试。
- 未验证：截图中《JOJO》的具体番剧能否在用户账号下下载。
- 构建环境：经用户授权，使用 GitHub Actions 在独立的 `anime-shelf-apk` 分支完成构建，原仓库 main 分支保持不变。

仓库提供 GitHub Actions 构建工作流 `.github/workflows/build-apk.yml`。构建会执行解析回归测试、编译、Lint 和签名校验，再上传 APK、包信息和 Lint 报告。当前是开发签名的测试版，尚未作为生产发行版验证。

源码不包含二进制 Gradle wrapper JAR。`gradlew` 可调用本机已安装的 Gradle 8.9；也可以先用 `gradle wrapper --gradle-version 8.9` 生成完整 wrapper。需要 Android SDK 35 和 JDK 17 或以上。

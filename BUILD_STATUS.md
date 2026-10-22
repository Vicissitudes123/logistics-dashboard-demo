# 当前交付状态

这是源码初稿，不是已验证可用的 APK。

- 已写入：B 站接口解析、实际清晰度选择、双轨下载、离线播放器、选集、倍速、缩放、观看记录、官方网页登录、本地文件导入。
- 已完成：源码人工审查、Java 语法解析、XML 格式检查、构建脚本 shell 语法检查。
- 未完成：Android 编译、Android Lint、解析回归测试执行、安卓设备运行测试。
- 未验证：截图中《JOJO》的具体番剧能否在用户账号下下载。
- 阻塞原因：当前云端环境无法完成编译依赖的联网下载；没有生成 APK 文件。

仓库提供一个手动触发的 GitHub Actions 构建工作流 `.github/workflows/build-apk.yml`，可在有网络的构建环境运行；当前并未上传到 GitHub，也未运行该工作流。

源码不包含二进制 Gradle wrapper JAR。`gradlew` 可调用本机已安装的 Gradle 8.9；也可以先用 `gradle wrapper --gradle-version 8.9` 生成完整 wrapper。需要 Android SDK 35 和 JDK 17 或以上。

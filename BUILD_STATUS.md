# 当前交付状态

当前源码为番匣 **0.1.1 修复候选版**，`versionCode=2`，包名 `com.animeshelf.app`，最低 Android 8.0（API 26）。本轮 APK 尚待对应提交的 CI 构建与校验；不能将 0.1.0 的通过结果当作 0.1.1 的验证结果。

## 已有证据

- 0.1.0 已生成并交付测试 APK，通过 60 项解析回归断言、Android 编译、Lint 和 APK 签名校验。
- 用户实际使用 0.1.0 后反馈了登录提示、在线播放 HTTP 错误和下载失败。截图不能确定具体 HTTP 状态码，也不能单凭截图归因于 DRM、编码、会员或网络出口。
- 源码确认存在备用地址丢失、媒体请求头未统一、登录完成未验证 API 会话及错误细节不足等问题。

## 本轮修复与验证

- 0.1.1 保留并尝试接口提供的备用媒体地址，保持 Range、缓存键和请求语义，统一 User-Agent 与 Referer。
- 登录完成时验证当前 API 会话；网页 App 唤起提示与真实登录状态分别处理。
- 播放与下载失败显示可取得的 HTTP 状态、音画轨和主机，不暴露 Cookie、完整签名链接或服务器响应正文。
- CI 执行解析回归测试、JUnit/Robolectric 单元测试、Android 编译、Lint 和签名校验；单元测试使用合成响应及模拟数据源，不访问用户账号或实际 CDN。
- artifact 包含 APK、包信息、`apk-signature.txt` 证书报告、单元测试结果和 Lint 报告。
- 继续使用 `anime-shelf-debug-signing-v2` 缓存签名密钥。应对比新旧 APK 的签名证书 SHA-256 后确认能否覆盖更新；缓存键相同本身不代表签名相同。

**尚未确认：**用户设备上先前失败的实际根因，以及 0.1.1 在该账号、视频和网络环境下是否能成功播放或下载。截图中的《JOJO》具体番剧仍需实测。自动化通过不等于已完成真机验证。

## 构建环境

经用户授权，使用 GitHub Actions 在独立 `anime-shelf-apk` 分支构建，原仓库 main 分支保持不变。当前是开发签名的测试版，尚未作为生产发行版验证。

源码不包含二进制 Gradle wrapper JAR。`gradlew` 可调用本机已安装的 Gradle 8.9；也可以先用 `gradle wrapper --gradle-version 8.9` 生成完整 wrapper。需要 Android SDK 35 和 JDK 17 或以上。

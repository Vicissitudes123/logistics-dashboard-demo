# 当前交付状态

当前交付为番匣 **0.1.1 测试版**，`versionCode=2`，包名 `com.animeshelf.app`，最低 Android 8.0（API 26）。对应源码提交 `44a2e79f2e65c2d5dfcb6bf101f344df57b1b11a` 已完成 CI 构建与校验。

## 本次交付证据

- GitHub Actions：[构建 39703457406](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/39703457406)，结果成功。
- 87 项解析断言通过；16 项 JUnit/Robolectric 测试通过，0 失败、0 跳过；Android 编译与 Lint 通过。
- APK v2 签名验证通过，证书 SHA-256 与已交付的 0.1.0 完全一致：`0c1ce08f08273587d4930f96e524e2f192dbbe80f46c4cc36f4d599f4605ed6c`。新包可覆盖安装，保留本地应用数据。
- `anime-shelf-0.1.1.apk`：4,905,850 字节；SHA-256：`47c844996876bb17da5a3373a7336725bdc999d4c5de938340029cf1b11b2df0`。
- 构建 artifact：`12393678189`；下载 ZIP 的 SHA-256 与 GitHub 记录一致，解压完整性通过。

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
- 继续使用 `anime-shelf-debug-signing-v2` 缓存签名密钥；本轮已实际对比新旧 APK 证书 SHA-256，确认一致。

**尚未确认：**用户设备上先前失败的实际根因，以及 0.1.1 在该账号、视频和网络环境下是否能成功播放或下载。截图中的《JOJO》具体番剧仍需实测。自动化通过不等于已完成真机验证。

## 构建环境

经用户授权，使用 GitHub Actions 在独立 `anime-shelf-apk` 分支构建，原仓库 main 分支保持不变。当前是开发签名的测试版，尚未作为生产发行版验证。

源码不包含二进制 Gradle wrapper JAR。`gradlew` 可调用本机已安装的 Gradle 8.9；也可以先用 `gradle wrapper --gradle-version 8.9` 生成完整 wrapper。需要 Android SDK 35 和 JDK 17 或以上。

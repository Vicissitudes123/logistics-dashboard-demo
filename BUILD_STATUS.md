# 当前交付状态

番匣 **0.1.7 / versionCode 8**，包名 `com.animeshelf.app`，Android 8.0（API 26）及以上。源码提交 `b40bc501522dfbba25971b762c0590a6d782a5a8` 在[构建 39972143291](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/39972143291)完成测试、编译、签名和发布。

## 本轮改动

- 系统文件选择器导出/恢复 JSON 片库、进度、管理标记和设置。恢复前先预览确认，按番剧/集 ID 合并，保留本机较新或同时间的进度；完整验证后一次偏好事务写入。
- 备份采用字段白名单，不包含 Cookie、账号、签名媒体地址、下载索引或本地 URI 权限。本地视频及其观看记录不恢复；需要重新选择文件。备份最大 4 MiB，包含数量与嵌套限制。
- 下载页新增已下载/总大小、速度、剩余时间、可用空间与真实排队/网络等待状态。独立单调时钟采样不受界面刷新影响，未知总量不伪造百分比；403与地址失效分开说明。内部空间不足32 MiB时在入队前提示。
- 全屏新增锁定：锁后只保留常显解锁入口，禁止普通按钮和所有画面手势，恢复临时长按速度并取消未提交拖动。系统返回先解锁；退出全屏/后台/结束播放恢复正常。
- 画质偏好只保存 qn 和视频编码；普通选择时将同档位优先显示。自动连播默认关闭，设置可开启；下一集缺同档位时提示手动选择，不自动降低档位。离线缺集或缺同档位不自动联网。
- 取消过期解析，修正后台返回与主动重播的导航基准，避免重新播放后连播失效。
- 首页封面懒加载与缓存，旧片库异步补图，封面元数据任务不占用媒体解析队列。封面失败保留占位；标题、搜索、排序和管理操作正常使用。
- 首页观看状态及手工看完标记，不改动每集续播位置。无手工覆盖时全部集数有完整进度才自动显示看完。

## 验证

- ParserSelfTest 132 项断言通过。
- 191 项 JUnit/Robolectric 测试通过，0失败、0错误、0跳过；19个suite，新增64项覆盖备份、下载测速/状态、锁定、连播、图片请求/解码/缓存及Activity集成。
- 首轮3项封面测试失败：Robolectric Legacy 将测试用1200×600 PNG编码为2,882,137字节，触发2MiB限制。改为Native图像编解码并添加fixture大小前置断言后全过；保留全部行为断言，生产限制未放宽。
- Android编译、Lint与签名校验通过。Lint无错误，18项警告；包含可用空间API建议，空间数值以内部文件分区实际剩余字节为准，不估计系统可回收缓存。
- Artifact SHA-256与平台摘要一致；外层ZIP与APK内部CRC、包名/版本、CI签名报告及APK实际证书摘要核对通过。
- 发布job再次校验身份、文件字节和ZIP包装。本地APK/安装ZIP的SHA-256、Git blob SHA和大小与公开文件一致。
- 未连接安卓真机；小米14上的系统文件选择器、实际播放手势、网络环境和后台行为仍需安装验证。自动测试不等同真机验收。

## 安装包核验

- Artifact：`12509177899`；SHA-256 `f526eca291ecc27c42514f9c7431ebf94599f66a83fa4b79db56a4af524ec78c`。
- APK：5,000,353 字节；SHA-256 `04f4a8c77cf18e114dfb7a58b44dfbcf2fd3ed242631771737a7e2ff8ccd888d`；Git blob `31073ab00002aed693432d5b55dac91226fd5e91`。
- 安装ZIP：5,000,493 字节；SHA-256 `015076cf9314723ee33e4888702d183eb54a96fddf7c24350901e4dda758462e`；Git blob `0dda79b6ae9bc4812b79eac02a5e0df0e1e623d0`。
- 签名证书 SHA-256：`0c1ce08f08273587d4930f96e524e2f192dbbe80f46c4cc36f4d599f4605ed6c`，与旧版一致，可覆盖安装。
- 交付提交：`de2ebd74ddbc8e07a47a3754c3628598edee20ac`。
- [ZIP安装包](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/de2ebd74ddbc8e07a47a3754c3628598edee20ac/downloads/anime-shelf-0.1.7-install.zip)；[直接APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/de2ebd74ddbc8e07a47a3754c3628598edee20ac/downloads/anime-shelf-0.1.7.apk)。解压ZIP后打开APK覆盖安装，不要先卸载。

仅开发分支 anime-shelf-apk 更新。main仍为`414ff14c6c5a2bc34adee7fa4cbf40893c67817b`；0.1.6存档分支仍指向`1f21e9b8ca9d009797f20d95551558472f1021f7`。

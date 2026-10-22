# 当前交付状态

番匣 **0.1.5 / versionCode 6**，包名 `com.animeshelf.app`，Android 8.0（API 26）及以上。源码提交 `bfebc6ecfd8e166bcd6e91fe69074d191f707237` 在[构建 39722429603](https://github.com/Vicissitudes123/logistics-dashboard-demo/actions/runs/39722429603)通过检查并完成 APK、ZIP 发布。

## 本轮改动

- 左侧纵滑调整当前播放窗口亮度 5%～100%，保留右侧音量和横滑进度。不修改全局亮度，无新增权限；退后台与返回首页恢复原窗口亮度。
- 画面长按约半秒临时 2 倍速，原速高于 2 倍时不降速；松手恢复长按前速度，不写入保存的倍速。暂停画面不自动播放，按钮和进度条不触发画面长按。
- 多指、滑动、触摸取消、失去焦点、退出或暂停到后台均取消临时加速；双击与长按互斥，长按后移动不会同时提交进度跳转。
- 播放器控件去掉常驻底色和边框，保留白色图标/文字、按压反馈，以及 32dp 图标和 48dp 点击区域。
- 剧集长按进入原页面多选并勾选该集，支持全选、取消和批量下载；本地导入视频不重复下载。
- 离线视频长按进入多选删除，目录长按番剧选择其全部版本，支持跨组选。点击只切换选择，返回先退出选择；进度刷新不清空选择。删除仍按确认时的版本快照操作，保留观看记录和其他版本。
- 保留既有媒体请求、HDR SurfaceView 输出、离线存储和签名身份。

## 验证

- 解析检查 105 项断言通过。
- JUnit/Robolectric 106 项通过，0 失败、0 错误、0 跳过：批量 9、目录 7、导航/窗口生命周期 16、多选 11、错误诊断 12、媒体传输 12、手势状态机 12、图标密度 4、控制层 14、视频信息 9。
- 覆盖长按阈值、0.75/1.5 倍恢复、取消/多指/拖动、双击冲突、暂停不加速、保存速度不变，以及窗口亮度暂停/恢复/回首页。
- Android 编译、Lint 和 APK 签名校验通过，首轮 CI 即通过。发布 job 再次验证身份、字节和 ZIP 包装。
- Artifact ZIP 摘要、APK 与安装 ZIP 的 CRC/SHA-256、版本和证书报告已核对；本地文件与公开文件 Git blob SHA/大小一致。
- 无连接的安卓真机；用户仍需在小米 14 上确认亮度、长按手感与列表多选。自动化通过不等同真机手势验收。

## 安装包核验

- Artifact：`12401279604`。
- APK：4,952,081 字节；SHA-256 `2eba278b4196e7e236ce37be9adcdea08f53442cb971c8005e7d98cd868d4093`。
- APK Git blob：`086f98c928ecd1ff3bcb451050a12fa660551000`。
- ZIP：4,952,221 字节；SHA-256 `e4986b7e131051fc7b17dfb675ab8c89bc777c6399e31ced21b8c788e0aa4d0a`。
- ZIP Git blob：`16ac830dce7e5ed18b6869fa474bfbdfaac5852d`。
- 签名证书 SHA-256：`0c1ce08f08273587d4930f96e524e2f192dbbe80f46c4cc36f4d599f4605ed6c`，与原有版本一致。
- 交付提交：`89b148bdd4d7e237a3011c55f5e56170c5cc1e39`。
- [ZIP 安装包](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/89b148bdd4d7e237a3011c55f5e56170c5cc1e39/downloads/anime-shelf-0.1.5-install.zip)；[直接 APK](https://raw.githubusercontent.com/Vicissitudes123/logistics-dashboard-demo/89b148bdd4d7e237a3011c55f5e56170c5cc1e39/downloads/anime-shelf-0.1.5.apk)。解压 ZIP 后打开 APK 覆盖安装，不要先卸载，保留旧数据。

源码与下载只在 `anime-shelf-apk` 分支；原 main 核对仍为 `414ff14c6c5a2bc34adee7fa4cbf40893c67817b`。

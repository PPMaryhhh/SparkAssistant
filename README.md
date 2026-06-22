# 微博火花助手（Android / 小米）

这是一个独立 Android 工程，不会修改同目录中的 Python 脚本。它通过 Android 无障碍服务在微博前台辅助完成以下步骤：

1. 从“互相关注”列表选择未处理用户；
2. 打开主页和私信页，填入“续个火花✨”；
3. 用户在浮窗确认后发送；
4. 返回主页，打开第一处可见评论入口并填入“踩踩宝贝”；
5. 用户再次确认后发送，记录该用户并返回互关列表。

## 构建 APK

### 推荐：GitHub Actions 云端构建

本工程已经加入 `.github/workflows/build-apk.yml`，本机不需要安装 Android SDK 或 Gradle。

1. 在 GitHub 新建空仓库，把 `WeiboSparkAssistant` 文件夹中的全部内容上传到仓库根目录。上传后仓库根目录应直接看到 `.github`、`app`、`build.gradle` 和 `settings.gradle`。
2. 打开 GitHub 仓库的 `Actions` 页面。
3. 左侧选择 `Build Weibo Spark APK`。
4. 点击 `Run workflow`，再点击绿色的 `Run workflow` 确认。
5. 构建完成后打开本次运行，在页面底部 `Artifacts` 下载 `WeiboSparkAssistant-debug-apk`。
6. 解压后得到 `app-debug.apk`，传到小米手机即可安装。

每次修改并推送 `WeiboSparkAssistant` 目录，云端也会自动重新构建。

### Android Studio 本地构建

1. 用 Android Studio 打开本目录 `WeiboSparkAssistant`。
2. 等待 Gradle 同步完成（需要 Android SDK 35 和 JDK 17）。
3. 选择 `Build > Build APK(s)`。
4. 调试 APK 通常位于 `app/build/outputs/apk/debug/app-debug.apk`。

当前电脑未检测到 Android SDK/Gradle，因此仓库内没有预编译 APK。

## 手机上使用

1. 安装 APK，打开应用并设置私信文案、评论文案、等待时间和单次人数。
2. 点击“开启无障碍服务”，找到“微博火花助手”并允许。
3. 打开微博，进入 `关注 -> 互相关注` 列表。
4. 点击右上角浮窗“开始”。
5. 每次浮窗出现“确认发私信”或“确认发评论”时，核对当前对象后再点击。

小米/澎湃 OS 若频繁停止服务：在应用详情中允许自启动，将省电策略设为“不限制”，并把应用锁定在最近任务中。

## 已做的保护

- 每次真正发送前都必须人工确认；
- 单次人数限制为 1～50，默认 20；
- 步骤间隔限制为 2～15 秒，默认 4 秒；
- 已处理用户保存在本机，避免重复发送；
- 浮窗可随时暂停、重试或停止；
- 只读取微博包 `com.sina.weibo` 的前台页面，不联网、不读取账号密码。

## 现实限制

微博没有为这类流程提供公开稳定接口，本工程依赖页面可访问文本（如“私信”“评论”“互相关注”）。微博版本、字体大小或灰度界面变化，都可能导致某一步无法识别。程序遇到不确定页面会暂停，不会盲点；把页面手动调整到正确位置后点“继续/重试”即可。

# 夸克网盘 Emby 剧集整理工具

一个把夸克网盘里的剧集文件批量整理成 Emby 标准格式的 Android 应用（Kotlin 编写）。

## 功能

- **夸克网盘登录**：内置 WebView 完成官方登录，凭证（Cookie）本地加密保存（Android Keystore + EncryptedSharedPreferences），不触碰账号密码明文。
- **网盘文件管理**：自建文件浏览器，支持进入目录、刷新、重命名、移动到指定目录、删除。
- **Emby 批量重命名（核心）**：对剧集文件夹内文件做多步向导处理。
  1. 输入剧名 → TMDB 搜索元数据（需个人 API Key）或跳过直接用本地解析。
  2. 选择匹配结果。
  3. 预览变更计划：文件名按模板重命名（默认 `剧名.S01E01`），并检测集数冲突。
  4. 确认后一键执行：创建 `Season 01` 等归档文件夹、重命名、移动视频与配套字幕。
  5. 结果回写本地任务日志。
- **设置**：TMDB API Key 与语言、文件名模板、Season 文件夹模板、调试（仅预览）开关、任务日志查看、退出登录。
- **安全**：默认开启"仅预览"调试开关，确认无误后再写入网盘。

## 技术栈

Kotlin · AndroidX · Material 3 · OkHttp · Coroutines · EncryptedSharedPreferences · 逆向自研夸克云盘接口

## 构建

需要 JDK 17 与 Android SDK 34。

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 说明

- 夸克网盘没有官方开放文件 API，本项目通过逆向 Web/移动端 `drive.quark.cn` 云盘接口实现，可能随官方更新而失效。
- 依赖登录时抓取的 Cookie 与设备头，接口签名若变化需在 `QuarkApi.kt` 的 `commonHeaders()` 中补充。
- TMDB 元数据搜索需在设置页填入个人 TMDB v3 API Key（免费申请）。
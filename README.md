# Ordify

夸克网盘剧集批量整理工具（原名「夸克网盘 Emby 剧集整理工具」）。Android 原生应用，Kotlin 编写，纯 View 体系 + Material 3 暗色主题。

把网盘里 `01-4K.高码率.mp4` 这类散乱文件，一键整理成 Emby / Jellyfin 可识别的标准命名：

| 场景 | 整理结果 |
|------|----------|
| 本地解析，季号留空 | `毛骗.01.mp4` |
| 本地解析，填季号 1 | `毛骗.S01E01.mp4` |
| TMDB 刮削（含剧集标题） | `九门.S01E01.百人部队深夜离奇失踪.mp4` |

同时自动创建 `Season 01` 归档文件夹，把视频与配套字幕一起移动进去。

## 功能

- **Cookie 登录**：粘贴夸克网盘 Cookie 即可使用，凭证本地加密保存（Android Keystore + EncryptedSharedPreferences），无需账号密码。
- **文件浏览**：面包屑路径导航（可点击任意层级直接跳转）、排序（名称/大小/时间）、刷新、多级返回（系统返回键逐级回退目录）。
- **文件管理**：长按弹出操作菜单——批量重命名、重命名、移动到、设为首页目录、删除（红色警示样式）。
- **批量重命名向导（核心）**：
  1. 输入剧名（自动清洗 `九门(2026)` → `九门`、`候车室的故事 (2002) {tmdb-111436}` → `候车室的故事`）。
  2. TMDB 搜索（展示海报便于确认）→ 确认所选剧集 → 自动拉取季信息，直达预览；或选择本地解析（季号可选）。
  3. 预览全部变更：修改前 → 修改后，逐项勾选，集数冲突高亮标红。
  4. 执行：创建 Season 文件夹、重命名、移动文件，实时进度条。
  5. 结果写入本地任务日志。
- **集数识别（10 级策略）**：支持 `S01E01`、`第01集`、`EP01`、`剧名.30`、`剧名-30`、`[30]`、`30话`、`第X季第Y集`、`30v2`、纯日期等格式，36 组用例验证。
- **任务日志**：每次整理的成功/失败明细，支持复制与一键清理。
- **设置**：TMDB API Key（含有效性测试）与语言、Season 文件夹模板、调试模式（仅预览不写入网盘）、首页目录、退出登录。

## 技术栈

Kotlin · AndroidX（Fragment / ViewBinding / RecyclerView）· Material 3 · OkHttp · Coroutines · EncryptedSharedPreferences · 纯 View 体系（未使用 Compose）

## 构建

需要 JDK 17 与 Android SDK 34。

```bash
# 调试版（可直接安装）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 正式版（需要签名配置，见下）
./gradlew assembleRelease
```

### Release 签名

在项目根目录创建 `keystore.properties`（已被 .gitignore 忽略，不会入库）：

```properties
storeFile=your.keystore
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

文件存在时 release 自动签名；不存在时产出未签名 APK，构建不会失败。

## 用 GitHub Actions 构建测试版

仓库自带手动触发的构建工作流（`.github/workflows/build-apk.yml`）：

1. 进入仓库 **Actions** 页面 → 选择「手动构建 APK」→ **Run workflow**。
2. 选择构建类型：`debug`（默认，最快）或 `release`（CI 内自动用临时密钥签名，可安装，但与本地正式签名不同，不能覆盖升级正式版）。
3. 构建完成后在该次运行页面底部 **Artifacts** 下载 APK，命名格式 `Ordify-v{版本}-{提交号}-{类型}.apk`，保留 30 天。

## 项目结构

```
app/src/main/java/com/quarkemby/app/
├── MainActivity.kt           # 宿主：底栏导航、返回分发
├── QuarkEmbyApp.kt           # Application 入口
├── data/
│   ├── QuarkApi.kt           # 夸克网盘接口（列表/重命名/移动/删除/建目录）
│   ├── TmdbApi.kt            # TMDB 搜索、季信息、剧集标题
│   ├── Prefs.kt              # 加密偏好存储（Cookie/Key/模板/日志）
│   └── models/Models.kt      # 数据模型
├── ui/
│   ├── LoginFragment.kt      # Cookie 登录
│   ├── FilesFragment.kt      # 文件浏览 + 面包屑 + 长按菜单
│   ├── FileAdapter.kt        # 文件列表适配器
│   ├── RenameWizard.kt       # 批量重命名向导（4 步）
│   ├── SettingsFragment.kt   # 设置页
│   ├── LogFragment.kt        # 任务日志
│   └── Ui.kt / Img.kt        # UI 组件工厂 / 图片加载
└── util/
    ├── EpisodeParser.kt      # 10 级集数识别
    ├── RenamePlanner.kt      # 重命名计划生成与冲突检测
    ├── ShowNames.kt          # 剧名清洗（去年份/tmdb-id 等）
    └── CrashLog.kt           # 崩溃与渲染异常记录
```

## 说明

- 夸克网盘无官方开放文件 API，本项目通过逆向 `drive.quark.cn` 云盘接口实现，可能随官方更新失效；接口签名变化需在 `QuarkApi.kt` 的 `commonHeaders()` 中补充。
- TMDB 功能需在设置页填入个人 [TMDB v3 API Key](https://www.themoviedb.org/settings/api)（免费申请），不填也可正常使用本地解析。
- 应用包名 `com.quarkrename.app`，与早期 `com.quarkemby.app` 调试包不互通。

## 下载

正式版前往 [Releases](https://github.com/Yuyaniel/Ordify/releases) 页面；测试版用上述 Actions 流程自取。

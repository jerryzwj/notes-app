# 记事本

个人用的笔记应用：一个 Cloudflare Worker 同时提供网页和 API，数据存在 Upstash Redis。

- 线上地址：<https://note.wenjun.eu.org>（备用域名 <https://note.zhangwenjun.eu.org>，同一个 Worker）
- 源码与发版：<https://github.com/jerryzwj/notes-app>（公开仓库，APK 走 Release 附件）
- 访问密码：只放在本地 `.dev.vars` 的 `APP_PASSWORD` 里，不进仓库（改密码见下方「密钥」）
- 安卓：网页本身是 PWA，可直接安装；安卓客户端已改为 Kotlin + Jetpack Compose 原生版（`android-compose/`），旧的 Capacitor 壳保留但不再更新

## 功能

- 新建 / 编辑 / 删除 / 回收站 / 彻底删除 / 恢复
- 自动保存（450ms 防抖），离线也能继续写，联网后自动补同步
- 标题 + 正文搜索、标签筛选、置顶
- Markdown 预览（标题、列表、代码块、引用、链接、待办勾选、表格），打开笔记默认就是预览态，点正文或「编辑」进入编辑
- 编辑时顶部有格式化工具栏：加粗 / 斜体 / 标题 / 列表 / 待办 / 代码 / 引用 / 链接 / 分割线 / 粘贴（Ctrl+B、Ctrl+I 生效）
- 分享：为单条笔记生成免密只读链接 `/s/<随机码>`，可随时取消
- 单篇导出：编辑器工具栏「导出」把当前这篇存成自包含 HTML（排版和预览一致，双击就能读，也能直接打印成 PDF）
- 回收站、导出全部笔记为 JSON、把 JSON 导入回来（合并式，同 id 按更新时间取新的）
- 回收站里超过 30 天的笔记自动清掉（挂在列表请求上，最多每 6 小时跑一次，不需要 cron）
- 列表只拉标题和摘要，点开某条才拉正文；搜索走服务端全文（标题 + 正文 + 标签）
- 浅色为默认（米白纸底 + 白卡片 + 翠绿 `#0e9e74` 强调；列表是圆角卡片，按钮 / 搜索框 / 标签 / 标签页全走胶囊，大圆角 16/24px）；右上角太阳图标切到深色（深墨绿石墨底 `#111614` + 亮翠绿 `#45d6a6`），偏好存 `nn.theme`
- 手机窄屏自适应：列表与编辑区单页切换

## 结构

```
notes-app/
├─ wrangler.toml            # Worker 配置：assets + 自定义域名
├─ src/index.js             # 后端：鉴权 + 笔记 CRUD + Upstash REST
├─ public/                  # 前端（Worker 直接托管）
│  ├─ index.html
│  ├─ style.css
│  ├─ app.js
│  ├─ markdown.js           # 轻量 Markdown 渲染，主应用与分享页共用
│  ├─ share.html            # 公开只读分享页（/s/<slug> 复用它）
│  ├─ manifest.webmanifest  # PWA 清单
│  ├─ sw.js                 # Service Worker（静态资源缓存）
│  ├─ mark.svg              # 品牌标识（翠绿渐变圆角底 + 白色纸页），顶栏与分享页共用
│  └─ icon-*.png            # 应用图标（192 / 512 / maskable）
├─ android-compose/         # 安卓原生客户端（Kotlin + Jetpack Compose，当前主用）
├─ android-app/             # 旧 Capacitor 壳（已弃用，保留未删）
├─ android/                 # 更早的纯 WebView 壳（已弃用，保留未删）
├─ scripts/
│  ├─ make-icon.py          # 生成网页图标 + 安卓 mipmap/启动页（改动后重跑）
│  ├─ backup.ps1            # 本地备份：登录 → 导出 JSON → 保留最近 N 份
│  ├─ smoke_test.py         # 接口冒烟测试
│  ├─ share_test.py         # 分享功能自检
│  └─ maintenance.py        # 列出 / 导出 / 清空笔记
└─ .github/workflows/build-apk.yml
```

## 数据模型（Upstash Redis）

| Key | 类型 | 说明 |
| --- | --- | --- |
| `note:<id>` | Hash | `title / body / tags / pinned / createdAt / updatedAt / deleted / deletedAt` |
| `notes:index` | ZSet | member = 笔记 id，score = updatedAt，用来按时间排序 |
| `login:fail:<ip>` | String | 密码错误计数，1 小时窗口，超过 10 次返回 429 |
| `share:<slug>` | Hash | `noteId / createdAt`，16 位随机 slug，公开只读 |
| `share:by-note:<id>` | String | 存 slug，用于取回已有链接和取消分享 |
| `sys:trashSweep` | String | 上次清理回收站的时间戳，6 小时节流 |

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ping` | 健康检查（免鉴权） |
| POST | `/api/login` | `{password}` → `{token}`，令牌 HMAC 签名，180 天 |
| GET | `/api/session` | 校验令牌 |
| GET | `/api/notes` | 全量列表，含正文；`?meta=1` 只回元信息 + 摘要（不含正文）；`?q=` 服务端全文过滤；响应带 `count` 与 `swept` |
| POST | `/api/notes` | 新建，可带自定义 `id`（离线创建用） |
| GET | `/api/notes/:id` | 单条 |
| PUT | `/api/notes/:id` | 更新（幂等，离线队列也走它） |
| DELETE | `/api/notes/:id` | 移入回收站；`?purge=1` 彻底删除 |
| POST | `/api/notes/:id/restore` | 从回收站恢复 |
| POST | `/api/notes/:id/share` | 生成分享链接（已存在则复用），返回 `{slug, url}` |
| DELETE | `/api/notes/:id/share` | 取消分享，原链接立即失效 |
| GET | `/api/share/:slug` | 公开只读内容（免鉴权） |
| GET | `/s/:slug` | 公开分享页（免鉴权，`noindex`） |
| GET | `/api/export` | 导出全部笔记 JSON |
| POST | `/api/import` | 导入 `{notes:[...], mode:'merge'|'overwrite'}`，缺省 merge；返回 `{imported, skipped, total}` |
| POST | `/api/token/device` | 用现有令牌换 10 年有效的安卓免密令牌，返回 `{token, expiresIn, device}` |

除 `ping` / `login` / `share` 外都需要 `Authorization: Bearer <token>`。

分享的内容是「实时指向」的：作者后续修改，链接里看到的就是最新版本；笔记进回收站或彻底删除后，链接立刻 404。

## 部署 / 更新

```powershell
cd D:\code\notes-app
wrangler deploy
```

## 密钥

三个 secret，已写入线上 Worker：

```powershell
wrangler secret put APP_PASSWORD   # 登录密码
wrangler secret put AUTH_SECRET    # 令牌签名密钥（改了会让所有已登录设备失效）
wrangler secret put UPSTASH_TOKEN  # Upstash REST token
```

`UPSTASH_URL` 在 `wrangler.toml` 的 `[vars]` 里；域名在 `routes` 里，换成别的子域改一行再 `wrangler deploy` 即可。

本地调试用 `.dev.vars`（已在 .gitignore 里），内容与线上 secret 同名。

## 安卓

### 方式一：PWA（推荐，零维护）

1. 手机浏览器打开 <https://note.wenjun.eu.org>
2. Chrome 菜单 →「添加到主屏幕」/「安装应用」
3. 之后从桌面图标启动，全屏、离线可读已缓存内容

### 方式二：原生壳 APK

`android/` 是纯 WebView 壳（无第三方依赖）：

- 返回键先交给网页（关编辑器、清搜索），再退回或挂后台
- 导出 JSON 时写进系统「下载」目录
- 断网显示本地错误页

本机没有 Android SDK / JDK，用 GitHub Actions 出包：

```powershell
git init
git add .
git commit -m "notes app"
git remote add origin git@github.com:jerryzwj/notes-app.git
git push -u origin main
```

推送后 Actions 自动跑 `build-apk`，在 workflow 的 Artifacts 里下载 `notes-debug-apk`（debug 签名，个人自用可直接安装）。

也可以本地装 Android Studio 后直接 `gradle -p android assembleDebug`。

## 备份

导出 JSON 只是第一步，能定时落到本地才算真有备份。

### 手动跑一次

```powershell
powershell -NoProfile -File D:\code\notes-app\scripts\backup.ps1
```

产物在 `D:\code\notes-backup\`：`notes-YYYY-MM-DD.json` 加一行行 `backup.log`；默认保留最近 30 份，`-Keep 60 -OutDir D:\other` 可改。

密码取值顺序：`-Password` 参数 → 环境变量 `NOTES_PASSWORD` → 项目 `.dev.vars` 里的 `APP_PASSWORD`。

### 自动

已写进当前用户的启动项（`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`，键名 `NotesBackup`）：每次登录后以隐藏窗口跑一次，同一天重复跑只覆盖当天那份文件，不会堆垃圾。

想换成严格的「每天 21:30」，需要有管理员权限建计划任务（当前会话建不了，`schtasks` 返回 Access is denied）：

```powershell
schtasks /create /tn "notes-daily-backup" /tr "powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File D:\code\notes-app\scripts\backup.ps1" /sc daily /st 21:30 /f
```

以管理员身份跑完那条命令后，把启动项删掉，免得一天备份两次：

```powershell
Remove-ItemProperty -LiteralPath "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name NotesBackup
```

### 导回来

网页右上角「⋯」菜单里的「导入备份」选中 `notes-*.json`，确认后按 id 合并：同 id 且备份里的更新时间不晚于现有数据就跳过，不会删除你现在的内容。

### 导出文件长什么样

唯一一份「给机器」的导出是全量 JSON（右上角「⋯」菜单里的「导出全部（JSON）」）。想看单篇、想读：编辑器工具栏的「导出」生成 `标题.html`，单文件自包含、不依赖网络，排版跟预览一模一样，打印时自动去掉底色和边框。

```json
{ "exportedAt": "2026-11-08T03:08:58.868Z",
  "notes": [ { "id": "...", "title": "我的各种token",
               "body": "- Redis数据库:\n`curl ...`",
               "tags": [], "pinned": false,
               "createdAt": 1794103393326, "updatedAt": 1794107248481,
               "deleted": false, "deletedAt": 0 } ] }
```

正文就在 `body` 字段里，和编辑器里写的一模一样（Markdown 原文）。看着"不像一篇笔记"，是因为 JSON 把换行写成了字面的 `\n`。想看正常的多行文本：

```powershell
(Get-Content D:\code\notes-backup\notes-2026-11-08.json -Raw | ConvertFrom-Json).notes[0].body
```

或者直接拖进支持 JSON 的编辑器（VSCode / 浏览器）折叠查看。

## 维护脚本

```powershell
$env:NOTES_PASSWORD = "<你的访问密码>"
python scripts\smoke_test.py              # 全量接口自检
python scripts\share_test.py              # 分享流程自检（12 项）
python scripts\maintenance.py list        # 列出全部笔记
python scripts\maintenance.py export backup.json
python scripts\maintenance.py clear --yes # 清空（慎用）
```

`NOTES_BASE` 默认线上域名，本地调试设为 `http://127.0.0.1:8787`。

## 已知限制

- 单用户：一个密码、一个令牌，没有多账号
- 分享链接是「持有即可读」：谁拿到链接都能看，不想要了在编辑器里点「分享 → 取消分享」
- 列表只拉元信息和 100 字摘要，全文搜索靠服务端；未缓存正文的笔记离线时搜不到，点开也需要联网
- 冲突策略是「最后写入覆盖」，同一篇笔记多端同时编辑会丢其中一侧
- 回收站清理挂在列表请求上：只要不来访问就不会清，超过 30 天的条目要等下一次打开网页
- 本机 IPv6 到 Cloudflare 不通，脚本里强制走 IPv4；浏览器和手机不受影响
- 备份靠登录时触发，电脑关着的那几天不会产生新备份（可用上面的管理员命令改成定时）
- 密码错误计数用的是 Upstash，跨地域延迟下登录本身会慢一点点

## 安卓 App（Kotlin + Jetpack Compose 原生版，当前主用）

WebView 壳的界面观感一直不满意，所以安卓端改成纯原生重写：后端还是同一个 Cloudflare Worker，接口和数据一个字没动，App 只是换了一个直连 HTTP API 的客户端。

- 工程：`android-compose/`，包名 `eu.wenjun.notes`（与旧壳一致，可直接覆盖安装），`versionCode 4` / `versionName 2.2-native`
- 技术栈：Kotlin 1.9.22 + Compose Compiler 1.5.8 + AGP 8.2.1 / Gradle 8.2.1 + Compose BOM 2024.02.00 + Material3 + OkHttp 4.12 + org.json，`minSdk 24` / `targetSdk 34` / `compileSdk 34`
- 没有 Retrofit、Moshi、Navigation：一个 OkHttp 封装 + 一个 `AndroidViewModel` + `openNote` 状态切换列表和详情，依赖少、编译快
- 主题直接继承 `android:Theme.Material.NoActionBar`，不建 `values-night` 变体（避免 `ResourceCycle` 自继承问题）；输入框统一用 `BasicTextField`（material3 1.2.0 的 `OutlinedTextField` 没有 `contentPadding`）
- release 复用本机 debug 签名（可直接装），开了 R8 + 资源压缩，APK 约 1.5 MB

### 代码地图

| 文件 | 作用 |
| --- | --- |
| `MainActivity.kt` | 入口：`NotesTheme` + 按 `token` / `openNote` / `settingsOpen` 分流登录、列表、详情、备份页；页面切换用 `AnimatedContent`（淡入 + 轻微横向滑入）；底部 toast |
| `Api.kt` | OkHttp 直连 Worker：`login`（`device:true`）、`notes(?meta=1)`、`note(id)`、`createNote`、`saveNote`、`trash/restore`、`share`；请求带 `User-Agent: NotesNative/2.0 (Android)`；请求抛 IO 异常时自动换另一条域名重试一次 |
| `Host.kt` | 线路优选：两个域名（`note.wenjun.eu.org` / `note.zhangwenjun.eu.org`）指向同一个 Worker；用静态清单文件测速取快的，结果存 `notes-host` 偏好文件，6 小时后重新测 |
| `Update.kt` | 版本更新检查：读 GitHub `releases/latest`，拿 `tag_name` 跟 `BuildConfig.VERSION_NAME` 比数字段（`v2.1` vs `2.1-native` 也认），优先取 release 里的 `.apk` 资产地址，仓库名默认 `jerryzwj/notes-app` 存在 `notes-update` 偏好文件 |
| `WebDav.kt` | 极简 WebDAV 客户端（OkHttp）：`MKCOL` 建目录、`PUT` 写文件、`GET` 读文件，Basic 认证，中文路径逐段 URL 编码；配置存 `webdav-config` 偏好文件 |
| `Backup.kt` | 把每篇笔记渲染成可读 Markdown（标题 / 标签 / 更新时间 / 笔记 ID + 正文），写到 WebDAV 的「记事本备份/」目录，另写 `_备份记录.md`；`lastRecord()` 读回上次备份时间与篇数 |
| `Session.kt` | 令牌存应用私有 SharedPreferences（`notes-session`），退出登录即清 |
| `Theme.kt` | 设计令牌：`Look.dark` 开关 + 两套色值（浅色 `#F6F8F5` 底 / `#FFFFFF` 面板 / `#0E9E74` 翠绿；深色 `#111614` 底 / `#161C19` 面板 / `#45D6A6` 亮翠绿），`Bg`/`Panel`/`Ink`/`Accent` 等按当前外观取值，切主题所有界面自动跟着变 |
| `Markdown.kt` | 轻量 Markdown：标题、正文、代码块、引用、列表、待办、分割线，行内加粗/斜体/行内代码/链接/删除线 |
| `NotesViewModel.kt` | 令牌、列表、tab、搜索词、打开中的笔记、保存状态、toast；401 自动清令牌回登录页；列表里改置顶会先 GET 全文再 PUT，避免把正文清空；新建走「本地空白稿 + 首次保存时 PUT 建笔记」，不阻塞界面 |
| `NoteCache.kt` | 全文缓存 + 列表缓存（同一份 `notes-cache` 偏好文件）：单篇正文最多 40 条，列表摘要一份；列表加载完还会预取最近 12 篇，点开时时间戳一致就直接用缓存 |
| `LoginScreen.kt` | 品牌图标 + 密码框 + 登录按钮 |
| `BackupScreen.kt` | WebDAV 备份设置页：地址 / 用户名 / 密码三个输入框、「保存设置」「立即备份」两个按钮、状态行显示「上次备份：时间（N 篇）」，并说明备份的是可读 Markdown |
| `ListScreen.kt` | 顶栏（品牌、「+」、⋯ 菜单里的刷新/退出）、搜索框、全部/置顶/回收站三个 tab、行式列表（标题、2 行摘要、时间、最多 3 个标签、置顶标记）、每行 ⋯ 菜单（置顶/分享链接/删除，回收站里是恢复/彻底删除） |
| `EditorScreen.kt` | 标题 / 标签 / 正文三段输入，600ms 防抖自动保存，右上「预览 / 编辑」切换，底部格式化工具栏（加粗、斜体、标题、列表、待办、代码、引用、链接、分割线、粘贴），⋯ 菜单里置顶 / 分享链接（复制到系统剪贴板）/ 删除；返回键会先落库再回列表 |

### 功能

- 登录一次即长期免密（拿 10 年 device 令牌），改网页密码不影响这台手机
- 列表只拉标题摘要（`?meta=1`），点开某条才拉正文；搜索走服务端
- 新建 / 编辑 / 置顶 / 分享链接 / 删除 / 回收站恢复 / 彻底删除 / 退出登录
- 打开已有笔记默认是预览态，点右上「编辑」才进编辑；空白新笔记直接给编辑
- 工具栏按钮按光标位置插入 Markdown（选中文字会被包裹），「粘贴」直接读系统剪贴板插到光标处
- 打开笔记不再等云端：列表加载完会后台预取最近 12 篇正文（每批 3 条并发），点开时若本地缓存时间戳与列表一致就立即渲染；实测直连云端取单篇约 800ms（4 次 864/772/825/779ms），列表约 1.1s
- 缓存只在 `updatedAt` 与列表元信息一致时生效，别处改过的笔记不会被旧缓存冒充；删除 / 退出登录会清掉对应缓存
- 新建不再等云端：点「+」直接在本地开一张空白稿、立刻进编辑页，第一次自动保存的 PUT 会按 id 把笔记建到后端；什么都没写就返回，云端不会多出空笔记
- 下拉刷新：列表拉到底再往下拖，顶部露出「下拉刷新 / 松开刷新」，松手过 64dp 阈值就重新拉列表；松手后顶部换成「正在刷新…」提示条一直挂到数据回来，随后显示 0.7 秒「已刷新」再收起。右上角 ⋯ 菜单里的「刷新」也保留
- 冷启动不空屏：上次的列表摘要在本地存一份，重开 App 立刻摆出来，同时在后台拉最新；本地还没缓存时显示「正在同步云端笔记…」+ 骨架条，不再是空白。启动窗口背景跟当前浅色主题同色（`#F6F8F5`）+ 居中标识，点开图标不会先闪一下
- 外观开关：默认浅色（米白底 + 白卡片 + 翠绿强调），⋯ 菜单里「外观：浅色/深色」点一下整 App 立即换色（列表、编辑、备份、登录全跟着变），选择存 `notes-look` 偏好文件；状态栏 / 导航栏图标颜色也跟着深浅切换
- 预览态的文字可以长按选中、拖选择柄复制；编辑页 ⋯ 里另有「复制全文」一键拷标题 + 正文
- 双域名优选：`note.wenjun.eu.org` 和 `note.zhangwenjun.eu.org` 指向同一个 Worker，App 冷启动（距上次测速超过 6 小时）会各自打一个静态清单文件、取延迟低的那条；请求遇上 DNS/建连/超时这类 IO 错误会自动换另一条域名重试一次并记住，界面上弹一句「这条线路没连上，已切到 xxx」。⋯ 菜单里能直接看到当前走的域名，点一下立刻重新测速
- 检查更新：⋯ 菜单里「检查更新」下面小字写着当前版本，点一下查 GitHub Release；有新版本弹对话框显示更新说明和「下载更新」（直接打开 APK 资产地址），没有就提示「已是最新版本」。App 每天最多自动查一次，有新版只在菜单里标一句「有新版本 vX」，不弹窗打扰。仓库/版本都没发布时只提示「还没有发布记录」，不报错
- WebDAV 备份（防数据库失效）：⋯ 菜单 →「WebDAV 备份」，填地址 / 用户名 / 密码后点「立即备份」。每篇笔记写成一个 `.md` 文件（文件名=笔记标题，内含标签、更新时间、笔记 ID 和正文），放进 WebDAV 的「记事本备份/」；另外写一份 `_备份记录.md` 记下备份时间与篇数，设置页的状态行直接显示「上次备份：2026-11-11 16:47（5 篇）」。回收站里的笔记不备份，失败篇数会在 toast 里报出来
- 自动备份规则：每次保存成功都重置倒计时，**连续 10 分钟没有新改动**才自动跑一次（期间再改就一直往后顺延，不会边写边传）。两个时间戳（`lastSavedAt` / `lastBackupAt`）落在 `webdav-config` 偏好文件里，所以「改了但没等够 10 分钟就被杀掉」的情况下，下次打开 App 会补跑一次。自动备份成功只更新状态行不弹提示，失败或有失败篇数才 toast
- 页面转场有动画：进出编辑页 / 备份页是淡入 + 轻微横向滑入（280ms），返回是淡出（160ms）；列表里新笔记插到最前、置顶换位置时行位置平滑移动，不硬跳

### 构建

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME="D:\Android\sdk"; $env:ANDROID_SDK_ROOT="D:\Android\sdk"
cd D:\code\notes-app\android-compose
.\gradlew.bat :app:assembleRelease --no-daemon
```

产物：

```
D:\code\notes-app\android-compose\app\build\outputs\apk\release\app-release.apk
```

本机直连 `repo.maven.apache.org` 不通，`settings.gradle.kts` 里 pluginManagement 和 dependencyResolutionManagement 都前置了阿里云镜像。

### 发版（让 App 里的检查更新生效）

App 查的是 `https://api.github.com/repos/jerryzwj/notes-app/releases/latest`，`/releases/latest` 只认「已发布的正式 release」，草稿和 prerelease 不算。发版步骤：

1. `android-compose/app/build.gradle.kts` 里 `versionCode` 加 1、`versionName` 改成新版本（比如 `2.2-native`）
2. 构建后把 `app\build\outputs\apk\release\app-release.apk` 改名为 `notes-native-2.2.apk`
3. 在 GitHub 仓库建一个 tag 为 `v2.2` 的 Release，正文写更新说明，把 APK 作为资产上传（文件名以 `.apk` 结尾）
4. App 下次检查更新时，会拿 `v2.2` 的 `2.2` 跟本地 `2.2-native` 比数字段；本地旧就会弹对话框，「下载更新」直接打开资产地址

仓库名存在 `notes-update` 偏好文件里（`repo` 键，默认 `jerryzwj/notes-app`），换仓库不用改代码。2.1 已经按这个流程发过一次：<https://github.com/jerryzwj/notes-app/releases/tag/v2.1>（资产 `notes-native-2.1.apk`，1,597,105 字节）。本地 `2.1-native` 与 tag `v2.1` 的数字段相同，所以现在点「检查更新」会回「已是最新版本」；等以后发 `v2.2` 才会弹提示。

### 与网页版还差什么（v1 未做）

- 导入 / 导出 JSON、单篇导出 HTML（网页端有；原生端后续接 `GET /api/export` 即可）
- 指纹锁、令牌加密落盘（现在只是私有 SharedPreferences，卸载即清）
- 每日后台备份、接收其他 App 的「分享到记事本」、离线写队列补同步
- Markdown 图片（渲染器不处理 `![]()`，跟网页一致）

### 真机验证情况

本机没有连安卓设备也没有装模拟器，APK 只做了编译和静态校验：`aapt2` 确认包名 `eu.wenjun.notes`、`versionCode 4`、`versionName 2.2-native`、`minSdk 24`、`targetSdk 34`、label「记事本」、启动 Activity `eu.wenjun.notes.MainActivity`；`apksigner verify` 确认签名有效（debug 证书，与旧壳同证书，可覆盖安装）；dex 里能找到 `MainActivity`。接口侧用原生 UA 实测过：`POST /api/login`（`device:true`）回 80 字符三段令牌，`GET /api/notes?meta=1` 回 4 条、`updatedAt` 是毫秒时间戳、`tags` 是数组，`GET /api/notes/:id` 能取回 1549 字正文。

装到手机后重点看四件事：登录后退出重进是否免密、列表 / 详情是否正常加载、编辑 600 毫秒后是否自动保存、分享链接是否复制进剪贴板。

## 安卓旧版（Capacitor 壳，已弃用）

安卓端不做原生重写，而是用 Capacitor 把现有的 `public/` 网页装进壳里跑，只补网页在 WebView 里做不到的原生能力。界面、Markdown 渲染、搜索、回收站这些都复用网页那一套，改网页就等于改 App。

### 原生能力清单

| 能力 | 入口 | 代码 |
| --- | --- | --- |
| 令牌加密落盘（AES256-GCM，密钥在 AndroidKeyStore 里） | 登录 / 启动时自动 | `SecureStore.java`、`SecureStorePlugin.java` |
| 指纹 / 锁屏密码解锁 | footer 的「指纹锁」按钮，开启后每次启动先解锁 | `BiometricPlugin.java` |
| 每日后台备份（默认 4:00，跑一次排一天） | 首次启动自动排期，不建议手动改 | `BackupWorker.java`、`BackupPlugin.java` |
| 系统「分享到记事本」收文本 | 任意 App 里分享文字 → 记事本，启动即建一条新笔记 | `ShareReceiverPlugin.java`、`MainActivity.java` |
| 导出写进系统下载目录 | 编辑器「导出」页脚「导出」 | `NativeFilesPlugin.java` |
| 一键粘贴（WebView 长按菜单不给粘贴时兜底，直接读系统剪贴板插到光标处） | 格式化工具栏最右边的剪贴板图标 | `ClipboardPlugin.java` |

网页侧的桥接在 `public/native.js`（`window.NativeBridge`）：浏览器里所有调用都是 no-op，不影响网页；壳里自动把接口地址换成 `https://note.wenjun.eu.org`（壳的 WebView 源是 `https://localhost`，属于跨域，后端已放行 CORS 并处理 OPTIONS 预检）。

### 构建

```powershell
powershell -File D:\code\notes-app\android-app\build-apk.ps1
```

脚本做三件事：`public/` → `android-app/www`、`npx cap sync android`、`gradlew assembleDebug`。产物：

```
D:\code\notes-app\android-app\android\app\build\outputs\apk\debug\app-debug.apk
```

环境要求：Node 22、JDK 17、Android SDK（`platforms;android-34`、`build-tools;34.0.0`、`platform-tools`，本机装在 `D:\Android\sdk`，已写进 `android-app/android/local.properties`）。Gradle 仓库优先走阿里云镜像，因为本机直连 `repo.maven.apache.org` 会失败。

装到手机：`adb install -r app-debug.apk`，或者直接把 apk 传到手机点安装（`minSdk 24`，Android 7 以上）。

### 备份落在哪

App 里的每日备份写在 App 私有目录，不是下载目录：

```
/data/data/eu.wenjun.notes/files/backups/notes-YYYY-MM-DD.json   # 保留最近 30 份
```

私有目录用户看不见（要靠 root 或 adb 才能取出），好处是不用存储权限、不会被清理软件误删，坏处是不能直接拿文件管理器拷出来。想随时拿文件的话用「导出」按钮（那份会落到 `下载/记事本/`）。备份状态（上次时间 / 成功条数 / 失败原因）记在 `notes_backup` 这个 SharedPreferences 里，网页和 App 都不展示，需要排查时用 adb 看。

### CI

`.github/workflows/build-apk.yml` 已改到新工程：`npm install` → `copy-web.js` → `cap sync` → `gradle -p android-app/android assembleDebug`，产物作为 `notes-debug-apk` 上传。

旧的纯 WebView 壳工程 `D:\code\notes-app\android\` 已废弃不用（保留未删），新代码全在 `android-app/`。

### 免密登录（安卓端长期不动密码）

- 令牌分两种：网页端 `ts.w.sig` 有效期 180 天；安卓壳 `ts.d.sig` 有效期 10 年（`DEVICE_TOKEN_TTL`）。两者都用 `AUTH_SECRET` 做 HMAC，**签名里没有任何密码信息**
- 所以：改 `.dev.vars` 里的 `APP_PASSWORD` 只影响「下次登录」，已经登录过的安卓端和网页端都不会被踢下线
- 安卓壳的免密来自 Device 令牌：App 里输一次密码就拿 `d` 令牌，之后每次启动直接进列表（令牌同时存在 localStorage 和加密存储里，退出登录才清）
- 已经在用的旧版 App / 浏览器持的是老的两段令牌 `ts.sig`，仍然认（180 天），不用重新登录；App 下次启动会自动调 `POST /api/token/device` 换成 10 年令牌
- 想让全部设备立刻失效（比如手机丢了）：换掉 `AUTH_SECRET` 再 `wrangler deploy`，所有端都要重新登录
- 开了「指纹锁」的端，启动还要过一道指纹，这是本地锁，跟密码、令牌无关

### 真机验证情况

本机这次没有连安卓设备，APK 只做了编译 + 静态校验（包名 `eu.wenjun.notes`、`minSdk 24`、`targetSdk 34`、`USE_BIOMETRIC` 权限、`ACTION_SEND text/plain` 分享入口、内置网页含 `native.js`）。第一次装到手机上要重点看四件事：登录后令牌是否写进加密存储（退出重进不用重登）、导出是否落到 `下载/记事本/`、从别的 App 分享文字能否建笔记、第二天 4 点后 `files/backups/` 是否出现 json。

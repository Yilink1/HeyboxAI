# HeyboxAI 项目交接摘要（给 AI / 新开发者）

> 目的：让后续 AI 在**不读全部源码**的情况下快速接手。最后更新：2026-10-27。
> 项目根目录：`C:\Users\Lenovo\AndroidStudioProjects\HeyboxAI`

## 1. 这是什么

一个 **LSPosed 模块**，给「小黑盒」（`com.max.xiaoheihe`）的帖子页注入 AI 总结 / 多轮对话能力。
参考产品：FluxDo（`https://github.com/Lingyan000/fluxdo`），主要借鉴其 AI 聊天 UI、上下文范围（ContextScope）和快捷词设计。

- 模块包名：`io.github.yilink1.heyboxai`
- 目标包名：`com.max.xiaoheihe`
- Xposed API：libxposed API 102（`compileOnly 'io.github.libxposed:api:102.0.0'`）
- 语言：Kotlin；minSdk 26 / targetSdk 35；仅 arm64-v8a
- 构建：AGP 8.5.2 + Kotlin 1.9.24；release 开启 R8 + 资源压缩，签名用 debug 签名
- 产物：`app/build/outputs/apk/release/app-release.apk`

## 2. 用户明确偏好（很重要）

1. **不要用 emoji**（UI、提示词、Toast 都不要），用户认为廉价。
2. 面向小白用户：**不要堆砌奇怪参数**。范围用固定档位（前 5/10/20/50/100 楼、全部帖子），不要“前 N 楼 + 自定义 N”这种开发者思维。
3. UI 要像聊天软件（FluxDo）：用户消息右侧小气泡、AI 左侧卡片气泡；不要整屏大段文字。
4. 设置优先做进宿主（小黑盒）里，而不是让用户退出去找独立 App。当前方案：**AI 面板右上角“设置”弹窗**为主入口；独立 App 只作为 LSPosed 图标入口/兜底。
5. 稳定性优先，避免硬编码混淆类名/方法名；用结构指纹匹配，跨版本尽量自愈。
6. 抓取要“真抓全”，不要静默过滤楼层（水楼过滤已按用户要求移除）。

## 3. 目录与文件职责

```
app/src/main/java/com/yilink/heyboxai/
├── ai/AiClient.kt                    OpenAI 兼容客户端：SSE 流式 chat、/models、连通性测试
├── config/AiConfig.kt                配置读写（模块 prefs + 宿主本地缓存，双写）
├── config/AiConfigProvider.kt        跨进程 ContentProvider（UID 白名单）
├── hook/HeyboxHookModule.kt          Xposed 入口：hook 帖子页 onResume，注入 AI 按钮
├── hook/XiaoheiheFingerprintHelper.kt 反射指纹定位 ViewModel/PostModel/Repository/分页方法/link_id
├── hook/XiaoheihePostExtractor.kt    帖子数据提取、范围裁剪、Prompt 构建、楼层解析
├── hook/MainCommentPaginator.kt      主评论静默串行分页（游标、去重、预算、熔断）
├── hook/SubCommentEnricher.kt        折叠楼中楼补齐（并发受限、缓存）
├── hook/SuspendBridge.kt             反射调用宿主 Kotlin suspend 方法的桥
├── ui/AiSummaryBottomSheet.kt        AI 聊天面板（核心 UI，最大文件）
├── ui/AiSettingsActivity.kt          独立 App 内的设置页
├── ui/MainActivity.kt                独立 App 启动页/状态页
└── ui/SummaryHistoryManager.kt       会话历史 JSON 持久化（按 linkId + scope）
```

## 4. 运行链路

1. `HeyboxHookModule.onPackageReady` 加载 4 个帖子 Activity 类，hook `onResume`。
2. `onResume` 后向 `decorView` 注入一个 “AI” 小胶囊按钮，锚定原生 `iv_share` 左侧；长按可“恢复默认位置 / 本次收起”。
3. 点击按钮 → `XiaoheihePostExtractor.extractBasePost(activity)`（纯内存，毫秒级）→ 打开 `AiSummaryBottomSheet`。
4. 用户点快捷词/刷新 → `ensureData()` 按当前范围静默补数据 → `buildPrompt(scope)` → `AiClient.chatStream()` 流式渲染。
5. 生成完成写入 `SummaryHistoryManager`；重开同一帖子自动恢复。

## 5. 数据提取与分页（最容易出问题的地方）

### 5.1 定位方式
`XiaoheiheFingerprintHelper` 不依赖固定混淆名，优先历史名（O4 / X / h / i），失败后按结构特征扫描：
- ViewModel：Activity 无参方法返回对象，类名含 ViewModel/LinkPost/DetailVM，或内部含 PostModel/Repository。
- PostModel：含 `getCommentSection` / `getPostContentSection` 等结构。
- Repository：含 12 参数分页方法或 5 参数楼中楼方法。
- 主评论分页方法：12 参数，第 4 个参数为 Map，末尾为 Continuation。
- 楼中楼分页方法：5 参数，末尾为 Continuation。
- link_id：Intent → MetaInfo → ViewModel 纯数字字段 → 标题 hash 兜底。

### 5.2 主评论分页
`MainCommentPaginator.paginateMainComments`：
- 初始评论来自 PostModel + 当前 RecyclerView adapter，按 commentid 去重。
- 游标：`commentConfig.lastReqPageVal`；`hasMoreFloors` 判断是否还有。
- 每页请求参数固定 11 个 + Continuation，limit=30。
- 预算：`pages = ceil((maxFloors - initial)/30)`，上限 25 页；
  `timeout = pages*2500 + 4000`，夹在 6s–60s。
- 终止原因枚举：CLEAN_NO_MORE / INITIAL_ALL_LOADED / MAX_PAGES_REACHED / TARGET_REACHED / BUDGET_TIMEOUT / EMPTY_RESPONSE / CURSOR_BLANK / CURSOR_DUPLICATE / NETWORK_ERROR / REPO_NOT_FOUND。

### 5.3 楼中楼
`SubCommentEnricher.enrichFoldedSubComments`：识别 `isHasMoreSubComments` 或 `originalSubCommentCnt > 已有预览数` 的楼层，受控并发补齐，每楼最多 2 页，带内存缓存。

### 5.4 范围
`SummaryScope`：`MAIN_ONLY(0)`、`TOP_5/10/20/50/100`、`ALL_LOADED(-1)`。
- 前 N 档只取已加载的 baseComments 前 N 条（不额外翻页）。
- `ALL_LOADED` 才触发主评论分页 + 楼中楼补齐。
- **水楼过滤已关闭**：`processRawFloors` 不再过滤，所有抓到楼层原样保留（用户要求）。

## 6. 配置

- 模块侧：`heybox_ai_settings`（`AiConfig.getPrefs`）。
- 宿主侧本地缓存：`heybox_ai_local_cache`（`AiConfig.loadFromProvider` 优先读它）。
- 原因：MIUI 会拦截宿主冷启动模块的 ContentProvider（日志 `Failed to find provider info`），所以**本地缓存是唯一可靠落点**；模块保存时双写并尝试 `contentResolver.call` 同步 Provider。
- 字段：`base_url`、`api_key`、`model_name`、`max_floors`、`system_prompt`。
- 默认：DeepSeek `https://api.deepseek.com/v1`、`deepseek-chat`、200 楼。
- Provider：`io.github.yilink1.heyboxai.config`，只允许自身 UID 与 `com.max.xiaoheihe`。

## 7. UI 现状（AiSummaryBottomSheet）

- 近全屏 Dialog（顶部留 10% 屏高），跟随宿主日/夜模式取色。
- 顶部主栏：标题 + 范围选择药丸（仅看主楼/前5/10/20/50/100楼/全帖讨论） + 当前模型切换药丸 + 右侧操作（复制、清空二次确认、设置）。
- 中部：`ScrollView` + `LinearLayout` 消息容器。
  - 用户气泡：右对齐、温润蓝气泡、`WRAP_CONTENT`、`maxWidth=75%`。
  - AI 气泡：左对齐、极简卡片、Markwon 渲染 Markdown、可选中；长按复制该条回复。
  - 流式输出时复用同一个 AI 气泡逐字刷新（`streamingTv`）。
- 状态栏：轻量展示当前范围/就绪状态/数据整理进度/输出 Token 估算/友好中文错误提示。
- 快捷词：`总结这个话题`、`极简省流`、`核心观点`、`争议焦点`、`有什么值得关注的`、`各方立场`。
- 输入栏：多行输入自适应 EditText（最多 4 行） + 圆形极简发送/停止按钮。
- 会话：`sessionCache` 按 `linkId` 缓存（每帖一份，不随范围分裂）；历史按 `linkId + scope` 存 JSON。

## 8. 构建与调试

```powershell
# 构建 release
.\gradlew.bat :app:assembleRelease

# 安装
adb install -r .\app\build\outputs\apk\release\app-release.apk

# 看模块日志
adb logcat -d | Select-String "HeyboxAI"
```

注意：
- 本机 Java 21 + Kotlin 1.9.24 的 Kotlin daemon 增量缓存会崩（`Could not flush incremental caches`）。
  `gradle.properties` 已加 `kotlin.compiler.execution.strategy=in-process` 规避；若仍失败：
  `.\gradlew.bat --stop` 后删 `app/build/kotlin` 再构建。
- 设备：无线调试 `192.168.246.79:23333`（会变）。
- 改完需在 LSPosed 中保持作用域勾选 `com.max.xiaoheihe`，并强杀小黑盒重进。

## 9. 当前已知问题 / 待办

1. **“30 楼诅咒”（最高优先级）**
   - 现象：长帖选“全部帖子”永远只拿到第一页 30 楼。
   - 历史日志：`第 2 页网络请求失败 (耗时 1ms): 方法 h 的末尾参数并非 Continuation`。
   - 已做修复：Map 类型判断改为“类名或 isAssignableFrom”；SuspendBridge 的 Continuation 硬校验改为警告放行；未匹配时打印 repo 候选方法签名。
   - **尚未在真机复验**。若仍复现，看 `HeyboxAI_MainPaginator` 日志：
     - 若打印“未匹配到主评论分页方法”，说明小黑盒改版，需要按候选签名调整匹配。
     - 若方法匹配但请求失败，检查 11 个参数（尤其 extra map、sort/filter、limit）是否被服务端拒绝。
2. `XiaoheihePostExtractor.enrichPostData` 里还残留一个旧的 `SummaryScope.TOP_20 ->` 分支（与后面的合并分支重复），是死代码，可删。
3. 设置入口目前是面板内弹窗；用户希望未来能注入小黑盒“我的”页（比设置页稳定），尚未实现。
4. 独立 App（MainActivity/AiSettingsActivity）仍保留，仅作 LSPosed 入口与兜底。
5. 历史会话没有“列表页”，只有同帖自动恢复；用户暂未强需求。

## 10. 修改时的红线

- 不要加 emoji。
- 不要把范围改回“前 N 楼 + 自定义 N”。
- 不要恢复水楼过滤（用户明确要求去掉）。
- 不要用固定混淆名作为唯一依赖；必须保留指纹兜底。
- 不要把用户气泡写死宽度（必须 WRAP_CONTENT + maxWidth）。
- 不要假设 ContentProvider 一定可用（MIUI 会拦），配置必须落宿主本地缓存。
- 改 UI 时保持纯原生 View（宿主进程里不要用 AppCompat 资源，避免闪退）。

## 11. 关键常量速查

| 项 | 值 |
|---|---|
| 目标包 | `com.max.xiaoheihe` |
| 模块包 | `io.github.yilink1.heyboxai` |
| Provider authority | `io.github.yilink1.heyboxai.config` |
| 模块 prefs | `heybox_ai_settings` |
| 宿主缓存 prefs | `heybox_ai_local_cache` |
| 默认模型 | `deepseek-chat` |
| 默认 Base URL | `https://api.deepseek.com/v1` |
| 默认最大楼层 | 200 |
| 单页条数 | 30 |
| 最大翻页 | 25 |
| 分页预算 | 6s–60s |
| 按钮 TAG | `heybox_ai_floating_btn` |

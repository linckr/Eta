# Eta 3.3.3 上游增量与本地兼容性专项审计

审计日期：2026-10-22。只读核查源码、公开 Release 与 Git 对象；本次未迁移业务源码、未构建、未下载或安装 APK、未读取设备私有数据、未发真实模型请求。以下为静态结论与后续迁移验收要求，不代表运行验收通过。

## 1. 固定版本与证据

| 对象 | 已验证精确值 | 证据 |
| --- | --- | --- |
| 官方 v3.3.1 | `a7fdfc219ceb52cbbe196beaebdcbb40f3fc81d9` | 官方远端 `refs/tags/v3.3.1` 与 compare merge base |
| 官方 v3.3.2 | `c56caed79f7be3bfd94ee989e0e2f2cedc746867` | 官方远端 `refs/tags/v3.3.2` |
| 官方 v3.3.3 | `5e9d3a7b5a7b9d348f36222a629d0626a98ccd0b` | 官方远端 `refs/tags/v3.3.3`；固定 SHA 源码包 |
| 本地审计 HEAD | `cc3aaa0`，分支 `feat/compaction-v4-20261022` | 本地 Git 只读查询 |
| 3.3.2 Release 发布时间 | `2026-10-22T05:34:51Z` | 官方 Release API，经 GitHub 连接器读取 |
| 3.3.3 Release 发布时间 | `2026-10-22T07:59:29Z` | 官方 Release API，经 GitHub 连接器读取 |
| 基线到 3.3.3 | 15 个提交，218 个变更文件，其中 67 个新增 | GitHub compare，另逐文件比对固定 SHA 源码与本地基线 Git blob 得到相同总数 |
| 本地与官方同改 | 63 个文件，其中 45 个生产 Kotlin 文件 | 官方 compare 文件集合与 `a7fdfc2..HEAD` 文件集合求交；这是同改文件数，不能当作必然文本冲突数 |

官方来源：[3.3.2 Release](https://github.com/Mangi-11/Eta/releases/tag/v3.3.2)、[3.3.3 Release](https://github.com/Mangi-11/Eta/releases/tag/v3.3.3)、[完整 v3.3.1→v3.3.3 compare](https://github.com/Mangi-11/Eta/compare/v3.3.1...v3.3.3)。Release 文案只用于确认范围，下文行为结论来自固定 SHA 源码。

路径约定：`U/` 指 `.analysis/compat-3.3.3-20261022/source/Eta-5e9d3a7b5a7b9d348f36222a629d0626a98ccd0b/`；`L/` 指当前 `Eta-3.3.1-local/`。文内 `U/data`、`U/ui`、`U/agent` 及对应 `L/` 路径均省略公共前缀 `app/src/main/kotlin/io/github/mangi/eta/`；`app/build.gradle.kts` 等非 Kotlin 路径从树根起算。行号属于本次固定源码。

## 2. 必须同时纳入的 3.3.2 与 3.3.3 变化

| 版本 | 已核实范围 | 本地迁移含义 |
| --- | --- | --- |
| 3.3.2 | Codex/Grok 订阅授权，通用 provider authMode，远端模型同步/对账、OAuth 备份清理 | 不能继续简单叠加旧 Codex 专用入口与凭据文件；须定义身份、目录、运行时授权、注销/刷新、备份恢复的单一所有者 |
| 3.3.2 | Gemini/MiniMax TTS，各服务独立参数与音色；ASR 总开关和无 ASR 时文字唤醒过渡 | 本地 Breeno 与旧语音开关改造必须合并进新增设置合同及生命周期；不能覆盖新 TTS 或总开关 |
| 3.3.2 | 会话摘要先加载，活跃会话正文按需加载、消息分页/释放，全历史搜索导出；界面导航与阅读位置恢复 | UI 只加载一页不等于模型只发一页。必须保留完整 history/journal/checkpoint；本地恢复、摘要检查点与大消息路径是同改热点 |
| 3.3.2 | Markdown/LaTeX 代码围栏含空行时的循环修复，流式/静态解析缓存；Kimi Web 状态修复 | 不应被旧 ChatMessageItem、历史重投影代码覆盖 |
| 3.3.2 | 应用图标与启动动效调整、ARM64-only native 包和原生构建脚本更新 | 新源码不再提供 x86/x86_64/armeabi-v7a 原生库；旧 x86 模拟器结果不能证明该版本功能可用 |
| 3.3.3 | 回复期间 FIFO 消息队列、立即中断后新回合、撤回编辑、图片/文件和运行中听写 | 和本地本轮 steering 合同不同，必须作产品语义选择或并存设计 |
| 3.3.3 | Web Search/Fetch 默认、DeepSeek、自定义 GET/POST 后端与独立设置/加密凭据 | Web 配置存 DataStore，独立 key 存 KeyStore 加密 SharedPreferences；不要误称 Web 配置造成 Room 23 迁移 |
| 3.3.3 | 可见聊天页与 GUI 浮层运行时常亮，暂停/完成等释放 | 和本地保护系统屏幕旋转状态的改造独立，不满足 rotation 补丁删除条件 |

## 3. P0：数据库谱系冲突，不能直接覆盖

上游 `U/data/db/EtaDatabase.kt:29` 是版本 **23**；本地 `L/data/db/EtaDatabase.kt:30` 是版本 **25**。两条迁移链从 22 开始已分叉：

| 迁移 | 上游 | 本地 |
| --- | --- | --- |
| 22→23 | `model_providers.auth_mode TEXT NOT NULL DEFAULT 'api_key'`，`U/.../EtaDatabase.kt:117` | `provider_models.context_window_kind` 与 `input_token_limit`，`L/.../EtaDatabase.kt:121` |
| 23→24 | 无 | `model_providers.request_byte_budget`，`L/.../EtaDatabase.kt:135` |
| 24→25 | 无 | `model_providers.request_send_policy_json` 与 `compaction_failures` 表，`L/.../EtaDatabase.kt:126` |

因此“都曾到过 schema 23”不等于 schema 相同。本地 25 还缺官方 auth_mode；官方 23 缺本地 token 类型/字节预算/v4 发送策略和失败抑制表。

上游 `U/.../EtaDatabase.kt:74` 保留 `fallbackToDestructiveMigration(dropAllTables = true)`；本地 `L/.../EtaDatabase.kt:78` 明确取消兜底并 fail closed。原版官方 3.3.3 在同包名下接触本地 25 数据库存在降级且无迁移路径导致毁损数据的风险。不能以重新安装、清数据、回退 Room 版本解决。未来需新 schema 版本和按实际表列识别的迁移策略，分别覆盖官方22/23与本地23/24/25起点；保留 fail closed，并对历史、provider、自定义模型、compaction failures/checkpoints 验证无损。此处只提出迁移约束，未实施。

## 4. 消息队列、立即插话与旧 steering 不是同一个合同

| 行为 | 官方 3.3.3 | 本地现有 |
| --- | --- | --- |
| 运行时发送 | 短按入会话队列，当前运行结束后 FIFO 新 run | 旧 supplement/steer 将追加文本注入当前 run/controller |
| 立即插话 | 队首标记 interrupt，取消当前 run，终态衔接新 run | `MSG_STEER_RUN=13`/`14` 往返确认，不必取消当前 run |
| 附件 | 队列项携带 images/file references，撤回时合并现有草稿 | 本地请求 ID、成功/失败回调与草稿版本保护必须逐项保留语义 |
| 用户主动停止 | 保留待发队列，停止自动连发 | 原有本轮 steering 无法直接表达队列管理 |

证据：`U/ui/app/AgentAppState.kt:1077`、`:1115`、`:1199`、`:1866`、`:2024`；`U/ui/app/AgentMessageQueueReducer.kt:16`（入队）、`:40`（提升）、`:64`（撤回并合并）、`:105`（终态取队）；`L/agent/runtime/AgentRuntimeClient.kt:134`、`L/agent/runtime/AgentRuntimeService.kt:716`、`L/ui/components/AgentChatComposerDraft.kt:27`、`L/ui/app/AgentAppState.kt:841`。

官方终态先更新当前会话，再调用持久化并取队；新 run 准备阶段通过 `initialPersistence.await()`（`U/ui/app/AgentAppState.kt:1516`）等待串行持久化完成后才交付 Runtime，不能拆掉这一写前持久化链。合并时需验证取消、失败、终态确认、checkpoint ref、pending result 恢复与下一条队列的顺序；“按钮看起来能发送”不足以验收。

**删除条件**：只有明确接受“取消当前运行、另起一轮”替代旧本轮追加，并确认聊天/助手浮层/外部入口、请求 ID 去重、失败草稿恢复均被上游或新适配覆盖后，才可删除旧 steering。当前源码证据不满足该条件。若并存，应为排队、立即中断、本轮追加给出明确不同操作，不能让同一按钮在不同路径暗中切换语义。

## 5. 运行中听写及语音设置

`U/ui/components/AgentChatInputBar.kt:155` 使用 `recognitionEnabled` 总开关，`:373` 的 `SpeechDictationButton` 仅在压缩时禁用，即流式回复时允许听写。运行中禁止听写的旧 `!isStreaming` 条件不能覆盖这里。

上游新增 `data/model/SpeechSettings.kt` 的服务参数，以及 `agent/voice/GeminiSpeechSynthesis.kt`、`MiniMaxSpeechSynthesis.kt`、`SpeechSseReader.kt`；拆分 `ui/voice/SpeechConnectionFields.kt`、`SpeechTtsProviderFields.kt`、`SpeechVoiceFields.kt`。本地语音变更涉及 `SpeechInputController`、`SpeechProtocol`、`EtaAssistantComposer`、`EtaAssistantOverlayService`、`EtaVoicePanel`、`SpeechRecognitionScreen` 和 `SpeechSettingsFields`，这些均为同改文件；只合并单个麦克风按钮不足够。

**删除条件**：上游总开关可能覆盖本地同义的禁用 UI 逻辑；只有保留旧设置值映射、Breeno 服务选择/失败隔离、面板释放与运行中听写、退出后不残留音频资源的验收后，才可删旧开关或入口分支。新增 Gemini/MiniMax 并不替代本地 Breeno ASR 实现。

## 6. 常亮与旋转保护独立

聊天页常亮：`U/ui/app/AgentAppRoot.kt:143` 读取 runtime 状态，`:145` 判断首页与当前流式状态，`:147` 的 effect 设置并在 dispose 清理 `view.keepScreenOn`。

GUI 浮层常亮：`U/agent/runtime/AgentRuntimeService.kt:894` 判断非终态、未取消、未暂停，`:902` 更新状态流和 capsule view；服务销毁 `:191` 也释放。新增 `AgentRunController` pause 状态流、`AgentRuntimeScreenOnTest` 与服务 retention 测试需要保留。

本地 `L/agent/device/RootShellDeviceController.kt:824` 明确规避 UiAutomation disconnect 的 freeze/thaw rotation 副作用。上游常亮代码不涉及这一设备执行路径或系统旋转偏好。**删除条件**是相同 dump_ui/UiAutomation 场景的系统旋转状态保持证据；上游常亮、启动旋转动画移除、图标更新均不构成替代证明。

## 7. Web Search/Fetch 配置、凭据、输出预算

入口/存储/执行链：`U/data/datastore/SettingsDataStore.kt:31` 的 `web_tools_settings_v1` → `data/model/WebToolsSettings.kt` → `data/repository/WebToolsSettingsRepository.kt:81` resolveRuntimeConfig → `agent/tool/AgentLocalTools.kt:115` → `agent/web/AgentWebTools.kt:53` / `ConfigurableWebBackends.kt`。配置页 `ui/screens/tools/WebToolsSettingsScreen.kt` 与 store、route、Settings 和工具卡也须完整保留。

独立 key 存储：`U/data/repository/WebToolsSettingsRepository.kt:26` 的 `eta_web_tools_secrets`，key `credentials`，KeyStore alias `eta_web_tools_credentials_v1`（`:28`），AES/GCM 加密（`:54`）。`:70` 起可复用已有 DeepSeek provider key/base URL；`:81` 决定独立设置和 provider fallback 优先级。凭据属于本地存储合同，不能将普通设置 JSON 当作完整可移植配置；本次未读取任何实际密钥。配置中自定义 headers/body 可能含用户填入的认证值，后续报告/日志也不得直接回显。

`U/agent/web/WebHttpTransport.kt:38` 关闭自动 redirect，`:83` 起逐跳比较 origin并对跨源过滤敏感 header；保留无 Cookie、错误正文不回显与取消路径。`AgentWebTools` 保持 `web_search`、`fetch_url` 工具名、当前 run 快照分页和 remote-fetch 失败的 local fallback。不能因为后端相同为 DeepSeek 就让网页工具共用模型的 Responses/Codex 通道。

输出体积有新增字段：`U/agent/web/AgentWebTools.kt:82` 开始输出 summary、站点、时间、权威度、排序分。`ConfigurableWebBackends.kt:35` 的限制包括 query 128、title 512、snippet 600、summary 1200、聚合结果字符量 24000、最多扫描100项；fetch 默认 `max_chars=12000` 且允许1000..16000（`AgentWebTools.kt:113`），缓存最多4篇、总600000字符（`:272`）。这些是字符/缓存边界，不能等同模型最终请求 UTF-8 bytes；附加 JSON 字段、中文/转义、多轮分页会增加上传体积。

**预算结论**：保留本地 v4 的最终 provider 请求序列化后 send boundary、预算预检、协议守卫、便携摘要与失败抑制。网页工具已有截断和分页不满足本地请求预算补丁删除条件。未来离线夹具应覆盖中文长 summary、多页 fetch、工具调用/结果配对，以及 compaction 后 queue dispatch；统计应测真实 provider 序列化体积，不能以缓存上限或 UI 文本长度推导通过。

## 8. 历史分页、恢复与本地压缩状态

`U/ui/app/AgentConversationStore.kt:246` 按需加载会话完整 journal/history/checkpoint；`:275` / `:281` 加载更早/最近消息，页大小128（`:576`）；`:330` unload 非活跃正文。`U/ui/app/AgentAppState.kt:795` ensure loaded、`:847` 分页、`:926` 释放非活跃会话、`:2246` 串行持久化。搜索/导出分别依赖 `ConversationSearchRepository`、`loadAllMessages`，不能只搜索或导出可见窗口。

本地 `AgentConversationStore`、`AgentAppState`、`AgentRuntimeWire`、`AgentRuntimeRunExecutor` 与 event/projector 还有 v4 checkpoint、failure persistence、恢复和准备计时。加载窗口和请求上下文必须分离；卸载或回写不得将“未加载/已释放”当作“空历史”覆盖 DB。重写/编辑分支仍需保留 revision 边界及 tool-result 完整性。历史分页性能改善不替代上传预算、重复压缩抑制或原始 journal 保留。

上游 `MainActivityLaunchRequests.kt`、`AgentAppShell.kt`、navigation restoration、`TexMathDelimiters.kt`、static/streaming cache 同属必须保留的变化，旧界面整体回盖会丢失后台恢复/重复跳转与解析循环修复。

## 9. 协议、目录、版本与签名合同

- IPC：上游 `U/agent/runtime/AgentRuntimeWire.kt:35` 已保留 `MSG_READ_CONTEXT_RESULT=15`，`:101` 新 `auth_mode`；`:300`/`:301` 对 OAuth 请求不写 apiKey。官方并未定义旧本地13/14 steering。两端需同步 provider auth、runtime resolver 与本地 budget/send policy/compaction fields，不能只处理编号不冲突就判兼容。
- OAuth 目录：官方 `U/agent/auth/ProviderCredentialStorage.kt:24` 使用 `noBackupFilesDir/provider_credentials` 的每 provider 加密 AtomicFile；旧本地 `L/agent/model/CodexCredentialStore.kt:206` 使用 `filesDir/eta_codex_oauth_v1.bin`、锁文件及不同 alias。是否迁移旧授权或要求重新登录必须另立方案；不能直接拷贝密文或仅改文件名。账号适配细节由同批专项审计覆盖。
- 模型目录：官方 `U/data/repository/RemoteModelReconciliation.kt:13` 对 OAuth 目录可删除远端已移除的自动模型并保留手工模型/用户覆盖；合并时必须保留本地 contextWindowKind/inputTokenLimit、预算和兼容性标记，不能仅按旧/新名称对应便宣称等价。
- Android 合同：官方与本地 `app/build.gradle.kts:38` 均为包名 `io.github.mangi.eta`，minSdk33、compile/target37、Java25；官方 `:45` 增 arm64-only，不是 SDK 升降级问题。
- 版本：官方和本地 `app/build.gradle.kts:42` 恰均为 `versionCode=2026102202`；官方 name3.3.3，本地3.3.1-local。未来迁移产物要取更高本地 code，不能靠 name3.3.3判断为升级。
- 签名：两边读取同名 ETA_RELEASE_STORE_* / KEY_* 环境变量只证明构建脚本接口，没有证书指纹证据。本次未下载 APK、未访问私钥、未比对装机证书，不能声明官方包可覆盖本地签名版；应用数据/KeyStore保持另受签名与安装方式约束。
- 资源/原生：新增 nimbus-jose-jwt 依赖、proguard 与 license，native ABI 过滤、原生源码归档/构建脚本变化需成套迁移。相同目录名与包名不证明 native、授权或备份格式兼容。

## 10. 合并热点与可删除结论

| 热点 | 合并要点 | 当前可删除结论 |
| --- | --- | --- |
| EtaDatabase / ProviderEntities / Model / Provider / repositories | 合并两条 schema 谱系及 auth_mode 与本地预算、窗口类型、失败表 | 不可删本地字段及 fail closed；未来须无损迁移测试 |
| AgentAppState / ChatInputBar / UiState / UiActions / HomeScreen | 以新分页、队列、恢复为骨架，接回本地本轮追加、草稿确认与checkpoint恢复 | 旧 steering 未被等价替代；不可直接删 |
| Runtime Wire / Service / RunExecutor / resolver | 同步新 auth、常亮、暂停状态流与本地13/14、预算字段、准备计时、压缩状态 | IPC 编号不冲突不等于合同完整；不可只保留一边 |
| AssistantComposer / OverlayService / VoicePanel / SpeechInputController | 新ASR总开关、文字过渡与运行中听写，接回Breeno/外部追加 | 可收敛重复禁用分支，未验证前不删功能 |
| OpenAI providers / Factory / Reasoning / headers | 新官方通用OAuth/模型能力与本地重试、字节边界、DSML/历史协议兼容性同时存在 | 仅功能标题相同不能删本地兼容修复 |
| SettingsDataStore / SettingsScreen / Backup | 新Web设置与独立凭据合同、本地压缩设置和备份字段同时保留 | Web设置不是Room23迁移，不能混淆 |
| RootShellDeviceController | 保留rotation保护；对照上游dump_ui调用链 | 常亮不能替代rotation补丁 |
| strings / ChatMessageItem / model picker / provider screens | 新队列、分页、授权和语音文案、UI拆分；保留本地压缩进度与配置入口 | 资源整体覆盖会丢功能；只可去除已证明重复的展示 |

上游新增测试文件包括 `AgentMessageQueueReducerTest`、`AgentRuntimeScreenOnTest`、`AgentRuntimeServiceRetentionTest`、`AgentAppStateConversationLoadingTest`、`AgentConversationPersistenceTest`、`ChatHistoryPagingTest`、`AgentWebToolsTest` 等。本次只核查它们存在及合同相关源码，没有执行，不能将其当作迁移后的通过证据。最终验收还须补两条DB谱系、本地v4完整上下文、旧steering与新queue并存/替代决策、外部入口、运行中语音和旋转保持；这些均未在本次只读审计中实施。

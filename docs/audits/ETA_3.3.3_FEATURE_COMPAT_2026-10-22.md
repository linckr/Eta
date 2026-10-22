# Eta 3.3.3 非压缩本地能力兼容审计

日期：2026-10-22。仅源码审查，未迁移、构建、安装、调用真实模型或读取真实凭据/会话。

本地为 `feat/compaction-v4-20261022`，HEAD `cc3aaa0`；本地参照基线 v3.3.1。上游为 v3.3.3 发布提交 `5e9d3a7b5a7b9d348f36222a629d0626a98ccd0b`。以下 `L/` 表示本地 `app/src/main/kotlin/io/github/mangi/eta/`；`U/` 表示 `.analysis/compat-3.3.3-20261022/source/Eta-5e9d3a7b5a7b9d348f36222a629d0626a98ccd0b/app/src/main/kotlin/io/github/mangi/eta/`。路径后的数字是本次读取源码的行号。

盘点来源为当前代码、相对 v3.3.1 的差异，以及 `docs/LOCAL_CHANGES_3.3.1.md`、`docs/LOCAL_CHANGES_3.3.0.md` 和 `docs/ETA_3.3.1_LOCAL_COMPATIBILITY_2026-10-14.md`。历史报告只用于发现项目，以下状态均重新检查 3.3.3 源码。旧报告“3.3.1 未覆盖 Codex/运行中语音”不能沿用到 3.3.3。

## 分类总表

| 能力 | 3.3.3 分类 | 当前源码依据与处理 |
|---|---|---|
| Codex 订阅请求、加密登录态、刷新、账号模型目录及推理档位 | 部分重合需合并 | 上游已有完整账号认证体系；不能再宣称 Codex 是本地独有。保留本地能力语义，按上游多 Provider `authMode` 架构整合，见下文。 |
| Codex 设备码登录入口和既有本地凭据承接 | 冲突需重设计 | 本地设备码流、全局凭据文件与上游浏览器 PKCE/回环回调、按 Provider 加密存储不兼容；上游没有读取本地旧格式的适配器。 |
| 主聊天运行中补充、runId/requestId 关联、ACK、幂等去重与失败草稿保留 | 冲突需重设计 | 上游主聊天改为消息队列及中断后新消息；队列项目不是当前 run 内补充。应保留两种操作语义并适配确认，不能拿队列入列代替 runtime 接受确认。 |
| 数字助理运行中补充与确认 | 本地独有保留 | 本地 `EtaVoicePanel`/overlay 的 supplement callback 与 `steerRun` 完整确认链，上游未含本地 IPC requestId ACK；胶囊内部原始 `session.steer(text)` 不构成端到端替代。 |
| 小布活动任务主界面接入、事件回放及完成归档去重 | 部分重合需合并 | 上游有外部归档导入/导航；仍无本地 active handoff 接入及稳定 UI 消息投影器。 |
| 小布原会话继续发送、每会话草稿和附件保持 | 冲突需重设计 | 本地去掉旧外部只读阻断并保留原上下文；上游仍把外部只读归档的草稿移到新会话。新版队列/草稿同步又改变发送路径，需一并整合。 |
| 小布实验 ASR：身份隔离、签名权限、Binder 回调、分段续录、完成回填和一次冷启动恢复 | 本地独有保留 | 上游 ASR 枚举仍无 BREENO，无 BreenoSpeechInput/BreenoAsrHooks 对应实现。 |
| 小布 Runtime 服务绑定优先级 `BIND_ABOVE_CLIENT` | 本地独有保留 | 上游 `AgentRuntimeConnection` 绑定仍无此标志。该标志不证明解决所有 ColorOS 冻结。 |
| 运行中语音听写填入草稿、streaming 改变不取消听写 | 已完全覆盖（能力层） | 上游按钮已改为 `enabled = !isCompacting`，取消 effect 不依赖 streaming；采用上游实现。它不覆盖 BREENO 本身或本地会话草稿隔离。 |
| 浮窗显示当前思考/工具、胶囊避让 | 已完全覆盖 | 当前本地已经使用上游胶囊思考和避让；不存在需要重新恢复的旧光球/单行浮窗补丁。 |
| 系统旋转保护：禁用系统 uiautomator dump 回退 | 本地独有保留 | 上游仍执行 `uiautomator dump`；本地仅走无障碍节点，Root 截图/坐标保留。 |
| WorkBuddy 网关进程、读取契约部署和私有手机技能 | 非 Eta 外部 | Eta 客户端契约由另一份协议审计覆盖；网关二进制/服务与应用私有技能不是 3.3.3 发布源码所能替代或证明兼容的资产。 |

## Codex：功能已有，身份和数据承接仍是迁移关口

本地证据：`L/EtaCodexOAuth.kt:109,163,198`（设备码、轮询、刷新）；`L/agent/model/CodexCredentialStore.kt:23,113,145,193,208`（全局文件、写后读回、AES-GCM、跨进程文件锁、旧 key alias）；`L/agent/model/CodexCompatibilityProfile.kt:18,33,48`（设备码端点、authMode 与身份）；`L/agent/model/ProviderClientFactory.kt:15`（按旧 sourceType/providerId 路由）；`L/data/repository/RemoteModelFetcher.kt:76,127`（账号模型目录及 401 刷新）。

上游证据：`U/data/model/Provider.kt:17` 定义 `ProviderAuthModes.OPENAI_CHATGPT`；`U/data/provider/ProviderConnectionProfile.kt:18` 定义订阅端点；`U/agent/auth/ProviderOAuthClient.kt:34` 是浏览器 PKCE/state/nonce 和回环回调登录，并非本地设备码流；`U/agent/auth/ProviderCredentialStorage.kt:23,36,69,133` 使用 `noBackupFilesDir/provider_credentials`、providerId AAD、按 ID 哈希的 AtomicFile 和新 key alias；`U/agent/auth/ProviderCredentialSessions.kt:45,63,86` 提供 generation 校验、刷新锁和轮换保存；`U/agent/model/AuthorizedProviderHttp.kt:34,49` 获取订阅 token 并限一次 401 刷新；`U/agent/model/CodexResponsesRequest.kt:9` 统一 Responses 约束；`U/data/repository/CodexModelCatalog.kt:15,60` 解析账号目录和推理档位。上游 `U/agent/runtime/AgentRuntimeWire.kt:300,436` 传递 authMode 并在 OAuth 配置中清空 API key；`U/data/repository/EtaBackupRepository.kt:278` 排除 OAuth secrets。

因此“登录/加密/刷新锁/模型目录/思考强度”在能力上重合，但数据布局、登录方式和服务商标识不同。没有证据表明上游能自动接住本地旧登录态；不能把新登录页出现视为原订阅已迁移。上游存储的对象内锁与本地跨进程文件锁也不能只按名称等同，应核实新认证服务的唯一持有进程及所有访问入口后决定是否仍需跨进程锁。未来方案应明确旧 providerId/sourceType → 新 authMode 映射、旧密钥解密后本机原子导入、成功读回前保留旧文件，或保留原设备码入口作为同一新凭据体系的另一授权方式。不可并存两套各自刷新的 token owner。

最小验收：使用合成凭据验证旧/新字段映射、失败不清旧登录态、写后读回、并发刷新和 logout/login 竞态、401 恢复最多一次、账号切换拒绝过期模型同步、模型目录/思考档位不丢；确认备份、RuntimeConfig、IPC 和日志不包含 token。真实登录/刷新/订阅请求需未来另行授权与实机验收，本次未执行。

## 运行中输入：消息队列不能代替补充确认

本地 `L/agent/runtime/AgentRuntimeSession.kt:117` 按 requestId 保存相同文本结果、拒绝 ID 复用不同文本；`L/agent/runtime/AgentRuntimeWire.kt:89,92,669,681` 定义请求与响应；`L/agent/runtime/AgentRuntimeClient.kt:134,373` 校验 runId/requestId 并等待对应回执；`L/agent/runtime/AgentRuntimeService.kt:718` 检查当前 run 后记录事件；`L/ui/app/AgentAppState.kt:841,1625` 持有 callback 并处理事件关联；`L/ui/components/AgentChatComposerDraft.kt:17,27` 相同文本复用 ID、仅 accepted 后清草稿；主输入框 `L/ui/components/AgentChatInputBar.kt:187` 发送前结束听写。助理对应入口为 `L/agent/voice/EtaAssistantComposer.kt:157`、`L/agent/voice/EtaAssistantOverlayService.kt:433`、`L/agent/voice/EtaVoicePanel.kt:161`。

上游 `U/ui/components/AgentChatInputBar.kt:125,137,205` 引入排队列表；`U/ui/app/AgentAppState.kt:1085,1112,1156,1199` 支持队列提升中断、撤回草稿、正常发送入列、空闲后 dispatch。`U/agent/runtime/AgentRuntimeSession.kt:89,95` 仍是仅文本 `steer`；胶囊内部 `U/agent/runtime/AgentRuntimeService.kt:838` 调用该方法，未实现本地 requestId IPC 回执链。

最小验收：保留“当前 run 补充”“排队下一条”“中断后发送”各自语义；同请求重发仅接收一次、不同文本复用 ID 拒绝、错误 run/已结束 run 拒绝、ACK 丢失不产生重复补充、拒绝/超时保留原草稿、在途期间编辑草稿不被旧 ACK 清空；主聊天、助理、胶囊跨入口交叉验证，队列撤回/图片/文件引用均保持新上游行为。

## 外部会话、草稿与 ASR

本地 `L/agent/runtime/AgentRuntimeClient.kt:33` 的活动查询包含 handoff；`L/ui/app/AgentAppState.kt:340,506` 可在无本地 checkpoint 时接入活动外部任务；`L/ui/app/AgentExternalRunProjector.kt:10,22` 以 `user-$runId`/`assistant-$runId` 统一活动/完成消息 ID，并在归档时补图片预览；`L/ui/app/AgentAppState.kt:632` 归档复用该投影器；`L/ui/components/AgentChatComposerDraft.kt:39` 按 conversationId 保留输入状态。上游有 `U/ui/app/AgentAppState.kt:608` 外部结果导航，但 `:531` 重接只按普通 UI handoff 查会话，`:655,659` 导入仍清输入和图片，`:1174` 对外部只读会话移走草稿，`:2315` 仍判断 `breeno-`/`external-` 归档只读。采用新队列时必须把“原会话继续”决策前移到 dispatch 路径，避免只修按钮后仍在业务层新建会话。

ASR 本地 `L/data/model/SpeechSettings.kt:6` 增加 BREENO；本地 `app/src/main/AndroidManifest.xml:36` 为 signature 权限；`L/hook/breeno/BreenoAsrHooks.kt:466,516` 校验控制请求并注册受权限保护入口；`L/agent/voice/BreenoSpeechInput.kt:17,69,83,90,109,145` 使用逐请求 ResultReceiver、ID 校验、FINAL 后新 ID 续录、仅启动未就绪一次恢复、65 秒总等待及完成兜底；`L/agent/voice/SpeechInputController.kt:149,247` 处理会话 generation 隔离和同步完成回调的状态顺序。上游 `U/data/model/SpeechSettings.kt:6` 只有 SYSTEM/QWEN/DOUBAO，不能覆盖这些逻辑。

上游已覆盖运行中普通听写：`U/ui/components/AgentChatInputBar.kt:163,373` 的取消 effect 仅依赖编辑/识别开关，按钮不按 streaming 禁用；当前本地对应 `L/ui/components/AgentChatInputBar.kt:161,371`。应采用上游 `!isCompacting` 限制并迁移 BREENO、完成状态顺序与会话草稿所有权；本地 `enabled=true` 不应整段覆盖上游压缩态限制。

最小验收：小布长任务运行时主界面接入、退回重进事件回放不重复、完成/归档同时到达仅一份消息、外部原上下文后续请求保留工具历史、切换会话不串草稿/附件；ASR 冷/热启动、停顿续录、静音段完成、取消后迟到回调、会话切换、运行开始/结束期间录音、完成回填一次、TTS 与录音音频租约互斥。源码/单元逻辑通过不能替代 ColorOS 和原生小布实机证明。

## 绑定、浮窗与旋转

服务绑定：`L/agent/runtime/AgentRuntimeConnection.kt:133` 包含 `BIND_ABOVE_CLIENT`，`U/agent/runtime/AgentRuntimeConnection.kt:133` 只有 AUTO_CREATE/IMPORTANT/INCLUDE_CAPABILITIES。保留优先级改动，复核短租约、30 秒空闲解绑、bindingDied 和 8 秒等待；实机至少覆盖小布前后台切换、长任务、主界面 attach 与小布进程重启，不把一个绑定标志当作冻结根因已根治。

浮窗：`L/agent/overlay/AgentOverlayState.kt:22,54,59` 与 `U/agent/overlay/AgentOverlayState.kt:22,54,59` 同样保存最新思考、每新块清前轮思考；本地相对 v3.3.1 的该文件差异只为忽略新 `ModelIdentityObserved` 事件，并非新的思考预览系统。`L/agent/runtime/AgentRuntimeService.kt:123,165` 已用 `AgentOverlayClearance`。继续采用 3.3.3 胶囊，合入新事件的穷尽分支即可；验收思考尾句、工具参数、暂停/继续、结束结果和触摸避让。

旋转：`L/agent/device/RootShellDeviceController.kt:126,159,527,821,989` 删除 Root 节点回退、节点动作明确提示无障碍不可用、dump 返回空、拒绝旧 UIAUTOMATOR 观察；`U/agent/device/RootShellDeviceController.kt:131,548,840,1021` 仍 fallback、执行 `uiautomator dump` 并重新校验旧节点。最小验收为系统自动旋转/锁定两种初态下观察及节点查询前后设置不变；无障碍开/关、Root 截图/坐标工具分别验证，旧缓存节点不可操作。保留上游新增 GUI 功能，但不能恢复会冻结/解冻旋转的 dump 路径。

## 外部资产与当前边界

WorkBuddy 网关运行程序、部署参数和手机私有技能与 Eta 发布源码分属不同资产；不因采用上游 OAuth/Grok 或本地 Provider 客户端改造就删除或重置。未来安装前另行列清私有数据保留检查。

本报告证明“现有功能和源码差异在哪里”，未证明合并后的编译、数据库升级、真实登录/刷新、小布 ASR、运行中听写或设备旋转已验收。压缩 V4、Provider 错误恢复/预算/数据库 schema 的详细冲突由同轮专项报告负责，不以本报告替代。

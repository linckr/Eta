# Eta 3.3.3 上下文、Provider 与数据兼容审计

日期：2026-10-22。范围：只读源码、测试源码与既存交付记录；本轮未迁移数据库、未构建/安装、未运行测试、未发送模型请求。

## 证据基准

- **L** = `D:/下载/agent/eta/Eta-3.3.1-local`，HEAD `cc3aaa0`，分支 `feat/compaction-v4-20261022`。以下 `L/path:行` 均以本次源码为准。
- **U** = `D:/下载/agent/eta/Eta-3.3.1-local/.analysis/compat-3.3.3-20261022/source/Eta-5e9d3a7b5a7b9d348f36222a629d0626a98ccd0b`，上游 v3.3.3 精确发布源码。
- **B** = `L/.analysis/compat-3.3.3-20261022/base-v3.3.1`，上游 v3.3.1 导出源码，用于识别 3.3.1→3.3.3 新变化。
- 下表路径省略共同前缀 `app/src/main/kotlin/io/github/mangi/eta/`，测试另行写明。不存在的本地专有文件以“U无此文件”说明，不能据此说上游已经实现。

## 结论

最新 2202 三修与本地上下文链仍有保留价值，3.3.3 没有替代其主要能力。迁移阻断项是数据库编号分叉；Provider 新认证/请求重写必须手工整合到本地统一请求计量与最终发送护栏，不能用整文件覆盖。签名/opaque保护本来已有上游基础，保留最终正确行为即可，不必把完全相同的代码重复当成本地增量。

### P0：数据库 23 发生真实 schema 身份碰撞

|版本|L 的含义|U 的含义|证据|
|---|---|---|---|
|22|会话增加 model_id|相同|两边 `data/db/EtaDatabase.kt` 的 21→22|
|23|provider_models 增加 context_window_kind、input_token_limit|model_providers 增加 auth_mode|L `data/db/EtaDatabase.kt:121`；U 同文件 `:122`|
|24|model_providers.request_byte_budget|没有此版本|L 同文件 `:135`|
|25|request_send_policy_json 与 compaction_failures 表|没有此版本|L 同文件 `:126`|

L `EtaDatabase.kt:30` 为25，U `:29` 为23。不能简单替换 `MIGRATION_22_23`：本地已有23/24/25用户不会重新跑22→23，上游23用户也没有本地23的容量字段。单纯改最终版本号不能修复分叉。

后续实现建议采用新的联合版本（至少26），为官方23、本地23/24/25与旧22分别定义迁移路径。可选择23→26直接收敛，也可改造23→24再逐级收敛；必须查询表结构，按实际缺少字段补齐，并保留 auth_mode、容量覆盖、B、发送策略、失败表、Journal、文本分块与外键。不能对“同版本号即同schema”作假设，也不能无条件重复 ADD COLUMN。历史测试应增加两种23数据库夹具；现有 `L/app/src/test/kotlin/io/github/mangi/eta/data/db/EtaDatabaseMigrationTest.kt:202` 只证明旧本地24→25路径的测试意图，不能证明联合迁移。

**不可引入 U `EtaDatabase.kt:74` 的 `fallbackToDestructiveMigration(dropAllTables = true)`。** L `:79` 明确采用未知路径失败保数据。官方23直接覆盖本地25属于降级路径，存在 destructive fallback 清库风险；本轮未安装验证，不能把风险当已发生。

## 逐项分类

|能力|分类与当前行为|双边证据与合并注意|
|---|---|---|
|2202 token三阈值|保留。触发≥85%I，提交≤80%I，提前目标≤60%I|L `agent/model/AgentCompactionTokenPolicy.kt:4`；U `AgentContextSession.kt:21,62,123`仍以knownContextWindow及85%触发，未实现80/60候选契约。L单测 `AgentCompactionTokenPolicyTest.kt:14,22,33,45,57`覆盖边界、最终和condense路径；本轮仅读测试源码|
|v4门控与legacy0|保留。显式启用才用portable；旧配置version0继续legacy|L `AgentModelClient.kt:317`、`AgentContextSummarizer.kt:55,79`；U无portable/checkpoint/send-policy文件，U `AgentContextSummarizer.kt:14`仍简单总结。不要把旧配置静默升级v4|
|B/L/H与独立bootstrap|保留。v4的B缺省0；legacy才按有效输入容量推导512KiB/1MiB。L冻结后不随观测成功滚动放大|L `AgentRequestBytePolicy.kt:5,12`、`AgentRequestSendPolicy.kt:5,22,29,38`；U无对应策略。B是软触发；L是自动发送上界；H是显式硬上界。估算/成功样本均不是服务端极限证明|
|2202 bootstrap修复|保留。无观测固定anchor512KiB、growth256KiB、L768KiB，H可裁小；旧bootstrapRuleVersion0失效|L `data/model/RequestSendPolicy.kt:19,21,31,38`；U无对应策略。测试 `data/model/RequestSendPolicyTest.kt:12,36,47,62`。旧bootstrap失效后沿用legacy，必须重新明确启用，不能声称仍有L/H保护|
|模型容量/输出预留|保留并与上游模型认证合并|L `AgentContextBudget.kt:9`区分total/unknown并取inputTokenLimit最小值，`:20`拒绝输出占满总窗；L `AgentCompactionRequestBudget.kt:10`计入摘要指令/旧摘要并预留输出。U无这些预算类，U `AgentContextSession.kt:21`只取knownContextWindow。U的auth_mode新增列必须与本地两容量列同时保存|
|持久失败抑制|保留。仅确定性byte失败；绑定会话、配置、算法版本及已失败来源前缀；append可保留，已消费编辑失效|L `AgentContextSession.kt:46,204,222,249`、`data/repository/RoomCompactionFailureStore.kt:16,24`；U无对应表/adapter。HMAC key在应用私有SharedPreferences生成，不能误称所有存储均为Keystore；DB存指纹和数值，非原始会话/凭据|
|2202稳定scope及断点证明|保留。scope用于定位，消费前缀证明用于复用；scope不等于无条件可复用|L `AgentPortableTextCompaction.kt:28,32,54`、`AgentCompactionCheckpoint.kt:47,73`、`AgentSummarySlices.kt:54`；runtime `AgentRuntimeRunExecutor.kt:302`传conversationId；U无对应文件。部分字段还验证字段结构/长度/批次，重新插入原受保护user必须失效|
|旧断点/层次树|保留分引擎策略，禁止统一格式转换|L `AgentCompactionCheckpoint.kt:47,89`区分legacy version1/portable version3子目录；`AgentHierarchicalCompaction.kt:53,55,107`树version2且拒绝损坏/错误类型。U无文件存储树机制；不能删除旧270片进度或把它按portable读入|
|Journal/压缩后编辑|保留本地修复并合入上游读取优化|L `ui/app/AgentConversationRevisionReducer.kt:31,42,50`按完整Journal重建被摘要覆盖部分；U同文件 `:30,37,43`只有较旧prefix逻辑。L `AgentConversationStore.kt:112,119,199`分别持久化/恢复history与journal。U `AgentConversationCodec.kt:37`新增InputStream解码，适合保留，但不能覆盖本地编辑/断点语义|
|DSML协议泄露|保留。只检测完整正文协议泄露；绝不把正文解析成工具执行|L `AgentToolProtocolGuard.kt:8,12,20,36`，`AgentModelRetry.kt:104`限定一次协议恢复；U无Guard，U重试未替代此保护。tool_calls真实结构仍由Provider解析；引用/代码示例不能误判|
|Responses重复输出/别名与Trace|手工合并，不能删除本地修复|L `OpenAiResponsesProvider.kt:360,363,375,568`空terminal可用标准增量，但非空终态不字段拼补、不冒充opaque，并避免别名重复；U同文件 `:335`新支持response.done(status映射)，应加入本地解析器。L `ModelHttpTrace.kt:17,91,145,216`计数与阶段不含正文/URL/头值；U没有此Trace|
|安全重试|保留并与上游401合并|L `AgentModelRetry.kt:43,75,83`最终预算、已可见输出/hosted tool停止重试、receive最多一次；U新 `AuthorizedProviderHttp.kt:21,46`只在401响应头后刷新一次且关闭OAuth redirect/自动连接retry。不能套两层401重放，也不能绕开本地可见输出保护|
|线性IPC与准备计时|保留。并非被上游流式读取替代|L `agent/runtime/AgentLegacyConversationProjection.kt:16,33`单次逐项编码和后缀尺寸计算，`AgentPreparationTraceTest.kt`为对应测试；U仍有较旧projection实现。上游InputStream读取改善另一阶段，不能证明Binder/预览编码已线性|
|Anthropic签名/Responses opaque|基础已被上游覆盖；保留正确结果，无须重复移植相同基础|L `AgentContextCompactor.kt:32,127`锁定pending签名工具回合，换摘要后清旧opaque与contentBlocks；U同文件 `:20,59`已有对应保护。AnthropicMessagesProvider、ResponsesEphemeralState从B→U归一化行尾比较未变。L新增的预算/候选路径必须继续调用同一保护|

## Provider新变化对本地预算链的影响（P1）

1. **实际请求必须等于计量请求。** U `agent/model/AgentProvider.kt:49`的effectiveConfig按认证profile改写baseUrl/providerType/source/endpoint并清apiKey；L `AgentProviderRequestJson.kt:8`目前按effectiveConfig分发实际JSON，必须吸收同样profile行为。U `OpenAiChatCompletionsProvider.kt:119`在customBody后为Grok重写model/stream/messages/tools；U `ResponsesRequestBuilder.kt:36`在merge后调用CodexResponsesRequest。后续统一builder必须包含这些最终重写，字节护栏必须放在最终序列化后，包含chat、compaction、rewrite、恢复和重试。
2. **Codex字段不是普通Responses的可选装饰。** U `CodexResponsesRequest.kt:8`拒绝max_output_tokens/max_tokens/temperature/top_p/background/conversation，补reasoning.encrypted_content及parallel_tool_calls。若直接把本地 `AgentOutputLimit`注入与U configure拼接，可能在发出前触发require错误；需针对authMode决定输出字段支持，预算输出reserve仍需保留。
3. **新认证scope必须失效旧发送证据。** L `data/model/RequestSendPolicy.kt:57`哈希现有route/auth/header/body，迁入U独立authMode/ProviderConnectionProfile后，必须明确把authMode与最终route/profile身份纳入scope，避免“同空apiKey、不同订阅认证”误复用L。持久失败scope同理，不能加入短期access token造成每次refresh失效，更不能记录token。
4. **trace应以每次物理HTTP call为单位。** U `AuthorizedProviderHttp.kt:32`可能产生401→刷新→第二个call，L Trace是每attempt/call模型；若整段共用一个trace或漏attach新call，会污染上传/接收阶段与可安全重试判定。测试要核对两个独立call的bytes/terminal，不能将401视为已完成流。
5. **工具协议主体未被重写。** U Chat仍使用结构化tool_calls，Responses仍转换function_call/function_call_output；上游新变化主要为OAuth profile、Grok约束、Codex配置和response.done事件。没有证据表明3.3.3已解决DSML正文、Summary结构校验或本地重复终态问题。

## 可删除与不可删除的边界

- 可用上游实现替换的仅是已确认等价的基础部分：InputStream解码可直接采纳，已有签名/opaque基础无需重复维护。订阅登录/provider选择是否完全删除本地专用实现，应由负责OAuth审计的报告结合账户迁移决定；本报告不宣称其完整等价。
- 不可因“新版已支持订阅”删除统一请求JSON计量、token容量、B/L/H、持久失败抑制、portable断点、层次树/旧进度、Journal编辑修复、DSML、Trace、安全重试或线性IPC。它们覆盖的问题没有被U对应代码替代。
- ProviderReasoning的硬编码模型effort校验在U `ProviderReasoning.kt:35,47`放宽并增加XAI支持；这是上游应吸收行为，但不能据此删除本地模型容量或输出预算。

## 最小验收建议（后续获授权实施时）

1. 离线构造官方DB23、本地DB23/24/25、旧22四类夹具升级联合版本；校验所有实体字段、Room identity、完整Journal/工具批次/分块和手工容量/B/L/H保持；未知schema必须失败保数据。关闭重开后再次核对。
2. 复用2202 token/bootstrap/source绑定测试，并新增profile/authMode切换失效、bootstrap旧版失效、B=0/null/48MiB独立、精确L/H等号与超1B。断点覆盖append、消费前缀编辑、原受保护user插入、partial工具批次、跨会话、存储失败/取消重开。
3. 只用fake Provider/回环HTTP验证最终JSON字节等于实际发送：API-key Chat/Responses/Anthropic、Codex、Grok四类最终重写、无工具摘要、401一次刷新、response.done三种状态。核对最终guard覆盖重试/恢复；已有可见输出与hosted工具不能重放；Trace逐call隔离且无敏感内容。
4. 保留签名tool回合不压缩、压缩后清opaque、流增量/terminal不重复、DSML引用与真实泄露分辨、Journal覆盖内外编辑的回归；用大夹具检查预览编码没有回退为重复全量序列化。
5. 真实模型验收另行授权并限制提供商/次数/费用。至少验证摘要语义、实际上传边界、端到端耗时、取消后续压、真实大工具批次与5000+来源规范化性能；离线测试不能替代此项。

## 当前验收状态的准确表述

本轮只做静态审计，没有重跑历史测试。既存 `docs/CONTEXT_COMPACTION_V4_REVIEW_FIXES_2026-10-22.md`记载279项Android36目标回归、36项Android33 IPC回归（315次执行，含重复覆盖）及构建；这是历史本地证据，不能移作3.3.3合并结果。

更新的 `docs/CONTEXT_COMPACTION_V4_2202_DEVICE_INSTALL_2026-10-22.md:13`明确2202已安装、DB25及数据保留核验、v4开启数0且未发送模型请求。它覆盖旧二次审查报告尾部“手机2201/2202未安装”的过时状态。安装启动通过仍不能声称v4真实模型摘要质量、实际上界、费用、请求次数或性能验收通过。

历史记忆只用于快速定位既有预算/IPC主题；关键结论已按本轮源码与发布源码重新核对。

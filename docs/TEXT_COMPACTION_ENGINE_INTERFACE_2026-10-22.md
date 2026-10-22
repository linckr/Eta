# 便携文本摘要引擎：接口、断点适配与回滚设计

> 扩展架构设计。v4 本次实现选择现有 Session 快照提交机制和隔离文件断点，不引入这里描述的全量 Room artifact/node/CAS 协调器。具体实现见 [文本切片说明](PORTABLE_TEXT_V4_IMPLEMENTATION_NOTES_2026-10-22.md)；旧成果保留、不会自动导入新引擎。以下抽象类型及导入器不应当作已接入代码。

日期：2026-10-22。状态：接口设计，未实现；配套 [策略与联合迁移 v3](CONTEXT_COMPACTION_POLICY_MIGRATION_V3_2026-10-22.md)。不修改已有摘要文件或手机数据。

## 1. 职责与数据类型

新引擎只生成可验证的候选和进度，不直接改活动上下文，不自行重放 Journal、不执行历史工具、不静默切供应商。

```kotlin
interface TextCompactionEngine {
    suspend fun advance(request: TextCompactionRequest, events: ProgressSink,
                        cancellation: Cancellation): EngineOutcome
}
data class TextCompactionRequest(
    val operationId: String,
    val source: JournalViewRef,        // 稳定消息身份、读取 revision、流式访问
    val expectedGeneration: Long,
    val baseSummary: VerifiedSummaryRef?,
    val selectedRange: SourceRange,    // 有序连续旧前缀，结束于允许的边界
    val protectedTail: TailAnchor,     // 当前请求及未闭合交换，不在模型输出中重建
    val budget: BudgetSnapshot,        // 当前模型/I/B/L/H、完整投影测量器
    val backend: SummaryBackendRef,    // 摘要 Provider/模型/能力与参数快照
    val deadline: Deadline,
    val quota: SharedRecoveryQuota,    // 整次最多一次恢复，含精简
    val resume: TextManifestRef?
)
sealed interface EngineOutcome {
    data class Ready(val candidate: CandidateRef): EngineOutcome
    data class Paused(val progress: TextManifestRef, val reason: PauseReason): EngineOutcome
    data class Failed(val failure: SafeFailure, val progress: TextManifestRef?): EngineOutcome
}
```

这些是待实现的类型契约，不是仓库里已存在/已编译的 API。接口应能用 fake Provider 与内存 Journal 测试，不强依赖 Android UI。

### 1.1 必需记录

- JournalViewRef：conversation_id、read_generation、canonical_version、稳定有序消息 ID 列表的索引引用；读取时遵守当前敏感工具脱敏，不持有整份 Journal JSON。
- SourceRange：start/end 稳定消息 ID、source_digest、边界类型；完整工具交换与签名状态由既有边界检查器判定。
- 大消息的中间进度另用 SourceSpan(startPosition,endPosition)，position 包含 messageId、字段路径和 UTF-16 offset/continuation，偏移不能切断代理对；verified_cursor 可停在片段中，但 CandidateRef 只能覆盖已经闭合的完整边界。片段摘要可以工程验证并保存，不能仅因它存在就提交半条工具结果。
- VerifiedSummaryNode：node_id、artifact_ref/digest、完整文本字节数、source range/span/digest、commit_boundary、生成后端及算法/提示/参数版本、origin、validation_state。verified 指工程检查通过，不代表语义绝对正确；片段位置元数据不包含原文字段值。
- TextManifest：record_version=3、checkpoint_id、operation_id、expected_generation、base_summary_ref、selected_range、已验证游标、节点引用、策略/算法/提示/canonical 版本、后端参数引用、status。凭据不写入。
- 无稳定 messageId 的旧 Journal 使用明确标记的 legacy generation+ordinal 来源引用并流式复核；可以在私有索引中建立本地身份映射，但不能把该映射说成原模型提供的消息 ID。编辑/重排使受影响映射失效。
- CandidateRef：上述来源及 artifact、覆盖终点、保留尾部锚点、generation、最终协议预算测量结果；不得拿一个裸字符串当作“已覆盖所有历史”。

正文 artifact 是应用私有不可变文件，manifest/node 元数据在 Room；失败表和普通遥测不存文本。若摘要生成含敏感数据，沿当前脱敏/用户可访问范围保存，不创建电脑副本。

## 2. 一次 advance 的顺序

1. 读取当前 source/generation 与断点，验证版本兼容、来源和连续覆盖；需要新摘要时选择能争取余量的连续旧前缀，不把整个 Journal 默认重放。
2. 将已有已提交摘要与本次增量作为数据输入；一次可装下则直接总结，否则以现有安全切片器分片。片段本身也满足摘要后端的 I/L/H。
3. 接收输出：流式计数、完成状态、正文和协议检查；不要求模型生成 source/revision/事实 ID。
4. 完整且工程验证通过后，先将 artifact 写临时文件、同步并原子改名；再用 Room 事务登记 node 与推进 manifest。文件成功而事务失败只产生孤立文件，不推进游标。
5. Coordinator 使用候选摘要、未覆盖原文、受保护尾部以及原系统/工具定义重建**完整最终协议**，一次测量 token/字节，不循环序列化整份历史。
6. 达到目标返回 Ready；未达到但可继续选择安全区间则继续且受总预算限制；没有更多区间则返回有效但余量不足的候选或 Paused，按既有容量规则区分。
7. Ready 后由独立 CommitCoordinator 在事务中检查来源范围、generation、候选 artifact 及预算。前缀没变而仅追加尾部时，重建最新尾部并复测；超限不盲目提交。成功才发布活动快照、递增 generation 和清理对应失败状态。
8. 成功提交后再标记断点已完成；旧成果按保留/引用规则清理。取消、失败、CAS 冲突均不删除最近的已验证成果。

artifact 与 SQLite 不宣称跨文件系统的单一事务：通过先写不可变文件、再提交引用实现。垃圾回收仅删除确定无引用且无进行中 lease 的孤立文件。

## 3. 20,300 字符的确定处理路径

8,192 tokens 是请求输出目标，256 KiB UTF-8 是本地捕获内存护栏；两者不是字符上限，也不能互换。处理顺序如下：

| 条件 | 操作 |
|---|---|
| 后端支持输出上限 | 发支持的上限参数；不支持则不猜参数，不保证模型遵守目标 |
| 捕获尚在 256 KiB 内 | 连续读取，不因跨过 12,000 字符打断；只在临时缓冲中积累，未完成不建 verified node |
| 达到捕获护栏或连接中断/未正常结束 | 明确失败或受限恢复；不能保留前 N 字符当完整摘要 |
| END_TURN 且实际无工具调用/泄漏 | 保留完整正文，例如 20,300 字符；记录 chars、UTF-8 bytes、有效 usage 或估算类型 |
| 最终完整候选达 token/字节目标 | Ready；超出输出软目标只记目标偏差，不必额外请求精简 |
| 候选在允许发送范围内但余量不足 | 正常结束可形成“余量有限”候选；不假称满足目标 |
| 候选不满足允许容量且恢复额度尚在 | 同一来源与完整输出进入一次定向精简；仍受 L/H/I/捕获上限/剩余总时间限制 |
| 精简仍失败、额度已用尽或其输入装不下 | Paused/Failed，保留已验证原成果与完整源历史；不得再次精简或本地截断 |

“20,300 字符也能通过”只适用于新文本引擎和合法候选；不能放宽旧 JSON 树的增量操作验证来冒充成功。输出达到请求 token 限制后若 finish_reason=length，属于不完整响应而非合格长摘要。

## 4. 与旧断点的适配矩阵

保留当前 `AgentCompactionCheckpointStore`、`AgentCompactionTreeStore` 读写器，**只读适配**到新引擎输入，不覆盖原文件。新 record_version=3 使用新 namespace；不能复用旧 fingerprint 文件名写入不同 schema。

| 旧数据 | 校验与导入 | 失败/回滚 |
|---|---|---|
| 旧文本滚动断点 summary/cursor | 用旧读取器验证原结构、source fingerprint 与游标；查源身份后将完整 summary 注册为 legacy_text node，保留来源映射与原 checkpoint_ref | 旧 summary/游标不能验证则保持旧文件，提示继续旧引擎或从匹配来源重建，不强删 |
| 文本游标停在消息字段片段中 | 只导入已能证明完整来源的成果；否则由旧引擎续到可验证的完整边界再桥接，或在新空间重建相应范围 | 不把半字段游标伪装成完整工具边界 |
| 已验证结构化树 memory+pending | 旧校验器验证 schema、事实与来源；按客户端规则完整渲染事实为文本输入；选择不重叠的覆盖区间，已合并 memory 不与其旧 leaf 重复导入 | 未验证增量不导入，不为压低条数截断；旧树仍可由旧引擎恢复 |
| 旧 270 段 | 同上按覆盖索引逐批读取，不在 DB migration 中一次转换；只取本轮需要的区间与合法基础摘要 | 保留原文件、游标和版本，不声称每次必须读完 270 段 |
| 当前活动摘要（ConversationMessage） | 作为已提交的 base_summary_ref，核对当前覆盖和 Journal 来源；原消息仍可读 | 新节点生成失败不撤销当前活动摘要 |
| 原生加密项/签名绑定状态 | 不转换为便携文本，不解密猜测；按原协议能力隔离 | 换 Provider 需要从便携摘要/Journal 有界重建，不能携带不兼容 opaque 状态 |

跨模型只复用来源和语义版本兼容的已验证成果，后端输入尺寸、输出参数、预算和 deadline 重新计算。旧指纹包含完整 config 时不能凭名称猜对应历史；需提供旧 key 与实际源范围可验证映射。

新算法不会“纠正”旧事实内容或凭空补来源。来源不足时不可桥接的情况要明确展示，而非以自动迁移成功掩盖。

## 5. 新旧引擎并存与回滚

- 开关为 conversation/operation 级 engine_id；进行中的任务按启动时的版本恢复，不被全局默认切换劫走。
- DB v25 的兼容回滚版本同时保留旧读写器、新表和 manifest 最小读取器；回滚默认仅切引擎，不删新成果。
- 已提交文本摘要采用现有活动会话 DTO；在兼容回滚包中可继续聊天。新断点仍由新读写器保留，旧引擎若不能恢复则说明限制，不伪装为断点续压。
- 不直接安装 DB v24 的旧 2002 APK 回滚，不用 destructive fallback 清库。备份恢复存在丢新消息风险，另行处理。
- 文件 cleanup 与 schema 降级不是回滚的一部分。完成一次新提交之后，至少保留前一已提交活动快照到该轮结果确认及迁移验收结束。

## 6. 质量基准冻结、核对责任

1. 冻结数据由本地工具从已授权范围提取并计算哈希；真实历史正文留在原受保护目录，不加入 Git。可提交合成数据、匿名化 rubric 和无正文 manifest。
2. 在查看候选摘要前，用户作为业务事实确认人批准关键事实清单：目标、禁止事项、已完成动作、状态变化、关键数值/路径、未决事项及对应 Journal ID。代理可整理初稿，但不能自己出答案再自评通过。
3. manifest 固定 source range/hash、rubric 版本、模型/Provider/参数、算法/提示版本、预算、预期关键项和计分规则；标注“仍相关/已失效”由原文证据决定。
4. 精确数值、标识、来源与覆盖用确定性脚本核对；语义保留和状态冲突由用户或指定人工审阅者复核。测试模型自评只能辅助，不能当最终真值。
5. 每组至少 50 项相关事实/其中 15 项关键项；关键项 100%、其余相关项合计>=95%、错误关键事实为 0，另测 20 项原文回查。基准修正必须升 rubric_version 并留下理由，不能看到失败后悄悄删难题。
6. 人工尚未确认的集只能用于预评估，不可宣布真实摘要质量验收通过。真实模型运行按事先批准的请求上限执行，不能为收集指标默认发送额外历史。

## 7. 实施交付物

- TextCompactionEngine 与 fake backend/Journal、CommitCoordinator、artifact/manifest 仓储接口及异常分类。
- v3 联合 Entity/DAO/迁移、设置 UI 与所有发送路径统一检查，包括恢复、手动继续、模型切换和 once permit。
- 旧文本/树/部分游标/270 段适配测试、20,300 字符处理路径测试、追加/编辑竞争与崩溃顺序测试。
- 来源索引性能测试、同协议完整请求一致性测试；摘要模型实际输出目标偏差与端到端成本指标。
- 质量基准及用户确认记录、可读兼容回滚包设计；尚未人工确认/尚未真机验收的项目明确列待验收。

本文件是阶段 1 的独立工作量定义，不将它隐藏在“调整几个阈值”里。

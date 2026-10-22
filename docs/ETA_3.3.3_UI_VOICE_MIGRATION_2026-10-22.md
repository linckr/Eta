# 3.3.3 UI、语音与外部会话迁移记录

日期：2026-10-22。本记录说明源码决策；构建、回归及安装的最终结果由统一交付报告记录。

## 合并结果

- 以3.3.3的消息分页、导航恢复和队列UI为骨架，保留本地完整Journal/模型投影和摘要状态展示。卸载的会话仍只能更新元数据，不把未加载正文写为空。
- 普通发送沿用上游短按排队、长按中断后发送；新增独立的“本轮补充”文字操作，向当前run发送文本，不取消当前任务，不挪用队列的含义。含附件的草稿不能通过文本补充入口发送。
- 本轮补充沿用requestId/runId、ACK、重复请求幂等与拒绝/超时保草稿；ACK返回后只有相同草稿才清空，避免覆盖用户在途编辑。
- 主输入框使用按会话持有的AgentChatComposerDraft，而非新建局部TextFieldState；上游inputSyncVersion仍用于队列撤回和编辑同步。运行状态改变不会取消普通听写。
- 小布外部活动任务重接前先加载/pin所属完整会话；活动投影和归档仍共用稳定消息ID，保留原会话继续发送以及附件/草稿隔离。
- 语音保留BREENO实验Provider、分段续录、结果隔离和完成回填。在上游recognitionEnabled总开关关闭时，先退出再获取音频租约，不要求云凭据。小布也无需云凭据。
- 主聊天听写采用上游!isCompacting限制；助手正在执行时仍允许输入补充，语音按钮与新增TTS/ASR总开关合并。
- 保留Gemini/MiniMax TTS、新语音设置拆分、新常亮、分页和Markdown/LaTeX修复；不重新应用旧浮窗展示补丁。
- 三语言资源以上游完整XML为基础合入本地50项独有资源；已通过XML解析检查，不靠拼接局部冲突块维持标签结构。
- 本轮补充入口位于工具栏上方的独立小行，避免英文标签挤占原工具按钮。

## 交叉审查修复

- 压缩中提升队列项：入口和纯reducer均拒绝修改，避免先弹出却既不取消也不派发而丢项。
- 卸载后加载：两次最新状态合并均保留queuedMessages/inputSyncVersion，停止后仍保留的队列不会被数据库的空临时状态覆盖。
- 队列项派发被文件权限或忙态拒绝：恢复到队首、保留附件及草稿，清中断优先级，不自动形成重发循环；等待用户明确重试。

## 回归范围

保留上游AgentMessageQueueReducerTest、分页/加载/持久化测试和SpeechLifecycleTest，保留本地AgentChatComposerDraftTest、BreenoSpeechTranscriptTest、外部投影与摘要中间态相关测试。

新增AgentConversationStoreTest.pagedAndUnloadedConversationKeepsCompactedProjectionAndCompleteJournal：150条原Journal与独立摘要投影，经128条消息页、卸载、保存、关闭DB再打开，要求投影、全部原Journal和150条UI消息均保留。

另新增压缩态队列提升/停止后恢复、拒绝派发后的幂等恢复、加载卸载会话保留队列和草稿同步版本三项回归。统一执行结果以根代理交付记录为准。

静态编译若通过仍不能证明ColorOS小布引擎、Xposed加载、语音实际回填或后台重接已经实机验收。此代理未安装、未调用真实模型、未要求用户说话，也未修改手机网络。

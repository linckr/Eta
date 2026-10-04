# 小布 ASR Hook 点调查

更新日期：2026-10-04。设备：RMX3888。小布版本：13.3.2。0419 用户确认完成回填和闲置恢复有效；最新安装版为 3.1.0-local，versionCode 2026100421，包含运行中语音、旋转保护和任务浮窗预览修订，等待新增行为的真机验证。

范围：标准识别服务检查、当前安装 APK 的定向分析、Debug Hook 实现、编译安装和用户手动短句真机验证。初始阶段为只读分析；后续已修改并安装测试 APK。识别服务设置未修改，录音均由用户手动启动。只转写实验中意外触发的 Eta 思考已定位并在最终测试中隔离。

## 结论与验证边界

**已在真机验证普通/流式 ASR 结果捕获、单次“只转写、不启动 Eta 思考”的实验路径，以及 0417 连续三轮独立识别。第四轮引擎释放及停顿续说仍有问题，尚未完成 Provider 验证。**

| 项目 | 当前结论 | 证据 |
| --- | --- | --- |
| 小布标准 RecognitionService | 当前查询未发现；仅有 Eta 服务 | 手机注册信息 |
| 普通 RecognizeResultOperation | 0417 已观察到真机最终结果命中 | 09:22:36.080 FINAL chars=22 |
| 流式 Operation 与 a0.w | 电源键唤醒后均命中，中间与最终结果对应 | 两层 ASR_PROBE 元数据 |
| 原生 ASR-only 启动 | 单次短句测试已进入分支并返回终态 | 分支专属观察日志和最终结果 |
| Eta 自动思考来源 | 本次实验来自 AIChat 查询接管 | source=aichat 拦截日志和任务时间 |
| 只转写且无新 Eta 任务 | 0405 单次短句测试通过 | 最终结果 19 字；任务时间未变化；无 in-flight 任务 |
| 独立 ASR Provider / 输入框交付 | 0417 连续三轮出文字，第四轮引擎释放失败；0418 待验证 | 三轮 FINAL chars=7、5、22 及客户端 code=3；随后 releaseEngine 中断录音 |

最终测试抓到 14 组流式 Operation/共用回调事件：13 组中间结果、1 组最终结果。终态长度均为 19，编号一致，两层相差 2 ms。日志未保存识别原文，因此不把长度一致等同于逐字识别准确。

首选命名稳定的两个 Operation.process() 作为结果捕获点；混淆的 a0.w() 适合作为交叉验证和备选。生产接入需同时处理原生仅转写启动、请求身份和 Eta 的 AIChat/Nlp.Text 接管隔离。长语音、连续重录、取消、超时、无小布弹窗、OTA 兼容和正式 Release 构建仍未验证。

## 安装包与标准系统 ASR

- 包名：`com.heytap.speechassist`。
- 当前安装版本：`13.3.2`，versionCode `130302`。包信息还包含出厂版本 `11.8.3`，本报告分析的是当前安装版本。
- APK 大小：126,427,686 字节。
- APK SHA-256：`D4DF69617DA25BA8B87B1EA400D1C5CB32AB6DCA3EABF123B46B81D8D5FFEDA6`。
- `settings get secure voice_recognition_service` 返回 `io.github.mangi.eta/.agent.voice.EtaRecognitionService`。
- `cmd package query-services --brief -a android.speech.RecognitionService` 仅发现 EtaRecognitionService，没有发现可直接调用的小布标准 RecognitionService。该结论限于这台手机当前可查询的注册服务。
- EtaRecognitionService 自己委托外部 SystemSpeechRecognizer，不是小布语音引擎的公开包装。

## 1. 首选捕获点：识别结果 Operation

### 普通识别

```text
com.heytap.speechassist.core.engine.skill.RecognizeResultOperation
public void process()
```

此方法读取 `getDirective().getPayload()`，转换为：

```text
com.heytap.speech.engine.protocol.directive.speechrecognizer.RecognizeResult
```

文本字段为 `getContent(): String`，终态条件为 `getType().equals("FINAL")`。`getConversationInfo()` 提供 `recordId`、`currentRecordId`、`roomId`；payload 另有 `getAssociatedOriginalRecordId()`。

### 流式识别

```text
com.heytap.speechassist.core.engine.skill.StreamRecognizeResultOperation
public void process()
```

payload 为 `com.heytap.speech.engine.protocol.directive.speechrecognizer.StreamRecognizeResult`，文本和终态判断同上，还包含：

- `getMode()`、`getSeqNo()`、`getReplaceIndex()`：流式拼接/替换信息。
- `getOneshotRound()`、`getWakeupWord()`：轮次和唤醒词标记。
- `getAssociatedOriginalRecordId()`：关联原始录音请求。

两个 Operation 都把文本、`type == "FINAL"` 的布尔值及 Bundle 传给 `zq.e.INSTANCE.a().w(...)`，然后设置 `OperationStatus.SUCCESS`。

注册证据：`zq.e.b()` 显式把 `SpeechRecognizer.RecognizeResult` 和 `SpeechRecognizer.StreamRecognizeResult` 分别映射至上述 Operation 类。这不是根据类名推测的入口。

**捕获时建议先允许原方法执行**，保留小布字幕、状态与成功回调；仅在最终、非空且属于 Eta 接管请求时交给 Eta。普通和流式可能指向同一次请求，需要按请求关联编号去重，不能只按文本去重。

## 2. 共用回调与备选 Hook 点

```text
com.heytap.speechassist.core.engine.a0
public void w(String text, boolean isFinal, android.os.Bundle bundle)
```

`a0` 实现 `zq.d`；DEX 接口扫描发现其为该接口的直接实现类。`zq.d` 声明 `w(String, boolean, Bundle)` 和 `onWaitAsrResult()`。

`a0.w()` 的下游顺序：

1. `v0(isFinal, bundle)` 检查当前请求相关状态；其中 `L0(bundle)` 比较结果的 recordId/currentRecordId 与该 room 的当前请求。
2. `core.e.b().onPartial(text, isFinal, bundle)` 更新识别状态。
3. 条件满足时安排异步回调。
4. 优先通知当前 `rq.n.onAsrResults(text, isFinal)`；否则遍历 `rq.m.onAsrResults(...)`。
5. 最终结果未被上述监听器接管时，调用 `pq.b.h(text)`，继续交给原生管理器。

`rq.m.onAsrResults(String, boolean): boolean` 是监听器消费接口，`rq.n` 继承它并提供开始、完成、取消回调。但返回 true 只能证明这个监听器循环的后续分支被跳过，不能证明服务器没有已经生成 NLU 或其他原生指令。

OTA 适配：a0/w 是混淆名，不应跨版本硬编码。可在 `com.heytap.speechassist.core.engine` 内结合 `(String, boolean, Bundle) -> void`、字符串 `onAsrResults, return: `、调用 `onPartial` 和 `onAsrResults` 做 DexKit 唯一匹配并校验。Operation 类则优先直接查找命名类与无参 process()。

## 3. 流式合并和更底层边界

当前版本 `com.heytap.speech.engine.connect.core.manager.i`：

- `p(JSONObject header, JSONObject payload, ConversationInfo)` 按 `SpeechRecognizer.RecognizeResult` / `SpeechRecognizer.StreamRecognizeResult` 分发。
- `l(JSONObject, ConversationInfo)`：普通识别，读取 type/content 后调用 t。
- `q(JSONObject, ConversationInfo)`：流式识别，调用 `com.heytap.speech.engine.constant.StreamAsrRecorder.updateStreamASRBean(recordId, content, mode, seqNo, replaceIndex)`，把合并后的文本写回 payload.content，然后调用 t。
- `t(String type, Integer oneshotRound, String text, ConversationInfo)`：构造包含 recordId/sessionId/text 的 JSON。非 FINAL 设 eof="0" 并发布 `asr.speech.text`；FINAL 设 eof="1" 并走结果分支。

因此不能直接把每个网络 payload.content 拼起来；优先在已有合并之后捕获，或复用等价的 mode/seqNo/replaceIndex 规则。命名 Operation 的流式文本是否在真实电源键路径上始终已经合并，仍需实际调用顺序验证。

Eta 现有 `BreenoHooks.extractTextRequest()` 仅识别外发事件 `Nlp.Text`，不是上述 SpeechRecognizer 结果入口。现有 `hookCdmTextRequest()` 是启动参数/图片辅助入口，也不是最终 ASR 结果回调。

## 4. 只转写路径：已定位，但尚未验证现代入口覆盖

```text
com.heytap.speech.engine.nodes.g
public final void i(String action, String optionsJson)
```

在 `i0(String topic, byte[][] parts)` 中，主题 `asr.ctrl` 转入上述 i；action="start" 的分支：

- 输出日志 `ProcessorImpl / start asr only `。
- 切换至 LISTEN，把内部 asrOnly 标记置为 true。
- 调用 `o0(optionsJson)`。

`o0()` 的云识别分支构造 DmParameter，设置 aiType 为 ASR（启用云唤醒校验时为 wakeup），再启动录音和云请求。`n(boolean finalResult, byte[][])` 在 finalResult=true 且 asrOnly=true 时发布 event.asr 后回到 IDLE，并执行结束处理；普通分支则继续处理状态。

另一个关键点：`com.heytap.speech.engine.nodes.a.p(DmParameter)` 是 CdmNode.voiceStart 的当前混淆方法，含字符串 `voiceStart , cdmStartEntity = `；它把 DmParameter.aiType 写入 `VoiceStartEventPayload`。可研究在**专用 Eta 转写会话**启动时使用 ASR 类型，但不能全局修改所有语音请求。

上述证据证明 APK 存在仅转写控制逻辑。尚未证明电源键唤醒和现代 AIChat 使用相同分支，也未证明可以不显示小布 UI 而稳定启动。不能据此宣称已经实现系统级、无弹窗的小布 ASR 服务。

## 实现与真机验证建议

1. 加仅观察 Hook，分别记录 Operation 与共用回调的命中、final 标记、文本长度、轮次和请求编号的短哈希；不记录音频、原文或整个网络消息。
2. 用户用无操作语义的固定测试短句验证普通/流式、停顿终态、取消重录、两次相同短句。确认完整文本与去重行为。
3. 先验证 asr.ctrl 专用转写请求。若实际入口仍有原生 NLU/指令，应基于该次转写的请求关联编号过滤，保留识别与生命周期事件。不得全局丢弃所有小布指令或整个含 ASR 的消息。
4. 建立 Eta 显式转写请求状态：请求编号、会话编号、超时、取消和一次性最终提交。避免识别出来后再被现有 Nlp.Text 路径重复提交。
5. 只有真机证明仅转写、取消和应用退出后的恢复行为后，才把它包装成 ColorOS 专属 ASR 选项。

## 本地证据位置

第三方 APK 和定向反编译结果保存在忽略目录 `.analysis/asr-20261004/`，不作为源码提交：

- `speechassist-13.3.2.apk`：手机当前安装包。
- `dex_inventory.py` / `dex-classes.json`：相关 DEX 类清单。
- `find_asr_listener.py`：扫描 zq.d 的直接实现类。
- `JadxAsrTargets.java`：定向提取工具；仅处理目标类及相关节点。
- `targets/sources/`：上述类的反编译证据。

Jadx 对 MessageProcessor.i.z() 的复杂分发代码未能完整还原；本报告不把它的缺失方法体当成可执行代码证据。结果 Operation、a0.w、i.l/q/t 和 ProcessorImpl.i/n/o0 的上述结论来自已输出的方法体。

## 推进记录：观察版

2026-10-04：新增 `BreenoAsrProbe`，在模块 APK 为 debuggable 时安装两种 Operation.process() 与 13.3.2 的 a0.w() 观察 Hook。无论观察读取成功与否，都只调用一次原方法；不改参数、返回值、录音或原生指令。日志前缀 `ASR_PROBE`，内容仅含序号、单调时钟、入口、最终标记、字符数、轮次、流式序号及进程内加盐的请求/房间短哈希；不包含识别原文或原文哈希。

- Debug APK 编译成功，versionCode `2026100401`，保留此前模型兼容修复。
- 交付文件：工作区根目录 `Eta-3.1.0-local-asr-probe-debug.apk`。
- SHA-256：`D1CAB4FB79A45ECCCE1F5FDFF2310F31902701A9871D22C277807E699C0A459E`。
- APK 已传至 NAS 临时目录。当前 Tailscale 显示手机在线，`magicnet-adb connect` 两次未发现已配对的 ADB 服务，等待核对无线调试端口；尚未安装新 APK，尚无新增 Hook 的真机命中证据。
- 安装后需重启小布进程以加载新 Hook，再由用户说固定短句验证。此阶段仍然没有开启文本转交或 ASR-only 指令拦截。

### 首次真机命中

无线调试重新开启后，经 NAS 连接成功并安装观察版，包信息确认 versionCode `2026100401`。重启小布进程后，用户长按电源键唤醒；在小布 PID 32271 中捕获如下两个观察事件（内容仅为元数据）：

```text
n=13 t=114214091 layer=StreamRecognizeResultOperation final=true chars=17 record=65855503c6a1 current=65855503c6a1 room=2c58bf1fdd71 seq=1
n=14 t=114214092 layer=callback final=true chars=17 record=65855503c6a1 current=65855503c6a1 room=2c58bf1fdd71 round=0 wakeup=false
```

该次终态事件证明：电源键唤醒的小布进程确实命中流式 Operation 和共用回调；两层文本长度和关联编号一致，时间相差 1 ms。尚未证明普通 Operation 命中、完整中间结果序列或 ASR-only 分支。随后启动仅过滤 `ASR_PROBE` 元数据的实时采集，等待用户说固定测试短句。

用户确认测试短句已说完。实时采集观察到同一个请求的 10 组 Operation/共用回调事件：9 组中间结果、1 组最终结果，文本长度依次为 2、6、7、9、11、13、14、16、16、17。观察到的每组长度、final 标记和关联编号都一致，终态两层相差 2 ms。日志序号中缺少 3/4，不能把本次采集宣称为完全无丢失的全部事件。未采集原文，因此这里只证明长度与关联一致，不证明识别文字逐字准确。

### 一次性 ASR-only 测试版

新增仅 Debug APK 生效的临时入口：小布私有 cache 中存在 `eta-asr-only-probe`、修改时间未超过 90 秒、且下一次普通启动包含 roomId 时，先删除标记，再把该次 ProcessorImpl.j("start", ...) 转至原生 i("start", options)。显式设置 startVAD=true、oneshot=false、enable_wakeup_cloud_check=false；同步发起的 p0(DmParameter) 保留该次 roomId。其他输入调用原方法；不在启动异常后再发第二次录音请求。临时记录接下来 70 秒收到的指令类型，不含 payload 或原文。当前版本混淆名仅用于真机实验，未作为生产兼容规则。

- APK `Eta-3.1.0-local-asr-control-debug.apk` 编译成功并安装。
- versionCode `2026100402`。
- SHA-256 `68AC03B62A2C509D679E6EE6E92596093DF70B79D43558BA7422E4BD6BC5FE22`。
- 已重启小布进程、放置临时标记、启动元数据采集，等待用户手动输入。此版仍未把识别结果交给 Eta 输入框，也未启用全局指令拦截。

用户反馈测试后仍由 Eta 接管思考。采集记录只有正常流式 ASR 事件，没有 `ASR_CONTROL one-shot start`，因此该轮未证明只转写入口命中，不能算 ASR-only 成功。检查时临时标记已消失；旧版会在过期时静默删除，过期是待验证解释，不能断言为根因。

已增加过期/房间缺失/无法消费标记的诊断日志，将一次性窗口改为 10 分钟，并编译安装 versionCode `2026100403`。APK SHA-256：`61D586FB75691EA902EF621704EC2EB27F335766FCEEFEC8545AD2CA882F2715`。重新准备下一次用户输入，等待确认分支命中。

### 只转写分支与 Eta 自动接管的区别

0403 测试出现了仅由已激活实验分支产生的指令观察日志，证明分支实际进入。采集到的指令类型为 CustomerData.UploadResult、SpeechRecognizer.StreamRecognizeResult、SpeechRecognizer.VadControl，未在本次采集记录中看到回答/任务指令；但这不能单独证明 Eta 没有运行。

在用户提醒后，于 Eta 手机本地 Debian rootfs 的 `/usr/bin/sqlite3` 找到 3.46.1。使用 rootfs 的动态加载器与库路径直接运行它，`-readonly` 查询 Eta 数据库，仅返回 created_at 时间戳；未复制数据库、WAL、SHM 或会话内容。最新任务时间为 `1791069360427`（北京时间 07:16:00.427），本次 ASR 终态按手机 epoch/uptime 换算约为 07:15:55.868。表明终态后约 4.6 秒确实创建了 Eta 任务，与用户看到思考的现象一致。

因此 ASR-only 原生启动并不足以阻止 Eta 现有 Nlp.Text / AIChat 查询接管。0405 新增单次请求隔离：仅在显式准备的转写测试中，根据第一次 ASR 的 roomId、recordId、currentRecordId、associatedOriginalRecordId 关联，跳过对应 Eta Runtime 启动；同房间另一个请求、其他房间、空编号、过期或重新准备后的旧编号不受影响。记录 `ASR_CONTROL runtime_suppressed source=...` 以区分实际接管入口。

- 两项身份/期限隔离测试通过，APK 编译成功并安装，versionCode `2026100405`。
- APK SHA-256：`1DF3DDBECF24F99758098DDDC9747FD417E20C34A7DD0A72B36867FF478F089A`。
- 测试前最新 archive created_at 仍为 `1791069360427`。
- 曾为本地时间核对临时加入仅 Debug 的权限保护诊断 Receiver，用户指出 Linux 环境后已移除；最终 0405 无此入口。
- 已准备下一次短句测试，等待接管日志及任务时间对比。此阶段仍未完成 Eta 独立 ASR Provider、输入框插入、无小布弹窗使用和取消/超时验证。

### 0405 真机验证结果

用户确认已说完。PID 30345 的采集中，流式 Operation 和共用回调出现 14 组对应事件（13 组中间结果、1 组最终结果），最终文本长度均为 19，终态两层相差 2 ms，同房间、同请求。随后出现：

```text
ASR_CONTROL runtime_suppressed source=aichat room=fcbabff9dfdf record=ef3cccacf3f2
```

再次在手机本地只读查询，最新 runtime_archive_runs.created_at 仍为 `1791069360427`，runtime_inflight_runs 无记录。对本次固定短句测试，证明原生 ASR 结果正常返回，Eta 的 AIChat 接管被本次请求隔离成功，未生成新 Eta 任务。此结论不扩展到长语音、连续重录、超时或无弹窗场景。

确认的真实链路为：ASR-only 启动 → StreamRecognizeResultOperation → a0.w → AIChat 查询 → Eta 原有接管。独立 ASR Provider 必须拥有自己的请求身份，并在文本交付前消费对应 AIChat 查询，不能仅把云请求 aiType 改为 ASR。

### 独立 Provider 接入：0407

新增“小布（实验）”识别选项，接入 Eta 原有识别设置、测试区和输入框控制器。默认识别选项不变；无需配置额外 API 密钥，仍依赖小布服务自身的网络及权限。

- 启动通过限定小布包名、受 Eta signature 权限保护的控制广播；识别文字仅通过该次请求的 ResultReceiver Binder 返回，不通过导出广播传递。
- 在原生 DmParameter 发起录音之前设置独立 recordId，并维护 roomId 和关联编号。仅消费本次转写的 AIChat/Nlp.Text 接管；保留最多 8 次、每次 120 秒的身份记录，隔离取消后的延迟结果和连续输入。
- 捕获普通/流式 Operation 结果，原方法调用一次；部分结果更新预览，终态交给 Eta 控制器，按现有自动结束或手动“完成”规则提交。
- “完成”调用原生 stopAsrOnly；取消调用 asr.ctrl cancel。启动后 65 秒、手动结束后 12 秒仍无终态则报错并取消。不同请求编号不能结束当前请求。
- 使用字符串锚点和结构校验定位处理器，不把 13.3.2 的混淆方法名作为 Provider 规则。修正 Java 数组反射名与 DexKit 类型名不一致导致的参数校验失败。
- 接口缺失时不注册桥接；服务未初始化时给出提示。当前仍需重启手机或小布进程后手动唤醒一次小布，尚未实现冷启动服务。

验证：Debug 编译成功，BreenoAsrProbeGateTest 2 项和 BreenoAsrClaimsTest 3 项通过。已覆盖请求期限、空编号、不同房间、旧结果与新请求隔离、身份历史容量。单元测试不证明录音结束或 IPC 在真机可用。

手机安装成功且包信息核对为 2026100407。交付 APK：`D:\下载\agent\eta\Eta-3.1.0-local-breeno-asr-debug.apk`，SHA-256：`9C86DD329A4A4ED78BF972AB4AB52DBF0397C719D3C68835A1799203B0331CB4`。这是 Debug 签名构建，尚未做正式 Release 验证。

0407 重新唤醒小布后，PID 24541 日志确认三个 DexKit 目标均定位成功，处理器、请求身份、外部中断、两个结果 Hook 均安装，出现 `Breeno ASR bridge ready`。这证明接口解析和桥接注册已在真机完成，不证明控制广播、录音或文本交付已成功。

0407 测试准备时，手机本地只读查询得到 archive_count=0、inflight_count=0；本轮以此作为任务数量基线，不沿用 0405 的历史时间戳。未读取归档内容，也未执行清理数据库操作。

剩余真机验证：Eta 内启动并回填文字、无新增 Runtime 任务、手动提前完成、取消后立即重录、无语音超时、长语音与后台恢复。此前 0405 的结果只证明一次性实验路径，不等同于这些 Provider 测试完成。

### 0407 独立入口实测失败与 0408 修订

用户反馈两项问题：测试区没有明确“完成”按钮，以及识别超时。日志只出现 `Breeno ASR started`，没有观察到最终文字交付。因此 0407 不可标为独立 Provider 可用。

0408 增加测试区显式“完成”按钮。静态检查同时发现 DialogManagerSwitcher.o() 仅在 DmParameter.recordId 为空时生成编号并更新自己的 current-record 字段；0407 提前填入编号会跳过此状态更新，属于已确认的实现问题，但尚不能单独认定为本次超时根因。修订为保留原生编号生成，在同步启动方法返回后关联其编号；只有实际取得编号才报告 LISTENING，否则立即提示引擎未就绪并取消。新增 `Breeno ASR native request: correlated=...` 元数据，用于区分没有发起请求与结果未回传。

0408 Debug 编译成功，相关 5 项单元测试通过并已覆盖安装。APK SHA-256：`78AA58D419234ED8046DFB3DE647D5FC42A9CFF479CF95C757C6BCA30A981BD7`。等待短句复测；取消/重录、超时、长语音仍未完成验证。

### 0408 仍超时：录音与进程生命周期证据

用户反馈仍超时。08:02:40.853，PID 24557 出现 `native request: correlated=true` 和 `Breeno ASR started`，未捕获最终回传。退出信息显示该进程在 08:03:16.853 被系统结束：reason=13、subreason=2054、description=o-kill(54)，约晚于启动 36 秒；不能把该厂商编号解释为已确定的具体策略。随后新 PID 30075 重建桥接。AppOps 显示 UID 的 RECORD_AUDIO 为 foreground，不能据此断言权限是唯一根因。

新增定向分析 AudioRecorderImpl 与 SpeechAudioRecordManager。AudioRecorderImpl.o() 委托 SpeechAudioRecordManager.G(Bundle)；后者在 u() 判定原生 conversationState 为 idle 时直接返回 null，不开启录音。原生 ASR 请求取得 recordId 不等于已采集音频，0408 的启动确认不足。

0409 为显式 Eta 转写添加局部适配：仅在该请求的录音启动调用栈内允许越过原生 UI 的 idle 检查，不修改麦克风权限、全局会话状态或普通小布启动。ThreadLocal 隔离启动调用栈；外部普通输入会先结束 Eta 请求。新增 `recorder eligibility: nativeIdle=...` 和 `recorder startup: available=...`，录音器返回 null 时立即取消并报错。这仍是待真机验证的修订；系统结束进程、后台麦克风和录音后的生命周期问题仍需独立核对。

0409 Debug 编译成功，身份隔离相关 5 项测试通过；这些测试不覆盖 Android 录音和厂商进程策略。

0409 已覆盖安装，APK SHA-256：`47FCD01A071C21B8B07DFD7959BCE9483BE8F6AB7A42C88830018F28D335CDB2`。交付副本仍为工作区根目录的 `Eta-3.1.0-local-breeno-asr-debug.apk`。

### 0409 实测：录音器创建但无终态

用户再次反馈超时。08:10:18.675 原生请求编号关联成功；08:10:18.677 观察到 nativeIdle=true；08:10:18.681 录音器返回 available=true。证明确实命中了 idle 分支并取得录音器对象，但不能证明收到 PCM 或送入引擎。08:10:52.335，该 PID 2203 再次被系统结束（reason=13、subreason=2054、o-kill(54)），约晚于启动 34 秒。

进一步静态证据：SpeechAudioRecordManager 的 onConversationStart 回调会为录音器调用 updateFireDataStatus；此方法设置允许向引擎送音频的标志，并关联 recordId。独立调用 ProcessorImpl 不等于已走过上层会话回调。录音器对象存在不能作为采音/上传成功的判断。

0410 增加两项适配：Eta 在本次识别期间绑定已导出的原生 SpeechService（BIND_AUTO_CREATE、BIND_IMPORTANT、BIND_INCLUDE_CAPABILITIES），收到服务回调后才发起转写；终态、取消、报错时解绑。使用专属控制 action，不发送 ENGINE_API 或 UI 唤醒 action；静态分析该 action 的 onBind 返回 null，客户端处理 onNullBinding 并保留绑定期间的生命周期。服务断开时提前报错。

本次录音器与原生生成的 recordId 齐备后，调用原生 updateFireDataStatus 打开该次音频发送；通过 RecorderNode.feedPcm 仅统计帧数，5 秒后输出计数，不读取、保存或输出 PCM 内容。同时记录客户端非 partial 回调代码。上述路径均待真机验证，不声称绑定一定能阻止 ColorOS 结束进程或解决后台麦克风问题。

0410 Debug 构建及相关 5 项单元测试通过。

已覆盖安装 0410，APK SHA-256：`CB7D4B13202327E4E1B4A39B4DD383D41DAD1D59DA5FAC20C1459AC1DCAF0697`。

### 0410 实测：零音频帧、快速停止

08:18:22.797 客户端服务绑定完成；08:18:22.821 收到 LISTENING（code=1），证明控制及 Binder 回调已贯通；录音器创建成功。08:18:27.822 引擎 feedPcm 计数为 0。用户反馈“小布识别超时，请重试”。

系统音频记录显示 UID 10092 的本次录音 session 2561 于 08:18:22.874 出现 rec update（not silenced），08:18:22.955 即 rec stop，间隔约 81 ms。AppOps 本次 allow duration 91 ms。证据不支持把本轮问题仅归为麦克风权限静音；但尚不能凭一次状态记录排除所有后台权限问题。

下一步定位录音停止来源，而不是延长超时。0411 保持原生停止行为，仅记录 BaseAudioRecordThread.interrupt 的原生方法调用来源、结构化录音错误码，以及 AudioRecorderImpl 停止原因的限定枚举字符串；不输出错误正文、参数、音频或识别内容。Debug 编译成功，APK SHA-256：`5367C33DEBB6D11CF0AA726F470D2572A8380C54C38B065A6EA31B9271D0C41A`。等待用户手动短词复测。

### 0411 实测：引擎主动停止及服务连接断开

08:22:56.318 录音器创建，08:22:56.321 即出现 `recording stopped: reason=engineCallStopRecord`。interrupt 调用链为 recorder.b.x → NormalAudioRecorder.x → SpeechAudioRecordManager.K/j → 原生清理 Runnable。5 秒后仍为 frames=0；用户看到“正在聆听”，随后提示“小布识别服务连接已断开”。退出记录确认同 PID 16855 在 08:23:35.126 被结束（reason=13、subreason=2054、o-kill(54)），importance=100。不能仅归为后台优先级低。

SpeechService 静态分析发现它注册了 interceptKillSelf 原生清理拦截器；默认仅在 UI mode 非 0 且 serviceForeground=true 时阻止自清理。绑定并不等于满足此条件。该证据提供适配点，但厂商退出编号仍未证明只来自这条路径。

0412 对显式转写期间的原生 interceptKillSelf 返回允许保留，结束后恢复原判断；不全局禁止进程退出。同时追踪 recorder.ctrl stop/stopall 的原生调用来源（仅方法名），并在未手动结束、尚无音频帧时收到原生停止后立即取消并报错，避免页面继续显示假聆听。仍需真机捕获停止指令生产者，独立 ASR 尚不可用。

0412 Debug 编译成功并覆盖安装；APK SHA-256：`A71F0448218294CF1D4A3BCD65C1674916BB30156FCEB0D635B255BEC49D843C`。

### 0412 命中具体停止路径；0413 隔离原生会话关闭

08:28:45.515 录音器创建；08:28:45.517 捕获原生停止指令生产链：`nodes.b.onMessage → g.j0 → g.i0 → g.j → g.C0 → g.g0`。结合 i0/j 方法体，证明是 `dialog.ctrl close` 调用 C0 发出 recorder.ctrl stop。用户确认立即看到提前结束错误；08:28:45.524 客户端收到 ERROR（code=4），不再假显示聆听等待超时。

此证据确认关闭路径，但未识别 bus 之前是哪个上层调用者发出了 close，不能把它描述为已证明由用户关闭弹窗触发。

0413 将 Eta 显式转写的生命周期独立于原生 dialog close：同一处理器、仍有活动 Eta 请求、未请求手动完成时，跳过该 close；保留本次同步启动前的原生旧会话关闭。新的普通小布 start 会先取消 Eta 请求。Eta 的取消/超时仍走 asr.ctrl cancel；手动完成走 stopAsrOnly；不全局屏蔽关闭或停止指令。需复测正常终态、取消恢复及原生错误结束路径。

0412 日志未出现 native lifetime Hook 安装，不能视为自清理拦截已生效。0413 改以 `SpeechService.foreground` 字符串锚点定位拦截器类，再找到 interceptKillSelf，等待核对真机安装日志。

0413 Debug 编译和相关 5 项单元测试成功，已覆盖安装；APK SHA-256：`480A32E69DFCA5D2D90DEB5840B2F0CBADCD1ED2AF19ABB1AB544D00712FCA90`。独立 Provider 的识别成功仍待验证。

08:32:34.546 真机日志确认定位 native-lifetime-name，08:32:34.549 安装 native lifetime Hook，随后 bridge ready。只证明该 Hook 已安装；仍需观察实际保留事件、音频帧和终态。

### 0413：首次聊天识别成功，后续录音被清理中断

用户反馈：唤醒小布后切换 Eta，聊天语音按钮首次能识别；进入设置测试后超时；再返回聊天也超时。

日志证明 08:37:36.125 服务绑定，5 秒时 frames=94；08:37:42.139 经原生 VAD 结束（g.L → z0），08:37:42.212 回传 FINAL chars=6，客户端 08:37:42.213 收到 code=3。结合用户反馈，证明首次独立聊天识别成功，不推断识别原文或逐字准确度。

08:37:55.720 第二次录音创建后，SpeechAudioRecordManager 的 K/j 清理 Runnable 调用 recorder.x，中断新录音；5 秒 frames=0。后续 08:38:51、08:39:18 也出现相同清理链。此时没有对应的 engineCallStopRecord 先行事件，区别于此前 dialog close 路径。两个入口使用同一 SpeechInputController，没有证据说明单纯是测试入口配置不同。

静态证据：管理器 H(Bundle) 先更新状态，再异步调度 K；K 在执行时读取管理器当前录音器，而非创建 Runnable 时绑定的录音器。存在清理前一轮而实际结束新一轮的可能；本次日志确认执行了 K，但尚未定位每次 H 的上层发送者。

0414 为活动 Eta 请求核对管理器当前录音器与请求持有对象的身份；未请求手动完成时，隔离命中该录音器的原生 K 清理。正常新小布输入、Eta 取消/超时会先解除 active，之后仍执行原生清理；手动完成允许清理；原生终态照常交付。结构化录音错误会主动结束请求，不依赖被隔离的 UI 清理。Debug 构建和相关 5 项测试通过，等待“聊天→设置测试→聊天”的连续真机验证。

0414 已覆盖安装，APK SHA-256：`4EC7FDEF5750622414BD029F3DC39ACA9903296F52D3C77CF5998C8EB59941A3`。

0414 连续测试前，在手机本地只读核对 archive_count=0、inflight_count=0；不读取会话正文。等待用户手动依次运行聊天、设置测试、聊天并核对终态。

### 0414 连续实测：已有音频，仍缺最终结果；0415 诊断版

用户确认：每次唤醒小布后，首轮测试有概率出文字；不再唤醒而启动下一轮则超时。同一 PID 23193 在 08:55:06.173 交付 FINAL chars=20，客户端收到 code=3；08:55:17、08:55:32、08:55:53 的后续请求分别在 5 秒时记录 frames=91、94、94，原生清理及 dialog close 隔离已命中，仍未交付 FINAL。原生 VAD 停止也有记录。因此现阶段不能再仅以零音频或录音提前结束解释连续失败；尚未证明是后端取消、连接状态还是结果关联失败。

静态分析发现 a0.x0(Bundle,Object,boolean)，字符串锚点为 cancelLastVoiceRequest recordId =。该路径会发原生取消回调并调用引擎取消。0415 仅在同房间存在活动 Eta 转写时隔离此取消，包含点击完成后等待 FINAL 的阶段；Eta 主动取消/超时先解除 active，仍执行正常清理。该取消是否在失败请求中命中、是否为根因均待真机验证。

0415 同时记录活动转写期间入站协议的 namespace/name，以及原生 FINAL 是否通过请求关联（只记录布尔值及字符数）。不输出协议正文、识别文字或 PCM。新增诊断不改变结果身份校验。

0415 Debug 构建及相关 5 项测试通过，保留数据覆盖安装成功，设备核对 versionCode=2026100415；已重载小布进程并开始限定元数据日志采集。APK SHA-256：`3E37EA75FACC6F5675D391989D61648AE63BA2BEE6F079C7110D823637F3E6CE`。交付副本：`D:\下载\agent\eta\Eta-3.1.0-local-breeno-asr-debug.apk`。等待用户唤醒一次后连续三轮短句测试；不宣称连续识别已修复。

### 0415 实测失败；0416 改为观察原生合并后的上游结果

用户反馈三次均不出文字，第三次主动唤醒小布后也不出。09:07:06、09:07:30、09:08:06、09:08:28 启动请求有 71–94 个音频帧，入站日志出现 SpeechRecognizer.StreamRecognizeResult 或 RecognizeResult，并伴随 VadControl；没有 native final observed 或客户端 FINAL。09:07:55、09:07:59 另有 correlated=false 的启动失败。入站指令类型本身不能证明是 FINAL，也不能证明属于当前请求；0415 尚未记录这两个属性。09:08:21 的 native voice cancellation 隔离只在后来重新唤醒时出现，不能据此确认它是此前失败的根因。

静态链路：MessageProcessor.C 接收 JSON，u 先通知原生 ASR 状态，随后执行 onDirectiveFilter、原始请求编号与 sequenceId 校验；通过后 z/p 处理识别 payload。流式 q 由原生 StreamAsrRecorder 合并，再调用 t(String,Integer,String,ConversationInfo)；普通识别 l 同样进入 t。之后 UI Operation.process 才负责回调 a0.w。当前证据提示 Operation 可能不是独立 ASR 的可靠交付层，仍需核对上游实际命中及原生过滤结果。

0416 增加 t 的文字交付备用路径：使用原生合并后的文字，仍按房间、请求编号/关联编号校验；原生方法照常执行，活动请求仅交付一次终态。另记录入站识别指令的 FINAL 布尔值、关联是否通过、字符数，以及原生过滤/校验的布尔结果。未绕过原生身份或序列校验，不输出识别正文。

0416 Debug 构建和相关 5 项测试通过，保留数据安装并核对 versionCode=2026100416；已重载小布。APK SHA-256：`9F2F7A3211A810B4C4535D48B9D9327CC308F10100FA94E2925B7A110B1201FE`。等待一次用户手动短词识别验证；备用回调是否命中、是否解决失败仍未确认。

### 0416 确认最终结果被原生编号校验丢弃；0417 严格独立身份适配

09:15:29.702 入站 StreamRecognizeResult 显示 final=true、correlated=true、chars=2，属于 Eta 当前请求。随后原生 onDirectiveFilter 返回 false（未拦截），F 返回 false（请求原始编号校验失败），H 返回 true（序列校验通过）。前两条 partial 也在 F 失败。5 秒音频帧为 91。没有 normalized result 或 Operation FINAL，因此本轮最终结果在 C 的 E/F 校验阶段被丢弃，而不是麦克风无音频或服务器没有识别出来。

静态核对 DmParameter.changeLocalOriginRecordId 默认 true，CdmNode.k 会登记房间原始编号、当前编号、序列和 session。因此“启动参数未登记编号”尚未被证明；还需定位原生状态被哪条路径覆盖或原始编号在协议中缺失。本轮只确认了 F 拒绝，不能将其推广为此前所有超时的唯一原因。

0417 在原生 F 拒绝时增加独立请求身份核对：仅允许消息 roomId 与活动 Eta 房间相同、recordId 与本地引擎生成的本次 recordId 完全一致，包含普通或流式识别结果，且全部指令仅为 RecognizeResult、StreamRecognizeResult 或 VadControl。未知编号、旧请求、其他房间、混合操作指令仍拒绝；H 序列校验仍保留。不放宽云唤醒 i 校验，不修改数据库或全局房间状态。通过后沿原生合并和处理链前进，由 0416 的合并文字回调或 Operation 交付。

新增 BreenoAsrResultIdentity 的 3 项测试覆盖两种结果格式、旧请求/其他房间/缺编号、空指令/仅 VAD/混合操作拒绝；本轮 8 项相关测试及 Debug 构建成功。测试不证明 Android Hook 命中或真机结果交付。已保留数据安装并核对 versionCode=2026100417，重载小布后等待首轮及不重新唤醒的第二轮测试。APK SHA-256：`5F3CFF7D6E30D2B78A7C311669569E0F47714D7F874F376E87B779C9DF8D9F8A`。

### 0417 连续三轮成功；延迟、引擎释放和停顿限制

用户确认连续三轮出文字；日志在 09:22:02.779、09:22:16.899、09:22:36.070 分别记录原生合并 FINAL chars=7、5、22，客户端收到 code=3。第三轮普通 RecognizeResultOperation 及 a0.w 也命中。这证明 0417 的严格独立身份适配和文字交付路径生效，不代表所有连续次数或长语音已验证。

首轮启动 09:21:56.933，首 partial 为 09:21:59.224；第二轮启动 09:22:13.685，首 partial 为 09:22:15.865，间隔约 2.29、2.18 秒。partial 从协议校验到原生合并回调仅毫秒级；没有录音起点标记，不能把整个间隔都归为网络或模型耗时，也未证明可消除首字等待。

09:22:40.030 第四轮启动后，09:22:40.710 录音被 `j4.e → EngineHolder$engineProxy$2.invoke → a0.k1 → SpeechAudioRecordManager.x` 中断；5 秒 frames=0。09:22:52.761 下一次启动 correlated=false，显示引擎尚未就绪。此证据确认释放路径，不能确认用户猜测的固定 30 秒阈值。静态 k1 会释放录音器并清空引擎监听器；j4.e 执行销毁回调后清空缓存对象，因此只保护录音清理或 interceptKillSelf 不足以保护完整引擎。

停顿后原生云 VAD 结束本句并发送 FINAL；此前客户端收到 FINAL 即解绑并停止，页面只等待点击完成，后续说话不会再被采集。0418 对手动完成模式改为分段续录：已完成文字累计，FINAL 后等待 200 ms 再以新的 UUID 发起下一段，保留同一次服务绑定；后续 partial 显示已完成段落加当前文字，点击完成等待当前终态并合并交付。自动 endpoint 模式仍收到首个 FINAL 即结束；取消移除待续录任务，旧段 Binder 回调按 UUID 忽略。总识别超时仍为 65 秒，点击完成后等待最多 12 秒，不能声称段落切换无采音间隙或无限连续。

0418 在 j4.e 层仅保护 EngineHolder 的两个缓存实例：活动 Eta 转写期间，或最近启动后 60 秒复用窗口内，跳过销毁回调及缓存清空；窗口外仍走原生清理。该保护没有主动初始化已释放的引擎，首次使用仍需唤醒小布。真机需确认缓存清理是否另有路径，以及正常小布输入是否保持可用。

0418 Debug 构建、原有 8 项身份相关测试通过；这些测试不覆盖 Android 分段录音、服务绑定或原生生命周期。已保留数据覆盖安装并核对 versionCode=2026100418，重载小布并开启限定元数据采集。APK SHA-256：`4F58A9BACC68065BE02630B3B3BBD41B149315F7822CE88CCFD18C3499A18D02`。等待用户两句中间停顿及一分钟后再次识别的手动验证，未确认上述体验问题已修复。

### 后续方向：原生长语音优先于分段重启

用户要求按钮开始至按钮完成保持同一段音频识别，不希望依赖每句 FINAL 后重启。0418 的分段续录只是现有短句 ASR 的适配，并不满足同一录音链持续运行的要求。

本次定向分析找到小布原生长语音路径：a0.q(Bundle,rq.n)（日志 beginLongAsr）注册专用 listener，并调用 LongAsrEngine.D(listener) / E(applicationContext,config) 开始；a0.stopLongAsr() 调用 LongAsrEngine.H() 手动结束；cancelLongAsr() 调用 q() 取消。长语音启动建立网络连接同时启动独立 ky.b 录音线程，音频可先入缓存再发送；H 停止录音并向仍连接的 WebSocket 发送结束消息。结果由 onLongAsrResult 回调，不使用先前的普通 RecognizeResultOperation 路径。离线支持函数 u() 当前返回 false。

这些是 APK 静态证据：支持考虑“一次启动、持续送音频、手动结束”的原生接口，但尚未证明该接口在本机鉴权和网络环境中可用，尚未完成监听器返回内容的累计/替换语义和超时处理。0418 安装包仍使用短句分段方案，不能描述为已接入长语音。后续优先验证原生 LongAsrEngine，避免绕过小布网络协议自行上传 PCM；无需先录完整段再上传，这样可保留实时预览。

### 0418 用户复测：停顿续录有效，手动完成失败

用户确认停顿后可以继续识别；间隔约一分钟再点语音，首次提示引擎未准备，第二次可识别。点击完成无法回填文字或停止识别。0418 仍是短句分段方案，不是用户希望的一次持续长语音请求。

日志证明 09:32:27.770 FINAL chars=12，09:32:27.973 自动启动下一段；09:32:28.852 手动 finish 导致原生 stop，5 秒 frames=0，但没有当前空段的 FINAL。源码还存在 Controller.finish 在 provider.finish 同步回填、取消后又覆盖为 RECOGNIZING 的问题。这两条路径都需要修正；不能只延长等待终态的超时。

09:32:20.189 首次绑定后 59 ms 收到 ERROR，且没有原生请求或录音启动日志；09:32:22.548 第二次绑定后成功启动。旧日志没有区分 processor 缺失、房间未准备或其他请求占用，尚不能唯一定位首次失败。

本轮只读设备退出信息确认 09:31:51.220 PID28917 和 09:33:14.272 PID13526 均被系统结束：reason=13、subreason=2054、importance=200、description=o-kill(54)。即使缓存清理保护命中，也不等于阻止系统结束进程。没有证明固定 30 秒或一分钟阈值。

### 0419 团队修订：手动完成及一次冷启动恢复

分工：finish_fix 修复客户端手动结束、文字累计和状态边界；engine_recovery 修复启动原因标记，核对冷启动证据；long_asr_review 只读分析原生长语音；主代理审查、统一编译安装及维护报告。

Controller.finish 现在先更新 RECOGNIZING，再调用 provider.finish，避免同步完成回调重置页面后又被覆盖。客户端 finish 幂等，移除续录及启动重试；当前段已结束时立即交付，有活动段时先发送 FINISH，最多等 1.5 秒。收到终态则用最终文字替换本段预览；超时则发送 CANCEL，提交累计段和已经显示的当前预览，不编造未返回的文字。cancel/complete 移除全部延迟任务，按段编号和 running 状态拒绝迟到/重复结果。该兜底优先保证可停止、可回填，不保证尚未返回的尾字被识别。

原生 START 仅在 processor 缺失或 room 未准备、且没有其他活动输入时标记 errorReason=engine_not_ready，同时输出 processor/room/busy 布尔值。客户端仅尚未收到 LISTENING、没有文字、没有请求完成、未重试过时，退役旧 UUID，解绑，等 1.5 秒后重绑重试一次。已有录音、其他输入占用、原生编号未生成均不自动重试；不强行构造引擎对象。

新增 BreenoSpeechTranscriptTest 的 5 项状态边界测试，涵盖 finish 保留 preview、禁止新段、旧/重复 FINAL、最终文字替换预览、空新段保留旧文字。连同 8 项身份测试共 13 项通过；Debug 构建成功。测试不覆盖真机 Binder、实际停止麦克风或 ColorOS 进程策略。

已保留数据覆盖安装，设备核对 versionCode=2026100419，重载小布并开启限定状态日志。APK SHA-256：`308B548130AE7496A8FCCD2D9B6DED1391C3A2EB87C325B3B53EEE7DA152755A`。交付副本仍是 `D:\下载\agent\eta\Eta-3.1.0-local-breeno-asr-debug.apk`。等待用户聊天输入框中手动完成与闲置一分钟后的测试；未发送模型消息。

长语音补充分析：ky.b 为持续 AudioRecord PCM16/16kHz/mono 读取，本地没有静音计时；但服务端停顿行为尚未验证。onLongAsrResult 的 boolean 来自 base.eof，不能视为整场完成；协议 action=final 才结束整场并清 listener。文本全量/分段语义仍需真机核对。原生 H 停录发送结束请求而保留 listener，cancel 则清 listener/连接；连接尚未 ready 时 H 会清未发送音频缓存，因此不能简单替换现有接口而忽略初始化和尾字时序。鉴权保留原生处理，不提取凭据。0419 未接入 LongAsrEngine。

### 0419 用户确认完成回填及闲置恢复；0420 输入栏调整

用户确认点击完成可停止并回填，约一两秒；闲置后可以恢复。日志 09:50:36.983→09:50:38.484、09:51:36.675→09:51:38.175、09:51:55.796→09:51:57.297 分别命中 1.5 秒 finish fallback（已识别 10/22/4 字）。09:51:13 与 09:53:50 的 startup unavailable 明确 processor=false、room=true、busy=false，客户端重试一次后收到后续 FINAL；与用户恢复反馈一致。不保证尚未返回的尾字，仍需长语音接口后续验证。

新增反馈：任务运行期间不能触发聊天语音。定位 AgentChatInputBar 将 SpeechDictationButton.enabled 绑定到 !isStreaming，且 LaunchedEffect(isStreaming,isEditingMessage) 会取消听写。现有文本输入和 supplement 通道支持运行中追加，语音本身只是草稿输入，没有必要沿用此 streaming 禁用。修订目标是允许运行中听写及回填，保持现有手动发送补充指令流程；任务开始/结束不会仅因 streaming 变化中断正在进行的听写。

0420 输入栏修订已完成：SpeechDictationButton 不再随 isStreaming 禁用；取消监听从 streaming/edit 两项变化缩小为编辑上下文变化。文字仍只回填草稿；发送补充指令前取消当前听写，避免发送后旧回调写入新草稿。Debug 编译成功，未为低影响 UI 条件修改新增镜像测试。APK SHA-256：`EAE5F2A919FFD8C976E607CBD5A5982FB9A7B51AD9915C467D4CEDACB7069E37`，versionCode=2026100420。只读任务数量查询仍有 inflight_count=1，因此暂未安装，等待用户当前任务结束；手机当前仍为 0419。待真机验证运行中语音→完成回填→手动补充指令。

### 0421：运行中语音、系统旋转及任务浮窗

0420 包未独立安装；用户确认任务结束后，追加两项浮窗行为修订，合并安装为 0421。

旋转静态证据来自手机当前系统 jar，详见 ignored ROTATION_EVIDENCE.md：系统 DumpCommand 连接/断开 UiAutomationConnection；断开调用 restoreRotationStateLocked，按初始 frozen 状态 freeze/thawRotation。Eta 的 RootShellDeviceController 曾在无障碍备用节点、滚动确认及节点重新校验路径调用 uiautomator dump。浮窗 LayoutParams、Xposed 和无障碍源码未发现直接写旋转开关的入口。未为复现主动执行系统 dump 或写手机旋转设置；这个路径是已确认有旋转副作用的代码路径，不能证明过去所有旋转变化都仅由它造成。

按用户“不修改系统旋转”的要求，移除系统 uiautomator 节点回退，不采用先改再恢复旋转值的方案。无障碍节点仍为主路径；未连接时可继续截图和 Root 坐标操作，但不产生伪节点 observation，wait_for_text 提示启用 Eta 无障碍，旧 UIAUTOMATOR 节点操作失败，坐标滚动不能虚报节点已确认位移。该手机 Eta 无障碍已配置。后续自行下发的任意 shell 命令不在本次设备观察修复范围内。

任务服务 collapsed 初值及每次新任务重置设为 false，默认显示窄单行气泡；气泡动作控件默认隐藏。已有 THINKING delta 的最新非空行作为预览，独立保存最多 240 字符，不改回答正文。工具、回答、新轮次/请求等事件清除旧推理，工具阶段显示已有工具标签，不显示参数。模型不返回推理文本时使用既有阶段提示。点击状态行收起为光球，点击光球恢复，独立箭头展开暂停/停止/补充控制。结果卡片仍保持既有结束行为。

本轮 Debug 构建通过；新增浮窗预览 5 项和 RootlessDeviceToolsTest 6 项，共 11 项通过，包括新增的无障碍缺失快速失败及旧节点拒绝边界。未运行完整套件，测试不证明真机浮窗位置、点击或整个任务期间旋转保持。

保留数据覆盖安装成功，设备核对 versionCode=2026100421；已重载小布。APK SHA-256：`857109F999E8F18DE8E6D3677526F3D3B86D2AC4B9EA23872DF6BB010136D9C6`。交付副本为工作区根的 Eta-3.1.0-local-breeno-asr-debug.apk。安装后只读旋转值为 0（关闭），不据此声称安装前后完全未变化。待用户主动应用操作任务验证旋转维持、单行浮窗点击收起及运行中语音回填。

### 0421 后续屏幕读取验证

2026-10-04 用户要求通过 ADB 触发 Eta 读取应用屏幕。在聊天中发送仅调用一次 observe_screen 的指令后切回桌面；返回 Eta 后界面显示调用 1 个工具，结果描述当前桌面，读取前后系统 accelerometer_rotation 均为 0。此验证只覆盖一次屏幕读取，尚未验证整个应用操作任务、浮窗点击交互或任务运行中听写。上游新增反馈为 #119（旋转副作用）和 #120（运行中语音输入）。

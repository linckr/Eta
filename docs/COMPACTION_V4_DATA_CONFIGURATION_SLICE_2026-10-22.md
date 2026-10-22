# v4 数据、配置与设置切片

## 已实现的行为

- 原 `request_byte_budget` 完整保留为 B，数据库升级不重写 null/0/正数。
- Provider 新增 `request_send_policy_json`，旧数据默认为 `{}`、版本 0；必须在设置中明确开启版本 4。
- 开启时以用户填写的已确认成功体积为 anchor；未填写则以独立固定的 512 KiB bootstrap，与 B（包括 48 MiB 采样覆盖）及模型窗口无关；G 固定为 256 KiB、L 为 786,432 B，再受已知 H 限制。bootstrap 不被标为已成功请求。
- 按冻结方案计算 G 与 L，保存 `observedBytes / sendLimitBytes / growthAllowanceBytes / hardLimitBytes / scopeDigest / evidence`。普通设置保存不重新计算已冻结边界。
- 2202新增bootstrap规则版本1。2201缺少该标记的bootstrap（包括可能继承48MiB B的值）失效，不继续沿用大L；设置必须明确重新开启保存。该失效分支沿用旧策略，不能声称仍受新L/H保护。真实观测值策略不受此bootstrap版本校验影响。
- 1,782,914 B 的例子得到 G=445,729 B、L=2,228,643 B。增长区间是本地发送政策，并非已实测成功的服务端容量。
- 生效范围是同一 Provider 的基础请求路由及其模型。地址、协议、鉴权、Provider 请求头/请求体覆盖、托管搜索变化都会使旧策略失效；修改后先保存，再重新开启。模型自身带额外请求头或请求体时，此 Provider 策略不生效。
- B 与 L/H 分别映射到 RuntimeConfig；JSON 和跨进程字段由统筹端接入。设置会提示 B 高于 L 的情况。

## 持久化失败元数据

- Room 版本 24→25 有显式迁移；新增策略列与 `compaction_failures`，不修改 Journal、旧摘要树或断点正文。
- 失败表只保存 key、来源 HMAC、策略/算法版本、错误码、时间、字节数、估算 token 数；不保存正文。
- `RoomCompactionFailureStore` 为运行时提供同步 load/save/clear 与 fingerprint。需在 IO 工作线程调用。
- fingerprint 使用安装私有随机 32 字节密钥及 HMAC-SHA256；密钥仅存应用私有 SharedPreferences。按 part 长度划界，正文每次最多编码 4,096 UTF-16 字符，避免合并整个历史；测试覆盖 5,000 条和约 419 万字符单条。
- 新包移除破坏性缺失迁移回退，迁移路径不明时拒绝打开而非清库。

## 需要注意

- **不能直接降级旧 APK。** 旧 APK 自己可能仍包含破坏性降级策略；新包无法约束旧二进制。需要回滚时应先规划与核验备份恢复。
- 本切片是 Provider 级配置，不是完整逐模型路由管理界面。独立模型覆盖会回到旧策略；不能宣称所有路由都已获验证。
- `observedBytes` 当前由用户依据证据录入；不会把“本次没有报错”自动记成观察成功，也不会在每次成功后抬高 L。
- 数据层不包含单次越界许可 UI、完整 route evidence 数据库或逐 Provider 自动观察统计。这些未实现能力不能被报告为已交付。
- 本切片不发送模型请求、不安装 APK、不操作手机、不更改网络。

## 回归入口

- `RequestSendPolicyTest`：冻结公式、bootstrap/H、路由失效、Entity 往返。
- `EtaDatabaseMigrationTest`：旧版本完整迁移链及单独 24→25、B/凭据/模型/历史保留、默认旧策略。
- `RoomCompactionFailureStoreTest`：关闭后重开仍可读取失败记录、私有 HMAC 稳定性与编辑失效、分段无歧义及大历史计时。
- `RuntimeConfigRepositoryTest`：策略到 ModelConfig/JSON、无匹配路由或模型覆盖时不生效。
- `ProviderComponentsTest`：明确开启、冻结边界、路由编辑失效、草稿状态恢复。

执行结果由统筹统一构建报告给出；此文档不把新增测试代码当成已经通过。

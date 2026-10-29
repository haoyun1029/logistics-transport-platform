# 五组件隔离联调记录（2026-10-24）

## 验证范围

本次已在真实 MySQL、Kafka、Flink、MongoDB、HDFS/Hadoop 上走通任务执行、道路模拟、匹配、同源评分和离线统计。道路、城市名称、货车依据及反馈均为明确标注的合成测试夹具，只有隔离测试库使用这些数据。它不证明任何真实道路已完成货车核验，也不替代三条不同 OD 与实际替代道路的最终验收。

运行脚本：`scripts/acceptance/verify_live_chain.py`。最终通过运行号：`20261024T134824Z_568c`。完整证据位于 `backend/target/live-chain/20261024T134824Z_568c/`，总报告为 `report.json`。

## 实际结果

| 环节 | 实际观察 | 留存证据 |
| --- | --- | --- |
| 账号和业务 | 初始化隔离管理员、创建调度员、司机注册审核、创建车辆、任务分配与司机接单 | 阶段报告及隔离 MySQL 审计 |
| Kafka | 后端道路模拟器发出 67 个带 SIMULATED、运行号、坐标系及序号的点 | `simulation.json`、独立主题 |
| Flink | 作业 `4bb1f4784e31324f610ff1e480ed7dac` 实际运行，有完成的 Checkpoint | `checkpoints.json` |
| MongoDB | 67 点实际落库，包含 63 个持续偏离事件；由后端读回完整运行 | `trajectory.json`、独立 MongoDB 库 |
| 匹配 | 实际匹配到合成替代路线 TEST_A → TEST_C → TEST_B，与计划道路不同 | `analysis.json` |
| 同源评分 | 计划 110 km/120 min，替代 90 km/90 min；同源油耗估算为 33 L/27 L，默认 40/30/30；本轮得分 0/100 | `optimization.json` |
| 推荐历史 | 保存“基于 1 次有效运输的初步推荐”；候选 v2 保持 CANDIDATE，原计划未覆盖 | 隔离 MySQL 比较快照 |
| 异常反馈 | 另一个任务在接单后立即反馈 100 km，完成记录保留但判 INVALID；复核不能变成有效样本 | `impossible-feedback.json` |
| 在线统计 | 2 个任务，仅合理反馈进入均值，均值为 100 km/30 L | `online-statistics.json` |
| Hadoop/HDFS | 真实 Map/Shuffle/Reduce 作业 `job_local651181544_0001` 完成；2 任务、1 合理反馈、1 排除样本、63 异常事件 | `offline-statistics.json`、`hadoop.stdout.log`、HDFS `_SUCCESS` |

技术联调的主样本使用了**只在隔离库注入的 90 分钟历史起点夹具**。程序没有真的行驶 90 分钟或 100 公里；也没有把加速 GPS 事件时间用作实际业务耗时。该注入步骤在报告中单独记录为 `SYNTHETIC_DURATION_FIXTURE`，不可当作现实运输效果证据。另一个异常样本使用实际 API 接单/完成时间，验证不合理耗时被排除。

第一次技术运行 `20261024T134136Z_da10` 揭示了原代码会接受“约 14 秒反馈 100 km”的缺陷。该次归档保留，但不作为当前反馈合理性规则的有效性证据；上述最终运行已经修正并重测此问题。

## 反馈合理性修正

统一规则版本为 `FEEDBACK_PLAUSIBILITY_V1`：里程、油耗和耗时须为有限正数，油耗不超过 2 L/km，按系统起止时间和反馈里程计算的平均速度不超过 150 km/h。150 是保守的数据质量上限，不是货车合法限速；道路合法性和 Flink 超速阈值仍分别判断。

完成任务会保存不合理反馈并标为 INVALID；轨迹复核也重新检查。优化评分再次检查历史匹配快照中的反馈，旧 VALID 标记不能绕过新规则。MySQL 在线均值与 Hadoop mapper 使用相同上限；排除均值不删除任务，不抹除异常事件。缺失均值继续显示暂无数据。

新增的 Hadoop 运行清单记录反馈规则版本及 mapper/reducer SHA-256，原始输入和输出仍按独立运行号留存。旧离线结果不被修改，新规则通过新作业生效。

## 隔离与恢复

- MySQL/MongoDB：`logistics_it_20261024t134824z_568c`。
- Kafka：`logistics.gps.it.20261024t134824z_568c`。
- HDFS：`/logistics/transport-statistics/integration/20261024T134824Z_568c/`。它有独立的 latest 指针。
- 临时后端为 8082，测试结束后已关闭；主前后端继续使用 8080/5173。
- 原 Flink 作业先写 Savepoint `file:/var/lib/logistics/flink-savepoints/savepoint-ab84c5-0ae6c242efaa`，测试作业取消后，从该状态恢复为 `750a5b861ee487e0b458e6c72154286c`，实际 RUNNING，restored=1。

保留隔离库、主题、HDFS 输出和 Savepoint 便于复查，未删除主库数据、未重置 Kafka 偏移、未格式化 HDFS。开发库仍为 4 条路线草稿/候选和 3 条任务；旧联调 MongoDB 仍为 64 点/48 异常，没有混入本次合成数据。

## 复现与失败处理

已有服务和 SSH 身份文件必须可用。Windows 已安装 Maven，Python 仅使用标准库。脚本读取本次进程环境变量 `LOGISTICS_MYSQL_ROOT_PASSWORD`，不在源码保存密码；调用时传入 `--identity-file` 和 `--maven` 的本机绝对路径。`--port` 默认 8082，禁止使用 8080；每次生成独立数据命名空间，不复用旧测试库。

该脚本会短暂停止当前唯一物流 Flink 作业，因此先确认没有用户正在发布 GPS。正常与断言失败路径均尝试恢复原 Savepoint；整体报告只有在业务检查和恢复均成功后才为 PASSED。恢复失败会记录 `RESTORE_OR_CLEANUP_FAILED`，需先查看 `/jobs/overview` 和报告里的 Savepoint/已提交作业号，不能盲目再次提交，避免重复消费。原始恢复请求保存在对应目录的 `restore-original-request.json`。

本轮后端 82 项测试、前端 54 项检查/生产构建、Python 离线统计 5 项测试通过；Flink 源码未修改，没有重复打包其未改模块。完整真实道路货车核验、实际道路固定案例、论文和最终答辩材料仍待完成。

### 证据清单

联调结束后可生成独立清单：

```powershell
python scripts/acceptance/build_evidence_manifest.py backend/target/live-chain/20261024T134824Z_568c
```

工具只记录相对路径、文件大小和 SHA-256，并写入 Python/平台等非敏感运行信息；会主动排除 `.env`、私钥和证书后缀。第三方可在干净副本上重新计算哈希，确认日志、输入、输出和阶段报告没有被替换。

### 第三方清单复核

收到证据目录后，复核者无需运行项目服务即可执行：

```powershell
python scripts/acceptance/verify_evidence_manifest.py backend/target/live-chain/20261024T134824Z_568c
```

`valid=true` 表示每个已记录文件的大小和 SHA-256 均一致，且没有未登记的普通文件；`missing`、`changed` 或 `extra` 非空时应停止引用该证据包。

### 评分敏感性分析

同 OD 评分快照可用以下脚本复算五组权重情景（默认 40/30/30、等权、时间/里程/油耗偏重）：

```powershell
python scripts/analysis/score_sensitivity.py backend/target/live-chain/20261024T134824Z_568c/optimization.json
```

脚本只复用快照中已保存的归一化分项分数，只改变权重，不跨 OD 合并数据；输出 `score-sensitivity.json` 并保留有效样本数、来源和“探索性、不证明全局最优”的限制。

### 真实路线证据校验器

`validate_route_evidence.py` 只允许字段齐全的材料进入人工复核阶段。它要求每个相邻路段保存几何、地图请求与原始响应、货车依据、核验时间和复核人；重复 OD、缺字段或不连续序号都会失败。`READY_FOR_HUMAN_REVIEW` 仍不等于已核验通过。

证据校验器的自动测试覆盖空白模板、完整待复核材料、重复 OD、路段序号断裂和伪造 `VERIFIED` 状态等 6 个场景。

本轮新增的配对 Bootstrap 示例使用 3 条同 OD 合成记录，成功生成差值和 95% 区间；多 OD 混入测试会失败。该示例不计入正式运输样本。

证据工具测试现覆盖 9 个场景：敏感文件排除、干净清单复核、文件篡改、新增文件，以及路线证据的字段、OD、序号和状态门槛。

一键复核脚本已运行：验收工具 9 项通过，证据清单 22 个文件通过；未提供真实路线材料，因此脚本按设计给出“最终道路验收仍待完成”的警告。

# 最终验收清单

本清单用于答辩前按实际证据逐项复核。勾选只代表有可追溯证据，不代表“页面能打开”即可通过。

## 路线与货车通行

- [ ] 三条基础路线的起点、终点均不同，并按地级市节点和相邻道路段记录。
- [ ] 每个路段有地图请求参数、原始返回、道路名称/类型、里程、耗时、坐标系、查询时间。
- [ ] 每个路段有可靠的货车通行依据：来源链接或文件、适用车型/时段/限制、核验结论和核验时间。
- [ ] 任一段缺几何、几何不连续或缺货车依据时保持 `PENDING`，不能发布、分配、模拟或优化。
- [ ] 失败地图请求也有请求时间、OD、状态码、失败原因和排除优化状态。

## 业务流程与三端

- [ ] 管理员、调度员、司机分别登录到独立端，侧边栏只展示当前角色可用功能。
- [ ] 司机注册、管理员审核、车辆维护、任务创建/分配/接单/完成均由后端权限和状态校验。
- [ ] 接单和送达使用二次确认；开始/结束时间来自系统时间戳。
- [ ] 司机反馈异常时保留历史，标记 `INVALID` 并记录原因；不进入有效样本、校准或均值。

## 轨迹与优化

- [ ] 模拟 GPS 沿已核验道路折线产生，事件明确标记 `SIMULATED`，运行号和序号连续。
- [ ] Kafka → Flink → MongoDB 有作业状态、Mongo 实际读回、处理日志和 Checkpoint 证据。
- [ ] 匹配仅使用同 OD 的计划与实测道路；城市示意线不能当作道路几何。
- [ ] 候选来自已观测且已核验的连续路段；未实际行驶的油耗明确标为估算。
- [ ] 评分保存同源指标、40/30/30 权重、Min-Max 边界、数据来源、版本和选择理由。
- [ ] 只有有效样本可生成“基于 N 次有效运输的初步推荐”，不能宣称全局最优。

## HDFS 与演示证据

- [ ] GPS/异常或运输统计写入真实 HDFS，输入输出路径、SHA-256、完成标记和运行号可复核。
- [ ] Hadoop 统计确实经过 Map、Shuffle、Reduce，管理端显示同一运行的 jobId 和结果。
- [ ] 按 OD 展示任务数、有效反馈数、平均实际耗时、平均里程、平均油耗和异常次数；无有效样本显示“暂无数据”。
- [ ] 演示脚本记录启动顺序、健康检查、固定数据、日志位置和清理/恢复步骤。

## 当前证据边界

五组件隔离联调 `20261024T134824Z_568c` 已通过，见 `LIVE_CHAIN_ACCEPTANCE.md`；其中道路和 90 分钟起点是明确标记的合成夹具。它证明技术链路可运行，不代替三条真实货车路线逐段核验。当前正式 Hadoop 结果和运行状态见 `PROJECT_STATUS.md`、`NEXT_SESSION.md`。

证据包可用 `python scripts/acceptance/build_evidence_manifest.py <run-dir>` 生成 `evidence-manifest.json`，再由独立复核者在干净副本上重算 SHA-256。

第三方复核使用：

```powershell
python scripts/acceptance/verify_evidence_manifest.py <evidence-dir>
```

返回 `valid=true` 且 `missing`、`changed`、`extra` 均为空，才表示证据目录与清单一致；任一文件缺失、内容变化或新增都会返回非零退出码。

## 评分敏感性分析

可对同一 OD 的优化快照运行 `python scripts/analysis/score_sensitivity.py <optimization.json>`，检查默认权重、等权和单项偏重时的排名是否稳定。结果只能说明该快照下的敏感性，不能替代更多真实运输样本。

### 真实路线证据入库前检查

使用 `scripts/acceptance/route-evidence-template.json` 收集原始材料。填好后运行：

```powershell
python scripts/acceptance/validate_route_evidence.py scripts/acceptance/route-evidence-template.json
```

校验器只检查结构和可追溯字段，不替代人工判断来源是否真的证明货车通行；缺项或重复 OD 会返回非零退出码并保持 `PENDING`。

### 配对差值与 Bootstrap

方案要求的统计不确定性分析已提供 `scripts/analysis/bootstrap_paired_metrics.py`。它按同一 OD 计算计划/候选配对差值、百分比变化和可重复的 Bootstrap 95% 区间，并拒绝混合多个 OD；示例输入 `paired-bootstrap-fixture.json` 仅用于已知答案测试。

证据工具测试可运行：`python -m unittest discover -s scripts/acceptance -p 'test_*.py' -v`。

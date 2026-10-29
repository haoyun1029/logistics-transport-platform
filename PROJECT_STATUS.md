# 项目实现与验收状态

更新时间：2026-10-24。本文记录实现与证据，需求基线仍为 `PROJECT_PLAN.md`、`AGENTS.md`。旧 `.doc` 未修改。

## 当前结论

三端已经使用正式 MySQL 任务、用户、车辆、路线和配置；完成了道路模拟、路线后处理、同源评分、推荐历史及真实 Hadoop 离线统计的实现。不能宣布整套毕业设计最终验收完成：高德底图已实图验证，普通驾车资料可查询并落库；货车物流接口未获单独授权，默认使用普通道路资料加公开货车依据；三条 OD 缺少真实逐段货车通行材料/道路几何，因而全 VM 的已核验替代路线演示尚未完成。

没有将隔离测试中的道路、核验材料或推荐插入开发 MySQL。当前路线仍待核验，不能为通过演示放宽分配、匹配或优化门槛。

## 功能清单

| 范围 | 已实现 | 验证与边界 |
| --- | --- | --- |
| 三端与登录 | 按角色跳转、独立侧边栏、子页面、后端权限、标签页会话 | 实际浏览器与 API 验证；不是同页角色切换 |
| 司机审核 | 自主注册、管理员通过/拒绝、审核记录与审计 | 已验证登录与审批状态；保留已有账号和审核记录 |
| 用户管理 | 超管创建职员、角色与启停；管理员/调度员司机资料；本人资料 | 权限测试通过；只允许超管删除，业务占用受保护 |
| 任务主流程 | 创建、分配、接单、完成反馈、取消、历史 | MySQL事务、司机/车辆锁、重复分配和状态门槛测试；开始/结束系统计时 |
| 车辆与维护 | 档案、占用状态、维修、维护内容/日期、下次保养 | 首页分类；维修/分配互斥；司机限所分配车辆 |
| 路线与版本 | 三个不同OD候选、路段编辑、核验记录、发布和新版本 | 已发布不可覆盖；编辑撤销核验；三条开发候选均待核验 |
| 地图 | 共用地图、道路/城市示意区分、计划/匹配/候选图层、轨迹回放与缺口 | 高德JS API 2.0实图已验证；未核验路线仍标城市示意，非真实已核验道路 |
| 正式GPS模拟 | 沿已核验道路折线、同OD替代走法、原始WGS84点、模拟运行号 | 隔离完整流程测试通过；开发库未核验道路不能执行 |
| 匹配复核 | 同OD道路几何、方向/端点/覆盖率/缺口/歧义校验、序列差异、不可变分析 | 隔离测试允许少量缺口并拒绝歧义；真实已核验路线待验 |
| 综合评分 | 同源预计耗时/里程/校准油耗、40/30/30、同值50、边界及权重快照 | 保存实际反馈和估算来源，不能混用评分；配置带版本 |
| 贪心候选 | 已观测且核验的道路池、连续性、候选新版本、初步推荐及失败历史 | 隔离完整流程生成1样本初步推荐；开发库当前保存暂无推荐原因 |
| Kafka/Flink/Mongo | 原始事件校验、超速、持续偏离状态、Mongo upsert、处理日志 | 新作业实际RUNNING且旧模拟样例落库；已核验道路偏离VM演示待验 |
| HDFS归档 | GPS/异常快照、读回哈希、完成标记 | 已真实写入/校验，保留历史目录 |
| Hadoop离线统计 | 逐任务JSONL→Map/Shuffle/Reduce→HDFS结果→管理页面 | 已实际运行Hadoop作业，页面显示同轮图表/明细与jobId，不以SQL聚合代替 |

## 测试与实际运行证据

本轮已运行：

- 后端 `mvn test`：61项测试，0失败/0错误/0跳过。
- 前端 `npm test`：50项检查；`npm run build` 的类型检查与生产构建通过。
- Flink模块 `mvn package`：10项测试通过并打包，Java8目标；包含原始校验、作业图、持续偏离状态/重置。
- `python -m unittest discover -s scripts/hadoop -p 'test_*.py' -v`：4项通过，包括OD隔离、缺失反馈、异常燃油反馈排除、损坏输入失败。
- 隔离H2业务测试：任务完整生命周期、并发/占用、事务回滚、司机归属、未核验阻断、版本不可变、用户权限。
- 隔离H2分析完整流程：创建任务→分配/接单→道路模拟→模拟Kafka/Mongo依赖→完成反馈→实际道路匹配→有效样本→贪心候选→同源归一化→1次有效运输初步推荐→持久化快照。这里使用人工测试几何和测试核验材料，不代表真实路线通过核验。

实际开发环境还核对：

- 后端已重启加载最新代码；重启后仍有3条MySQL任务，2条验收创建任务已取消、原已完成任务和反馈保留。正式数据没有因重启重置。
- 普通管理员 `demo_admin` 可登录且角色ADMIN；原admin、demo_dispatcher、demo_driver仍可使用原密码。实际密码不写本文件。
- 调度员访问管理统计403，司机访问策略403，司机读取不属于自己的任务轨迹403。原已完成任务属于该司机，读取其自身历史轨迹允许，不能误当越权。
- 原种子DEMO-2026-001已复核为INVALID：路线尚未核验，不把旧VALID标记当匹配证据；原反馈与历史仍保留。
- 旧内存联调DEMO-2026-002在本次后端重启后恢复初始待接状态；它与MySQL正式任务分开，不能计入正式任务数。

### 实际 Flink / MongoDB

本次重启后从持久化Savepoint `file:/var/lib/logistics/flink-savepoints/savepoint-b652f7-f4ffc2048174` 恢复；当前作业 `ff51d1d4e68c010f7ac1f06731cf1d2b` 为RUNNING，REST latest.restored指向该Savepoint。20:24左右检查为restored=1、151次完成、1次失败Checkpoint。恢复已实际验证；计数是快照，不承诺跨集群恢复。旧作业b652f7已取消。

此前发送16个旧联调模拟点，Kafka→新版Flink→MongoDB已实际通过。累计MongoDB示例任务64个点、48个超速事件、0条拒收；都是模拟数据和多次运行的累计点，不是64次运输。旧城市插值事件没有已核验道路上下文，因此不做持续偏离/匹配有效结论。持续偏离规则在本地测试验证，真实核验道路的VM规则演示仍待完成。

### 实际 Hadoop MapReduce / HDFS

最新完成运行：

- runId：`20261024T104947Z-74232`
- jobId：`job_local1359457162_0001`
- 输入SHA-256：`fb1a642418abb032cc79c1f0fcc150810c69ed8ceeb0f4f3593fe77742c40897`
- 输入：`/logistics/transport-statistics/mapreduce/run=20261024T104947Z-74232/input/tasks.jsonl`
- 结果：同运行目录 `/output/part-00000`，存在 `/output/_SUCCESS`。
- manifest：同运行目录 `/manifest.json`；最新指针 `/logistics/transport-statistics/latest.json`。
- Windows原始输入/脚本留存：`backend/target/od-jobs/20261024-184946-171`。

执行方式为VM内Hadoop本地运行器、真实HDFS输入输出，经过map/shuffle/reduce，没有启动YARN，也没有预先SQL聚合。

管理页面已实际看到：北京→上海2任务、广州→武汉1任务；北京→上海仅1条已完成且反馈数值合理的运输用于均值：1090分钟、1194公里、405升。该记录另有1条排除优化标记。广州→武汉没有完成反馈，均值为空；不是0分钟/0公里/0油耗。异常次数都为0，因为旧联调任务DEMO-2026-002不属于这3条正式任务，不能把它的48个异常点加到正式统计。

截图：`frontend/artifacts/hadoop-statistics.jpg`。图表和明细来自同轮Hadoop结果。首次作业曾因PowerShell字节内容导出失败，随后改用OutFile保存原始JSONL再验证；失败运行未发布为最新完成结果。

历史GPS归档仍保留：`/logistics/gps/export_date=2026-10-23/task_id=DEMO-2026-002/run-20261023-170228-vtV1Y7`，读回校验与_SUCCESS通过。本轮OD作业独立于该GPS归档。

## 尚未通过的最终验收

1. 高德：JS实图和普通驾车查询已通过，查询资料#1为北京→廊坊56.089km，约71.4分钟，来源AMAP_DRIVING_ROAD_GEOMETRY_V3，数据库保持PENDING。不同步骤的原始折线存在约7–18米接缝，未自动拼接，几何留空；原始响应保留。货车v4实测10012/INSUFFICIENT_PRIVILEGES，官方说明为单独授权的收费物流接口，可申请试用；用户希望不付费，默认走普通道路资料+可靠公开货车依据，不自动调用收费接口。成功资料/失败记录页面均可复核，货车依据和完整几何仍须补齐。
2. 真实路线材料：三条不同OD逐段道路几何、地图返回记录、目标货车限制依据/核验时间缺失。资料可以来源于有权使用的货车地图服务和可靠道路/交管公开资料。未拿到依据的路段继续待核验，不能用普通驾车路线或测试数据代替。
3. 固定全链路案例：材料齐备后，逐段核验并发布计划及同OD替代路线，调度创建/分配、司机接单、沿替代道路模拟→Kafka→Flink→MongoDB、完成反馈、复核匹配→贪心/评分/候选历史，再运行Hadoop并在三端地图验收。此前隔离测试与旧模拟链路验证不能替代此项。
4. 答辩交付：最终固定演示脚本与截图需基于上述真实核验案例补齐；论文、性能/负载和跨集群重启恢复等未在本轮验收，不虚报完成。

## 环境与非破坏性变更

VM为192.168.146.128，CentOS7，MySQL5.7.28、Flink1.17.2/Java8、Kafka、MongoDB6.0.26、Hadoop3.1.3。MySQL/Mongo、ZooKeeper/Kafka、Flink、NameNode/DataNode实际在运行。VM约4GB，上一阶段快照可用内存约282MB、swap已用约530MB（本次未重测），采用低内存离线执行方式。

两份20261024数据库迁移已应用，保留现有账号/表/任务/历史；新增普通管理员demo_admin。三条候选保持未核验，未将测试核验材料写入开发库，未清空表/格式化HDFS/重复安装组件。

后端当前运行日志见本文件末尾最新阶段记录。VM Hadoop日志：`/tmp/od-mapreduce-20261024T104947Z-74232.log`。启动与复现步骤见根README和streaming/README。
## 本次地图资料阶段（2026-10-24 晚间）

已修正官方货车v4协议、完整车牌拆分、车辆参数校验、超时和错误码留存；增加普通驾车v3资料模式及成功历史查询。来源、原始返回与核验状态分离，几何缺失/不连续不会造直线补齐，也不会写入已核验状态。

浏览器在路线页面实际查询成功，并刷新读取保存的资料；截图 `frontend/artifacts/amap-road-materials.png`。本次运行61个后端测试、50项前端检查、类型检查和构建。Flink10项/Python4项是此前通过结果，本次未重复运行这两个未改模块。

## 免费道路资料与几何复核阶段（2026-10-24）

增加 OSRM / OpenStreetMap 普通汽车资料来源，无需 Key；保留高德为默认模式。OSRM 使用 WGS84，服务完整 GeoJSON 作为正式待核验几何，高德底图显示时单独转换。请求限制至少间隔1.1秒、有效 User-Agent、最多3个途经点及0–2备选序号；不批量请求，署名与修正地图链接已加入资料预览和业务地图。不把免费公共服务承诺为生产 SLA，也不当作货车规划。

增加数据库历史详情端点、按源重新解析、逐步地图预览、几何接缝距离/端点提示、单步定位及历史填表。高德原始资料#1显示3处接缝，未生成跨缺口连线，完整几何仍为空。历史预览不调用高德/OSRM，不新增查询记录。填表指标精度为两位小数，修复原始多位小数违反HTML表单step而不能提交的问题；原响应不改写。

实际 OSRM 查询#2：湖州→杭州 G25道路段，54.2758km、36.1933min、387点、服务返回2条路线，选第0条；来源OSRM_OSM_DRIVING_V5，PENDING。通过浏览器复用、填表并保存为DB-4草稿（54.28km/36.19min），逐段核验仍PENDING，原3条候选和3条任务保留。这是高速路面端点之间的研究草稿，不是已核验物流出入口/装卸端点。未发出正式道路模拟、未产生有效推荐。

官方浙交〔2022〕1号PDF已下载渲染核对：杭宁相关项是客车优惠且已到期，通用货车ETC条款不足以覆盖本段具体限制，均未拿来批准道路。详细链接、SHA256及排除理由见ROAD_MATERIAL_REVIEW.md。现行、具体目标货车通行依据仍未取得，三OD及替代路线全链路验收继续未完成。

本阶段验证：后端全量74项测试0失败/错误/跳过；前端54项检查、类型检查和生产构建通过。Flink/离线统计模块未修改，未重复构建。实际检查Flink原ff51d1d4e68c010f7ac1f06731cf1d2b仍RUNNING，没有重复提交作业。后端最终日志为backend/target/open-road-final.stdout.log及stderr。

## 连接状态检查修正与最终复查（2026-10-24）

MongoDB健康检查改为对后端实际轨迹连接执行MongoDB `ping`，不再在公网27017不可访问时仅凭配置返回“VM内可用”。当前实际检查为本机27018 SSH隧道连接成功；选择、连接、读取超时均限制为1.2秒，返回端点仅有host:port，不返回驱动错误或连接字符串中的凭据。前端统一显示已连接/不可用。新增成功ping、断连不虚报及不成功响应的测试。

最新后端全量77项测试，0失败/错误/跳过；前端54项检查、类型检查和生产构建通过。实际重启后，Mongo摘要读取64点48异常，HDFS离线API的`dataStatus=HADOOP_RESULT_AVAILABLE`。Kafka/MySQL/HDFS为TCP连接成功，Flink为REST可访问；这不替代各组件职责和完整链路验收。

实际复查结果：`backend/target/road-material-health-verification.json`；测试日志：`backend/target/road-material-health-tests.log`；当前后端日志：`backend/target/road-material-health.stdout.log`及同名前缀stderr。免费道路地图截图已保存为`frontend/artifacts/osrm-road-materials.png`，可从管理员核验记录预览资料#2复现。正式货车材料及最终全业务案例仍未通过。

## 当前复核补充（2026-10-24）

本轮以 `LIVE_CHAIN_ACCEPTANCE.md` 记录的隔离运行 `20261024T134824Z_568c` 为当前五组件联调证据：真实 MySQL、Kafka、Flink、MongoDB、HDFS/Hadoop 均实际参与，67 个模拟 GPS 点经 Kafka→Flink→MongoDB，Hadoop Map/Shuffle/Reduce 产出离线统计，并在结束时恢复原 Flink 作业 `750a5b861ee487e0b458e6c72154286c` 为 RUNNING。隔离库、主题和 HDFS 输出未混入开发库。

反馈合理性已统一为 `FEEDBACK_PLAUSIBILITY_V1`：按系统起止时间计算的平均速度上限为 150 km/h；不合理反馈保留历史但标为 INVALID，不进入在线均值、匹配校准或优化评分。后端 82 项、前端 54 项、Hadoop Python 5 项检查及生产构建均已通过。隔离联调中的 90 分钟历史起点是明确标注的 `SYNTHETIC_DURATION_FIXTURE`，不能当作真实运输效果。

正式 Hadoop 最新运行是 `20261024T135016Z-36232` / `job_local1349876180_0001`，API 返回 `dataStatus=HADOOP_RESULT_AVAILABLE`；管理统计页展示北京→上海和广州→武汉的同轮结果。最新 Flink、Hadoop 和运行状态详见 `NEXT_SESSION.md`，旧历史记录保留用于追溯。

正式业务仍有明确门槛：三条不同 OD 的每个路段必须补齐实际几何、地图请求与结果、里程耗时、可靠货车通行依据及核验时间，全部通过后才可发布、模拟和优化。OSRM/普通驾车资料只能提供道路研究草稿，不能证明货车通行；当前真实路线和最终答辩案例未宣称完成。

## 2026-10-25 第二版演示链路复核与真实运行

本轮按 DEMO_MODE 方案完成湖州—杭州两条分叉路线的真实演示。旧种子数据保留；新增路线仍为 `PENDING_VERIFICATION`，普通 OSRM 道路资料不作为货车核验依据。

- 计划路线 DB-7：湖州→安吉→杭州，2 段，134.78 km / 104.13 min。
- 替代路线 DB-8：湖州→德清→杭州，2 段，63.57 km / 52.41 min。
- 回归测试 `DemoSeedRouteGeometryTest` 从种子 SQL 读取几何，按 1.5 km 采样、0.15 km 匹配阈值断言计划轨迹唯一匹配 DB-7、替代轨迹唯一匹配 DB-8。
- 后端五个核心类完成 Google Java Format；`ALGORITHM_EXPLANATION.md` 已补充投影/覆盖率/唯一匹配、贪心候选和 40/30/30 Min-Max 评分说明。
- 后端 Maven 全量测试：86 项通过；前端生产构建 `npm run build` 通过。

### 固定演示运行

- 任务号：`TASK-7AC46BB5C5BE4FAFA30B`，任务 ID：6，计划路线 DB-7。
- 运行号：`3bbf1f9b-1f9d-41d9-a9c0-2e802f76de40`，沿替代路线 DB-8 发布 44 个点，经 Kafka→Flink→MongoDB。
- 审计时间：创建 21:31:32、分配 21:32:48、接单 21:40:20、模拟 21:40:38、送达 22:12:34、匹配复核 22:12:47、优化评估 22:13:06（Asia/Shanghai）。
- 系统接单到送达约 32.23 分钟；反馈 63.57 km、20 L，平均速度约 118.1 km/h，低于 150 km/h 上限。
- 匹配：`VALID`，覆盖率 1.0、缺口 0、唯一匹配 DB-8；城市差异为安吉→德清，样本保留为演示样本。
- 优化：评估 ID 2，`DEMO_ONLY`；候选 DB-9（由已观测的德清路段生成），同源估算 63.57 km / 52.41 min / 20.00 L，候选综合分 100.00，计划路线综合分 0.00；`formalOptimizationEligible=false`，不能发布正式推荐。
- 后端业务审计表已核对上述 `TASK_CREATED`、`TASK_ASSIGNED`、`TASK_ACCEPTED`、`ROAD_GPS_SIMULATION`、`TASK_COMPLETED`、`TASK_ROUTE_ANALYSED`、`OPTIMIZATION_EVALUATED` 记录；Spring stdout 日志主要记录启动信息，业务证据以 MySQL audit_logs 与页面结果为准。

### 本轮 Hadoop 统计

- runId：`20261025T142430Z-112036`
- jobId：`job_local988282724_0001`
- 输入 SHA-256：`54085918e4e9d617a0e052464e9148880f493597b5fbc86b72579465c519f402`
- 输入：`/logistics/transport-statistics/mapreduce/run=20261025T142430Z-112036/input/tasks.jsonl`
- 输出：同运行目录 `output/part-00000`，`_SUCCESS` 存在；`latest.json` 已更新。
- 管理员统计页已刷新显示同一轮 Hadoop/HDFS 结果：北京→上海 3 次、广州→武汉 1 次、湖州→杭州 2 次；湖州→杭州有效反馈 1 条，63.57 km、20 L，异常 0。

### 演示截图

截图保存在 `frontend/artifacts/`，均为 2026-10-25 生成：

- `demo-task-created.png`、`demo-assigned.png`、`demo-driver-accepted.png`、`demo-simulation.png`：调度任务页及 DEMO_MODE 任务/时间戳证据。
- `demo-monitoring.png`、`demo-dispatch-monitor.png`：运输监控页，显示计划线、44 个 MongoDB 模拟点和回放。
- `demo-feedback.png`：管理员运输记录页，显示送达时间、63.57 km、20 L、32.2 min 和 VALID。
- `demo-matching.png`、`demo-optimization.png`：路径优化页，显示唯一匹配、城市差异、DEMO_ONLY、计划 0.00 与候选 100.00。
- `demo-hadoop-statistics.png`、`demo-hadoop-statistics-full.png`、`demo-hadoop.png`：管理员统计页，显示 runId/jobId 和 HDFS 明细。

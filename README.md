# 基于多源证据可追溯与不确定性评估的物流运输任务调度、轨迹分析及同 OD 路径优化系统

基于 Vue 3 + TypeScript + Vite、Java 21 + Spring Boot，数据组件部署在 VMware CentOS 7。需求以 `PROJECT_PLAN.md` 为准，当前验证结果见 `PROJECT_STATUS.md`；答辩前逐项复核见 `FINAL_ACCEPTANCE_CHECKLIST.md`。旧 `.doc` 未修改。

## 目录与职责

- `frontend/`：统一登录入口、按角色跳转的三个业务端、侧边栏功能页面、共用地图和图表。
- `backend/`：认证与权限、MySQL 业务、道路核验、轨迹后处理、同 OD 路线优化、HDFS 统计读取。
- `streaming/`：Flink 1.17.2 / Java 8 作业，Kafka 原始 GPS 校验、超速和持续偏离规则、MongoDB sink。
- `scripts/hadoop/`：Hadoop Streaming 的 OD mapper/reducer 和测试。
- `scripts/vm/`：已有 VM 的 HDFS 启动、GPS 归档、Flink 提交、离线作业脚本。

## 本地运行与数据库升级

Windows 需要 Java 21、Maven 3.9+、Node.js 20+。真实配置放在根目录 `.env`，不要提交密码或 Key。`.env.example` 是模板。

新数据库先创建 `logistics_system` 和专用 `logistics_app` 账号，授权该库所需的 SELECT、INSERT、UPDATE、DELETE、CREATE、ALTER、INDEX、REFERENCES，再导入 `backend/db/schema.sql`。MySQL 5.7 的可空时间字段使用 `TIMESTAMP(3) NULL DEFAULT NULL`，不需要关闭严格模式。

现有数据库升级需检查并依次执行：

1. `backend/db/migrations/20261024-business-tasks.sql`：任务支持未分配状态，补充车辆维护记录。
2. `backend/db/migrations/20261024-route-analysis.sql`：系统配置、实际路线分析、优化评估快照。
3. `backend/db/migrations/20261025-demo-mode.sql`：为任务增加显式演示标记。
4. `backend/db/migrations/20261026-vehicle-maintenance-reminders.sql`：将下次保养日期存入车辆档案，供司机端显示只读提醒。
5. `backend/db/migrations/20261027-vehicle-driver-profile.sql`：为车辆增加能源、总质量、尺寸、轴数、ETC 和绑定司机字段；新车辆由管理员录入并绑定审核通过的司机。
6. `backend/db/migrations/20261026-task-details.sql`：为运输任务增加货物、重量、计划距离、预计耗时、送达时限和任务详情。
7. `backend/db/migrations/20261026-task-details-backfill.sql`：仅为既有 `DEMO_MODE` 固定演示任务补齐明确标注的展示资料。
8. `backend/db/migrations/20261026-task-price.sql`：为运输任务增加可空的每公里单价；旧任务不自动补金额，新任务由调度员填写。
9. `backend/db/migrations/20261028-password-recovery.sql`：新增临时密码强制修改标识；旧账号默认不需修改，不改变已有密码和审核状态。

当前开发 VM 的迁移执行状态请先检查；启用演示模式前须执行第三份迁移，使用车辆保养提醒前须执行第四份迁移，使用新的车辆档案和绑定司机字段前须执行第五份迁移。`CREATE TABLE IF NOT EXISTS` 不会修改已有字段，所以只重新导入 schema 不能替代升级；迁移保留已有任务、账号和反馈。

在项目根目录的两个 PowerShell 窗口分别运行：

```powershell
.\scripts\start-local.ps1
```

```powershell
.\scripts\start-local.ps1 -Frontend
```

或分别在 `backend` 执行 `mvn spring-boot:run`，在 `frontend` 执行 `npm install`、`npm run dev`。打开 `http://localhost:5173/login`，不要双击 HTML 文件。Vite 将 `/api` 代理到 `http://localhost:8080`；健康检查为 `/api/health`。8080 已占用时先确认现有进程，避免重复启动。

首次初始化使用 `scripts/initialize-local.ps1 -Mode PrepareEnv`，重启后端后执行 `-Mode Bootstrap`。已有管理员无需再次初始化。司机从 `/register` 自行注册，管理员审核后才能登录。超级管理员可在用户管理中创建普通管理员和调度员。密码使用哈希，会话由后端校验，注销/停用账号会撤销会话。

司机忘记密码时，在登录页点击“忘记密码”查看联系说明。超级管理员核实身份后，在用户管理的司机列表点击“重置密码”，生成仅本次响应显示的随机临时密码；原有会话全部撤销。司机用临时密码登录后必须先修改密码，后端同时限制该会话只能读取本人登录信息、改密或退出，不能执行运输业务。改密后全部会话撤销，使用新密码重新登录。三个业务端的个人资料页均可验证原密码后修改密码。重置不改变审核和停用状态；未通过审核、已删除的账号不能重置。用户目录仅展示已审核司机（含停用），拒绝原因与超级管理员删除入口位于司机审核记录。

MongoDB 只监听 VM `127.0.0.1:27017`。Windows 后端通过 `scripts/start-mongo-tunnel.ps1` 建立本机 27018 隧道，`.env` 的 `MONGO_URI` 指向该隧道。Flink 与 MongoDB 在同一 VM，直接访问 27017。检查脚本 `scripts/check-vm-services.ps1` 仅探测端口，不能代替实际落库或 Hadoop 作业验收。

## 三个业务端

统一登录后根据后端角色进入对应端。每个端有独立导航和页面；会话按标签页保存，方便三端演示。后端对角色和司机任务归属做独立校验。

| 业务端 | 页面与能力 |
| --- | --- |
| 管理员 `/admin` | 首页、路线核验与版本、车辆档案、维护、运输记录、司机审核、用户与角色、路径优化、OD 离线统计、轨迹证据、组件状态、评分与异常配置、个人资料 |
| 调度员 `/dispatcher` | 首页车辆分类、司机资料、运输任务创建/分配/取消、实时监控、个人资料 |
| 司机 `/driver` | 本人任务、二次确认接单/送达、实际里程和油耗反馈、任务内地图、历史记录、车辆维护、个人资料 |

只有超级管理员可删除用户。普通管理员不能管理其他职员的角色；调度员不能维护基础路线、发布推荐或读取管理统计；司机不能读取他人任务详情或轨迹。用户停用/删除、车辆维修和重复分配会检查当前任务占用。

正式任务保存在 MySQL，状态为 `PENDING_ASSIGNMENT → PENDING_ACCEPTANCE → IN_TRANSIT → COMPLETED`，或进入 `CANCELLED`。任务、分配、司机/车辆占用及审计在事务内处理；开始和完成时间由服务器生成。

可以为待核验路线保存任务草稿，但不能分配和执行。当前 VM 的三条候选 OD（北京—上海、广州—武汉、成都—西安）均缺少完整道路核验；页面禁用分配是核验门槛生效。它们不是同一 OD 的三个候选。

如需在验收环境演示完整任务链路，可在根目录 `.env` 中设置 `DEMO_MODE=true`，先执行 `backend/db/migrations/20261025-demo-mode.sql`，再重启后端。此模式只允许几何、地图来源和相邻路段结构完整的 `PENDING_VERIFICATION` 路线进入演示任务；每个任务保存 `demoMode=true`，匹配结果和优化评估都会带 `DEMO_MODE` 标识，演示样本不计入正式推荐，候选数据源为 `DEMO_ONLY` 且不能直接发布。关闭开关并重启即可恢复正式核验门槛；路线数据库状态不会被演示流程改成 `VERIFIED`。

项目内的固定演示路线可用 `scripts/vm/seed-demo-route-material.sql` 导入。该脚本引用已保存的 OSRM/OpenStreetMap 原始响应，新增同一湖州—杭州 OD 的主路线和备选路线，保留 387/437 个 WGS84 折线点；两条路线及其路段仍为 `PENDING_VERIFICATION`，普通驾车资料不能证明货车通行。脚本只新增带固定名称的路线和路段，可重复执行，不会修改既有路线、任务或核验结论。导入后在调度员端选择湖州—杭州主路线创建演示任务；司机确认接单后，后端按预设场景沿同 OD 备选路线生成 GPS，司机不选择路线。轨迹匹配仅使用 Kafka/Flink/MongoDB 中的 GPS 点还原实际走法，模拟路线选择留在审计记录中，再由管理员执行演示评估。

旧 `/api/demo` 是内存联调样例，重启恢复初始状态；对应入口已移到 `/dispatcher/demo-monitoring` 和 `/driver/demo`，标明共享模拟数据。该演示不替代正式 MySQL 任务、道路核验或有效优化样本。

## 路线维护、地图与轨迹

管理员创建路线和有序地级市节点，按相邻城市录入道路段。每段填写道路名称/类型、正向里程/耗时、地图来源、请求/结果记录、取得时间、货车通行依据、核验来源/时间和核验人。

道路几何格式：

```json
{"coordinateSystem":"WGS84","coordinates":[[116.1,39.1],[116.2,39.2],[116.3,39.3]]}
```

也支持明确标注的 `GCJ02`；数组顺序是 `[经度, 纬度]`。几何须使用实际道路折线，示例仅说明格式，不是可核验路线。路线发布会检查全部路段、顺序/连通、几何端点、坐标体系、估算指标与核验材料。修改路段会撤销该段核验状态；已发布版本不可直接编辑，先创建新版本。地图请求 JSON 不允许包含服务 Key、令牌或密码。

普通驾车路线不能单独证明目标货车可通行。缺少可靠依据时保持待核验。地图请求不论成功或失败都会记录，管理员在核验请求记录页面复核；地图成功响应不会自动发布路线。

地图共用 `TransportMap.vue`，支持计划/实际匹配/候选图层、原始 GPS、超速点、轨迹回放和缺口断线。城市虚线仅是示意；真实道路折线与匹配结果分开展示。监控每 10 秒读取快照；回放不会发送 Kafka 消息或改变任务状态。

高德两类配置不能互换：

```dotenv
AMAP_WEB_SERVICE_KEY=本机Web服务Key
AMAP_JS_API_KEY=本机Web端JS_API_Key
AMAP_JS_SECURITY_CODE=平台要求时填写浏览器安全密钥
```

修改根目录 `.env` 后重启后端并刷新页面。`/api/map/configuration` 只向已登录浏览器提供浏览器指定的 JS 配置，不返回 Web 服务 Key。JS Key 已配置时加载高德 JS API 2.0；未配置或加载失败时显示带署名和原因的 Leaflet / OpenStreetMap 预览。预览不能视为高德最终验收通过。2026-10-24 已实际显示高德底图。Web 服务 Key 的普通驾车接口已成功；货车 v4 接口返回 `10012 / INSUFFICIENT_PRIVILEGES`。货车规划是单独授权的物流收费服务，可申请试用，但本项目当前默认采用普通道路资料配合公开货车通行依据，不要求充值。

路线页面默认“普通驾车道路资料”：调用 `/api/routes/road-geometry-quote`，只获取道路资料，绝不自动调用货车接口。成功请求保存在 `route_quotes`，刷新后可复核原始响应、脱敏参数与时间。可以将指标填入下方路段表单；返回折线缺失或不同步骤不连续时，几何留空，不能用直线补齐。普通驾车资料始终待补货车依据，不能自动核验或发布。具体操作见 `AMAP_SETUP.md`。

可选择“OSRM 开源道路资料”，调用 `/api/routes/open-road-quote`，无需 API Key，使用 WGS84 坐标、普通汽车 profile、服务直接返回的完整 GeoJSON 折线及自身里程/耗时；不能混入高德指标评分。公共演示服务限制低频使用，后端控制至少 1.1 秒请求间隔并标识 User-Agent，不批量采集。页面提供 OSRM / OpenStreetMap ODbL 署名及修正地图链接。切换到此模式会清空旧坐标，避免将 GCJ02 误作 WGS84。它也不能证明货车通行。

成功资料可以直接重新预览、复用：`GET /api/routes/truck-quote-history/{id}` 从数据库原始响应重新解析，不再调用任何路线接口。分步地图逐条绘制源折线，显示缺失/无效步骤和接缝两端距离；不补直线。填表指标保留两位小数以匹配表单精度，原始精确值留存在资料中。已保存真实资料及仍缺少的依据见 `ROAD_MATERIAL_REVIEW.md`。

选择“货车规划”才调用官方物流 v4 接口：策略1–13、车型1–4、车辆总质量load、核定载重weight、车高/车宽/轴数、province/number车牌参数。成功响应也只作为待核验资料；失败保留错误码与脱敏返回。收费服务的试用或开通由用户自行决定。

正式任务模拟器仅沿任务绑定的已核验、已发布道路折线生成 GPS，司机不选择路线。开启 `DEMO_MODE` 时，演示任务可沿待核验但结构完整的路线几何生成 GPS；若存在同 OD 的备选路线，后端按固定演示场景自动选择，司机只接任务。事件和接口明确标为 `DEMO_MODE`，不代表货车通行已经核验。原始 GPS 点包含 `SIMULATED`、WGS84 坐标、时间、运行号、序号和总点数，不伪造匹配道路/城市字段，也不包含模拟器选中的实际路线 ID。计划道路上下文供偏离检测使用；轨迹匹配只读取 GPS 点并独立还原道路序列。场景路线 ID 仅写入审计记录。加速模拟的事件时间不替代任务实际开始/结束时间。

## 匹配、有效样本与优化

司机完成任务并提交反馈后，管理员或本人可发起轨迹复核。后处理读取 MongoDB 的原始点，按同 OD 已核验道路几何匹配；`DEMO_MODE` 任务可匹配同 OD 的待核验结构化路线，但保存的样本会明确标为演示，不进入正式推荐。系统保存实际道路/城市序列、计划差异、覆盖率、缺口和排除理由。允许少量缺口；端点不完整、逆序、歧义、过稀疏、反馈明显不合理等不能作为有效样本。MongoDB 不可用时不覆盖已有分析结论。历史仅标为 VALID 而没有实际匹配证据的种子记录不能进入优化。

反馈合理性使用 `FEEDBACK_PLAUSIBILITY_V1`：有限正数、油耗不超过 2 L/km，反馈里程除以系统实际耗时的平均速度不超过 150 km/h。该上限用于排除明显不合理数据，不代表道路法定限速。加速 GPS 不增加系统实际运输时间；几秒内提交数百公里会保留反馈并排除优化。轨迹复核、历史样本校准和在线/Hadoop 均值使用一致口径，旧 VALID 标记也会复查。

正式优化从有效样本实际观测且仍已核验的路段中贪心选择连续路线。`DEMO_MODE` 只在演示环境使用同样的连续性和几何检查，对待核验路段生成隔离的 `DEMO_ONLY` 候选；每次成功或失败都保存不可变评估记录，演示候选不写入正式优化运行表、不覆盖原计划、不能自动发布。

计划/候选都使用同地图来源的预计耗时、里程和校准油耗估算。实际反馈单独展示并用于油耗校准，保留车辆模型、校准方法和来源任务。未实际行驶的组合油耗明确为估算。

同 OD 本轮集合内，越低越好的单项分为 `100 × (max - value) / (max - min)`；同值项均为 50 分。默认权重为耗时 40%、里程 30%、油耗 30%，合计必须为 100%。保存本轮边界、权重、指标来源、候选集合、选择理由和版本。一条有效运输可产生“基于 1 次有效运输的初步推荐”，前提是候选通过硬约束且改善；不宣称全局最优。没有可用证据时保存“暂无推荐”及原因。

评分和异常阈值在 `/admin/policy` 维护，带配置版本与并发修改校验。Flink 持续偏离要求距离、连续点数和持续时间同时满足，抖动/缺口/重复乱序会重置连续状态。匹配有效性由后处理决定，Flink 原始点仍保持 `NOT_MATCHED`、不可直接优化。

## 五组件链路与 Hadoop 离线统计

- MySQL：用户、车辆、维护、任务、反馈、路线版本、核验、配置、匹配和优化快照。
- Kafka：`logistics.gps.raw` 原始模拟事件。
- Flink：校验、超速/持续偏离、处理日志及 MongoDB sink。
- MongoDB：`gps_points`、`gps_anomalies`、`gps_rejected`。
- HDFS：GPS/异常归档、原始业务 JSONL、真实 Hadoop Streaming MapReduce 输出和完成清单。

管理员“平台运行状态”显示当前连接探测结果：Kafka/MySQL/HDFS 为 TCP 探测，Flink 为 REST 探测；MongoDB 使用后端轨迹读取所用连接执行 `ping`，当前通过本机 27018 SSH 隧道访问 VM 回环服务。隧道或服务不可用时显示不可用，不再根据“仅绑定 VM 回环”配置推断正常。健康探测不能替代整条数据链路验收。

正式按 OD 离线统计运行方法（Windows 项目根目录）：

```powershell
.\scripts\run-od-offline.ps1 -IdentityFile '你的SSH私钥绝对路径'
```

提示输入的是应用管理员账号，不是 Linux/MySQL 账号。脚本使用会话导出逐任务 JSONL，包含 MongoDB 异常计数；上传后在 VM 执行 Hadoop map/shuffle/reduce。当前 VM 内存约 4 GB，使用 `LOCAL_ON_HDFS`：Hadoop 本地运行器加真实 HDFS，不启动 YARN。输入没有预先做 SQL 汇总。

输入、输出和 manifest 保存在 `/logistics/transport-statistics/mapreduce/run=运行号/`。只有 Hadoop 产生 `_SUCCESS` 才更新 `latest.json`；清单保存 jobId、输入 SHA-256 和路径。管理员统计页通过 WebHDFS 读取该轮结果和来源，失败时明确不可用，不用 MySQL 结果伪装为离线统计。`scripts/vm/archive-od-statistics-hdfs.sh` 是旧 SQL 聚合归档工具，不作为 Hadoop 离线计算验收。

新清单同时保存反馈合理性规则版本及 mapper/reducer SHA-256。规则调整后新建一次统计运行，历史输出保持原样。

统计口径：任务数包含当前 OD 全部状态；平均耗时/里程/油耗只使用已完成、时间及数值合理的反馈；缺失值显示暂无数据，不填零。优化排除数独立展示，路线匹配无效不代表已经产生的实际反馈必须从描述性统计中删除。异常次数来自对应任务 MongoDB 异常事件；不同 OD 统计只作描述，不进行优劣评分。

GPS 归档使用 `scripts/vm/archive-gps-hdfs.sh`，按导出日期、任务、运行号保留独立快照，读回 SHA-256 后写 `_SUCCESS`。这与 OD 离线任务统计职责不同。Flink 部署、VM 服务启动、Checkpoint 与落库验证见 `streaming/README.md`。

## 主要 API

| 路径 | 职责 |
| --- | --- |
| `/api/auth/*`、`/api/admin/drivers/*` | 登录、注册、初始化、会话与司机审核 |
| `/api/business/users` | 用户、职员角色、启停和司机资料 |
| `/api/business/tasks` | 正式任务列表/创建及按 ID 的 assign、accept、complete、cancel、trajectory |
| `/api/business/vehicles`、`/api/business/vehicles/{id}/maintenance-reminder` | 读取车辆和下次保养日期；只有管理员能设置或清除提醒日期 |
| `/api/business/maintenance`、`/api/business/profile` | 管理员维护记录和本人资料；司机端保养提示通过车辆接口只读获取 |
| `/api/business/routes` | 路线、路段维护/核验、publish、versions |
| `/api/business/tasks/{id}/simulate`、`/match`、`/analysis` | 道路模拟、匹配复核、最新分析 |
| `/api/business/optimizations` | 同 OD 评估、快照历史与推荐证据 |
| `/api/business/policy` | 带版本的评分/异常配置 |
| `/api/routes/road-geometry-quote`、`/truck-quote` | 普通道路资料与货车规划查询，均不自动核验 |
| `/api/routes/truck-quote-history`、`/truck-quotes` | 分别读取成功资料和失败记录（均为 `/api/routes` 前缀） |
| `/api/map/configuration` | 浏览器地图配置 |
| `/api/statistics/offline/input`、`/offline` | 原始离线任务输入、HDFS Hadoop 结果（后者同 `/api/statistics` 前缀） |
| `/api/statistics/od` | 单独的 MySQL 在线描述性统计，不替代离线结果 |
| `/api/telemetry/*` | MongoDB 摘要、旧联调轨迹 |

## 验证与边界

```powershell
# 在 backend 目录
mvn test
# 在 frontend 目录
npm test
npm run build
# 在 streaming 目录
mvn package
# 在项目根目录
python -m unittest discover -s scripts/hadoop -p 'test_*.py' -v
```

业务完整流程、事务回滚/并发、权限、路线核验门槛、匹配和初步推荐在隔离 H2 数据库测试；测试道路/核验材料只用于测试，不写入开发 MySQL。Kafka → Flink → MongoDB 已在 VM 使用明确标注的旧模拟样例验证，Hadoop OD 作业已实际完成，高德 JS 实图已实际显示。已核验替代路线的全 VM 业务演示、完整货车材料和最终论文/答辩验收仍需补齐，不能因测试通过就声称整个方案完成。

另已用独立 MySQL/Kafka/MongoDB/HDFS 命名空间完成真实五组件技术联调，包括注册审核、任务、67 点模拟、持续偏离、匹配、初步推荐和 Hadoop 统计；脚本为 `scripts/acceptance/verify_live_chain.py`。合成道路和历史时间夹具只用于隔离测试，不批准公共道路，详见 `LIVE_CHAIN_ACCEPTANCE.md`。



## 可复核证据包

五组件联调证据目录可以生成并复核哈希清单：

```powershell
python scripts/acceptance/build_evidence_manifest.py backend/target/live-chain/20261024T134824Z_568c
python scripts/acceptance/verify_evidence_manifest.py backend/target/live-chain/20261024T134824Z_568c
```

第二条命令用于第三方检查文件缺失、内容篡改和未登记新增文件。工具会排除 `.env`、私钥和证书，不读取这些敏感文件。

评分快照的权重敏感性可复核：

```powershell
python scripts/analysis/score_sensitivity.py backend/target/live-chain/20261024T134824Z_568c/optimization.json
```

## 真实路线证据入库

路线资料必须先填写 `scripts/acceptance/route-evidence-template.json`，再运行：

```powershell
python scripts/acceptance/validate_route_evidence.py <route-evidence.json>
```

这是结构性、失败即停的检查；它不会替代交通管理/道路运营来源的人工判断，也不会把普通驾车资料自动变成货车通行证据。

## 配对差值与不确定性分析

同一 OD 有多条可比运输样本时，可运行：

```powershell
python scripts/analysis/bootstrap_paired_metrics.py <same-od-records.json>
```

脚本只接受一个 OD，计算计划与候选的配对差值、百分比变化和固定随机种子的 Bootstrap 95% 区间；样本少或为模拟数据时结果仍标为探索性，不能替代真实车队验证。

## 一键复核

已有技术证据可用以下命令复核：

```powershell
.\scripts\acceptance\run-reproducibility-checks.ps1 -EvidenceDir backend/target/live-chain/20261024T134824Z_568c
```

它会运行验收工具测试、证据哈希校验，并在未提供真实路线证据时明确警告；技术链路通过不会自动等同于货车路线通过。

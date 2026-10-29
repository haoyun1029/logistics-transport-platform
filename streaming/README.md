# Flink GPS 作业与 VM 演示部署

## 当前职责

目标集群 Flink 1.17.2 / Java 8，单并行度适配当前一槽位。Kafka `logistics.gps.raw` → 原始模拟事件校验 → 超速/持续偏离 → 日志和 MongoDB upsert。作业名为 `Logistics simulated GPS validation and sustained deviation (no road matching)`。

2026-10-24 已从 `file:/var/lib/logistics/flink-savepoints/savepoint-ab84c5-0ae6c242efaa` 恢复，当前作业 `750a5b861ee487e0b458e6c72154286c` 为 RUNNING，REST 观察到恢复次数为1；后续检查中 Checkpoint 持续完成。旧测试作业已取消；不要根据历史 ID 取消当前作业。这些数值是验收快照，不是实时状态。

持续偏离使用按任务分组的 checkpointed ValueState，每个模拟运行独立计算，状态 TTL 两天。距离、连续点数、持续时间同时满足才产生偏离事件；返回路线、缺口、重复/乱序重置连续状态。正式模拟器发送服务器独立的计划道路上下文和配置版本；没有已核验几何的旧事件仍为 `NOT_EVALUATED_NO_VERIFIED_GEOMETRY`，不能编造偏离结论。

原始事件仍标为 `SIMULATED`；Flink 保持 `routeMatchStatus=NOT_MATCHED`、`eligibleForOptimization=false`。道路/城市匹配由 Spring Boot 完成后的独立复核生成，不将异常检测等同于有效优化证据。

## 构建与提交

Windows 的 `streaming` 目录运行：

```powershell
mvn package
```

产物 `target/logistics-gps-streaming-0.1.0.jar`，目标 Java 8。Flink 运行库由集群提供，Kafka connector/client/base 使用 `cn.logistics.streaming.shaded` 包隔离，避免集群旧依赖导致 `OffsetsInitializer.committedOffsets` 的 NoSuchMethodError；不要直接删除集群 lib。

将产物和 `scripts/vm/submit-gps-job.sh` 上传 VM `/tmp`。首次部署、且确认没有活动的同名业务作业时，在 Linux 执行：

```bash
bash /tmp/submit-gps-job.sh /tmp/logistics-gps-streaming-0.1.0.jar --bootstrap-servers 192.168.146.128:9092 --topic logistics.gps.raw --group-id logistics-gps-mongo-demo-v1 --speed-limit-kmh 80 --mongo-uri 'mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000' --mongo-database logistics_system
```

脚本通过 REST 上传，不需要在 PATH 寻找 flink。阶段为 CHECK_VERSION、CHECK_ACTIVE_JOBS、UPLOAD_JAR、RUN_JOB；HTTP 失败保留响应且不自动重试。检测到活动的物流作业时停止，避免重复消费。版本升级需先查询当前作业，取消并确认 CANCELED，再提交；不要照抄旧作业 ID。

`SUBMITTED` 仅说明提交成功；必须查询 RUNNING、TaskManager stdout 的 GPS_PROCESSED、MongoDB 实际结果及 Checkpoints。当前持久化作业使用 `/opt/logistics-artifacts/logistics-gps-deviation-0.1.0.jar`；临时上传目录只用于提交过程，不作为运行状态依据。

Kafka 从组已提交偏移开始，没有偏移时从最早消息读取。换组会重放，应明确规划；旧异常不会因修改阈值自动撤销。默认超速阈值参数用于旧事件，正式事件使用服务器上下文中的策略。

未设置 `--mongo-uri` 时输出 MONGODB_SINK_DISABLED，仅为控制台检查，不代表完整链路。需要认证 URI 时使用部署环境配置，不将真实 URI 提交源码。REST 提交时 JobManager 进程环境不同于提交脚本 shell，不能假定 shell 环境自动传入集群。

Checkpoint 每 10 秒，至少一次处理；失败最多固定间隔重试三次。当前作业使用持久化目录 `file:///var/lib/logistics/flink-checkpoints`，并已实际从 `file:/var/lib/logistics/flink-savepoints/savepoint-ab84c5-0ae6c242efaa` 恢复，REST 观察到 `restored=1`。这证明本次同集群 Savepoint 恢复，不承诺跨集群重启恢复，也不宣称端到端精确一次。真实五组件隔离联调和限制见根目录 `LIVE_CHAIN_ACCEPTANCE.md`。

## VM 服务启动与健康检查

已有 VM 地址 `192.168.146.128`，CentOS 7。按现有安装启动，不重复安装：

```bash
systemctl start mysqld
systemctl start logistics-mongod
cd /usr/local/kafka
bin/zookeeper-server-start.sh -daemon config/zookeeper.properties
bin/kafka-server-start.sh -daemon config/server.properties
cd /usr/local/flink
./bin/start-cluster.sh
```

先用 `jps`、监听端口和 systemctl 确认服务未运行，再执行对应命令，避免启动重复实例。Kafka 在 ZooKeeper 后启动；Flink 集群启动后还需检查/提交业务作业，不能只看 JobManager 进程。

已有 HDFS 元数据不格式化。NameNode/DataNode 未运行时，使用已上传的 `scripts/vm/start-local-hdfs.sh`：

```bash
bash /tmp/start-local-hdfs.sh 192.168.146.128
HADOOP_HEAPSIZE_MAX=128 /opt/remodule/hadoop-3.1.3/bin/hdfs dfsadmin -report
HADOOP_HEAPSIZE_MAX=128 /opt/remodule/hadoop-3.1.3/bin/hdfs dfsadmin -safemode get
```

脚本检查存储 clusterID、备份 hosts/配置/NameNode 元数据，再修正本机主机映射并启动本机服务。8020 已运行时停止，不重复配置；不强制退出安全模式、不启动 YARN、不改其他主机配置。

只读检查：

```bash
systemctl is-active mysqld logistics-mongod
jps
curl -fsS http://127.0.0.1:8081/overview
curl -fsS http://127.0.0.1:8081/jobs/overview
java -cp /tmp/logistics-gps-deviation-0.1.0.jar cn.logistics.streaming.MongoGpsProbe logistics_system DEMO-2026-002 16
free -m
```

通过实际返回的 TaskManager ID 查询 `/taskmanagers/{id}/stdout`，不要长期硬编码临时 ID。Windows 可运行 `scripts/check-vm-services.ps1`；MongoDB VM 外的 27017 不开放是预期，Windows 查询需 SSH 隧道27018。

当前 VM 约 4 GB 内存，本次检查 available 约282 MB、swap已用约530 MB。HDFS NameNode/DataNode 使用256 MB堆上限，Hadoop离线本地运行器192 MB；不启动额外YARN服务。数值随负载变化，不承诺扩容后的性能。

## MongoDB 安装记录与数据语义

已安装 MongoDB 6.0.26 官方 RHEL7/AVX/x86_64 包，无需重复安装。独立 systemd 服务 `logistics-mongod`，安装目录 `/opt/logistics-mongodb-6.0.26`，数据 `/var/lib/logistics-mongodb`，仅监听127.0.0.1:27017；WiredTiger缓存0.25 GB。安装脚本 `scripts/vm/install-mongodb-local.sh` 保留供新环境复现，检查官方包 SHA-256、已有端口/路径，不覆盖数据库。

- `gps_points`：有效原始点、规则阈值、处理状态和时间。
- `gps_anomalies`：超速或持续偏离的事件；同一点可有多个异常标签，事件数不等于独立持续事故数。
- `gps_rejected`：无效事件指纹和拒收原因。

原始 JSON 的 SHA-256 为事件 ID，重放 upsert 去重。MongoDB 写入失败使作业失败并按策略重试，不吞掉错误。数据按集合逐次写入，不宣称跨集合事务或跨集合原子快照。

本轮实际发送旧联调任务16个点，新作业消费并落库。累积查询64个模拟点、48个超速事件、0条拒收；它们来自多次演示，不是64次运输。当前旧轨迹仍是城市连线样例，没有货车核验，只能证明Kafka/Flink/Mongo链路。持续偏离规则已在单元测试验证；沿真实已核验替代道路的实际 VM 演示尚未完成。

## HDFS 归档与 Hadoop 统计

GPS归档：

```bash
bash /tmp/archive-gps-hdfs.sh /tmp/logistics-gps-deviation-0.1.0.jar DEMO-2026-002
```

需先上传 `scripts/vm/archive-gps-hdfs.sh`。脚本按导出日期/任务/运行号写入独立快照，读回SHA-256，全部成功才写 `_SUCCESS`。重复快照不能重复计入业务统计。原始成功快照保留在 `/logistics/gps/export_date=2026-10-23/task_id=DEMO-2026-002/run-20261023-170228-vtV1Y7`。

正式 OD 离线统计通过 Windows 项目根目录 `scripts/run-od-offline.ps1 -IdentityFile 私钥路径` 运行；提示输入应用管理员账号。它导出逐任务JSONL和Mongo异常计数，再在VM执行 `scripts/vm/run-od-mapreduce.sh` 的真实Hadoop Streaming Map/Shuffle/Reduce；输入/结果都在HDFS，使用Hadoop本地运行器而不是YARN。

2026-10-24最新验收作业 `job_local1359457162_0001`，运行号 `20261024T104947Z-74232`，输入SHA-256 `fb1a642418abb032cc79c1f0fcc150810c69ed8ceeb0f4f3593fe77742c40897`。结果有北京—上海2任务、广州—武汉1任务；只有北京—上海1条完成反馈进入均值。该历史反馈因缺少核验/匹配证据排除优化，不能拿描述性均值证明推荐有效。

`/logistics/transport-statistics/latest.json` 只指向具有 `_SUCCESS` 的完成运行；管理员页面通过WebHDFS显示jobId、完成时间、原始输入路径/哈希和结果路径。旧 `archive-od-statistics-hdfs.sh` 仅是SQL聚合归档，不作为本轮Hadoop计算证据。

## 验收边界

本地流处理模块10项测试通过并完成打包，覆盖原始校验、作业图、持续偏离状态和重置。新作业实际RUNNING且已验证旧模拟消息落库。正式道路模拟/匹配/同源评分的完整业务测试使用隔离H2和模拟Kafka/Mongo，不替代已核验路线的全VM演示。

JS高德实图和普通驾车道路资料查询已实际通过。货车物流v4接口当前10012权限不足；默认采用普通道路资料加公开货车依据，三条OD的真实逐段货车材料仍缺。不能宣称已核验替代路线固定案例或整套毕设验收完成。


## 2026-10-25 启动快照

本次重启后从保存点恢复的业务作业 ID 为 `e3081bf349bdb39e424908ca43da874a`，状态 RUNNING；启动前确认无活动物流作业，未重复消费或提交第二个作业。Kafka 首次启动遇到旧 ZooKeeper 临时节点，等待会话过期后重试成功。

# 继续工作现场（2026-10-24）

当前 Windows 前端 `localhost:5173`、后端 `localhost:8080` 正常运行；MongoDB SSH 隧道使用本机 `27018` 转发到 VM `127.0.0.1:27017`。VM `192.168.146.128` 为 CentOS 7，MySQL 5.7.28、MongoDB 6.0.26、ZooKeeper/Kafka、Flink 1.17.2、HDFS 3.1.3 已安装并运行。不要重复初始化账号、安装组件、清空数据库或格式化 HDFS。

当前唯一物流 Flink 作业为 `750a5b861ee487e0b458e6c72154286c`，状态 RUNNING，从 `file:/var/lib/logistics/flink-savepoints/savepoint-ab84c5-0ae6c242efaa` 恢复，已观察 `restored=1`；持久化 JAR 为 `/opt/logistics-artifacts/logistics-gps-deviation-0.1.0.jar`。继续前先查 REST，不重复提交或取消当前作业。

本轮已完成真实五组件隔离联调，最终运行号 `20261024T134824Z_568c`，报告在 `backend/target/live-chain/20261024T134824Z_568c/report.json`。隔离 MySQL/Mongo/Kafka/HDFS 命名空间已保留，原作业已恢复。67 个模拟点经 Kafka→Flink→MongoDB，Hadoop Map/Shuffle/Reduce 也完成；90 分钟历史起点是明确标注的隔离合成夹具，不是真实行驶证据。

反馈合理性规则 `FEEDBACK_PLAUSIBILITY_V1` 已统一到完成、匹配、优化、在线统计和 Hadoop mapper：系统起止时间推导平均速度须不超过 150 km/h，异常反馈保留但标为 INVALID 并排除均值/优化。后端 `mvn -q test` 82 项、前端 `npm test` 54 项、生产构建、Hadoop Python 5 项均已通过；未修改的 Flink 源码本轮未重复打包。

最新正式 HDFS/Hadoop 结果：run `20261024T135016Z-36232`，job `job_local1349876180_0001`，输入 SHA `fb1a642418abb032cc79c1f0fcc150810c69ed8ceeb0f4f3593fe77742c40897`；API `dataStatus=HADOOP_RESULT_AVAILABLE`。管理页显示北京→上海 2 任务、广州→武汉 1 任务，北京→上海合理反馈均值 1090 分钟/1194 公里/405 升，广州→武汉无均值。结果与日志见 `backend/target/feedback-main-hadoop.log`。

免费道路资料已支持 OSRM/OpenStreetMap；资料 #2 湖州→杭州 G25 段 54.2758 km、36.1933 min、387 点已保存为 DB-4 待核验草稿。普通驾车资料不证明货车通行，货车接口仍因 `INSUFFICIENT_PRIVILEGES` 不可用；三条不同 OD 仍须逐段补齐几何、货车依据、来源和核验时间后才能发布、模拟和优化。不要用隔离夹具、城市直线或普通驾车结果替代真实核验。

下一步优先检查管理端统计页面和现有截图，必要时补充验收记录；不重复已通过的全量测试或联调。最终答辩材料、三条真实货车路线及真实替代走法仍未宣称完成。

## 2026-10-25 启动复核

本次已恢复 VM `192.168.146.128` 及 MySQL、MongoDB、ZooKeeper、Kafka、HDFS NameNode/DataNode、Flink。Kafka 首次启动因 ZooKeeper broker 临时节点尚未过期而失败，等待会话过期后重试成功；没有删除 topic 或业务数据。Windows Mongo 隧道 `27018`、Spring Boot `8080` 和 Vite `5173` 已启动。

从保存的 `file:/var/lib/logistics/flink-savepoints/savepoint-ab84c5-0ae6c242efaa` 恢复业务 GPS 作业，当前 JobID 为 `e3081bf349bdb39e424908ca43da874a`，状态 RUNNING；Flink REST 返回 1 个 TaskManager、2 个运行中的算子。启动时没有重复提交第二个物流作业。

# 项目暂停记录（2026-10-23）

用户要求暂停开发并准备关机；恢复前不要自动继续开发或访问地图服务。

关机收尾已确认：Windows 前后端 8080/5173 均无监听，VMware `vmrun list` 返回 `Total running VMs: 0`。临时 SSH 公钥已从 VM 授权文件移除；本机临时密钥文件删除被执行策略阻止，后续处理残留文件时应遵守执行策略，不尝试绕过。

## 已保存的数据与状态

- 源码、部署脚本和说明文件已保存。流处理模块最近构建通过，8 项测试通过。
- MySQL 业务库、MongoDB 和 HDFS 均使用虚拟机磁盘存储，不清库、不格式化。
- MongoDB 已核对目标任务 `DEMO-2026-002`：16 个有效模拟 GPS 点、12 个超速点、0 条拒收，仍未进行道路匹配，不能作为有效优化样本。
- HDFS 归档：`/logistics/gps/export_date=2026-10-23/task_id=DEMO-2026-002/run-20261023-170228-vtV1Y7`，4 个导出文件读回 SHA-256 校验通过，存在 `_SUCCESS`。
- Flink 作业 `faf919ea9c53394814a87120f9b3370d` 已通过 stop-with-savepoint 正常结束，状态 `FINISHED`。
- Savepoint：`file:/var/lib/logistics/flink-savepoints/savepoint-faf919-d5c35ed438fd`，位于该虚拟机磁盘，需保留。
- 内存中的演示任务概览已另存 `backend/target/pause-20261023/demo-overview.json`；后端重启会恢复固定初始任务，不会自动恢复该 JSON。
- 本次地图 Key 尚未使用或写入配置。文档和源码不保存密钥。

## 恢复时从这里继续

1. 用户明确要求继续后，启动 VMware 中的 `D:\Hadoop\Hadoop101\Hadoop101.vmx`，重新核实 VM IP，原地址为 `192.168.146.128`。
2. 核实 SSH 认证；本次临时授权公钥在关机收尾时移除，不能假定后续仍可免密登录。
3. 已确认 Kafka 与 Flink 安装在 `/usr/local/kafka`、`/usr/local/flink`；Java 为 VM Java 8，Flink 1.17.2。按依赖顺序启动 ZooKeeper、Kafka、MongoDB、HDFS、Flink，先检查是否已自动启动，避免重复。
4. MongoDB 服务名为 `logistics-mongod`，仅绑定 VM `127.0.0.1:27017`；MySQL 服务名为 `mysqld`。HDFS 采用 `scripts/vm/start-local-hdfs.sh` 恢复现有存储，禁止格式化。
5. 如需恢复原 Flink 状态，在 REST JAR run 请求中配置 `savepointPath` 为上述 Savepoint，保留原算子 UID 和消费组 `logistics-gps-mongo-demo-v1`；当前提交脚本尚未提供恢复参数，不应把恢复路径误传为普通业务参数。
6. 重新启动 Windows 后端和前端，确认登录和组件健康状态。MongoDB 的 Windows 查询连接、按 OD 的运输离线统计、道路匹配和页面轨迹仍未完成。
7. 后续再配置并验证高德 Web 服务能力；Key 只写入本机 `.env`，调用地图结果仍需逐段补充货车通行依据。

VM 约 4 GB 内存，启动 HDFS 后曾使用约 614 MB swap；恢复后应观察资源，不擅自启动额外工作节点或 YARN。

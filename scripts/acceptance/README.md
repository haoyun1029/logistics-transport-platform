# 验收与研究工具

这些脚本用于把路线核验、统计分析和第三方复核做成可重复步骤。它们不会修改 MySQL、MongoDB、Kafka 或 HDFS，也不会把普通驾车资料批准为货车路线。

## 证据清单

```powershell
python scripts/acceptance/build_evidence_manifest.py <evidence-dir>
python scripts/acceptance/verify_evidence_manifest.py <evidence-dir>
```

第一条生成文件大小和 SHA-256 清单；第二条在第三方副本上检查 `missing`、`changed` 和 `extra`。

## 路线证据入库前检查

```powershell
python scripts/acceptance/validate_route_evidence.py <route-evidence.json>
```

模板为 `route-evidence-template.json`。结构完整只会得到 `READY_FOR_HUMAN_REVIEW`，仍需人工判断来源是否真的适用于目标货车。

## 测试

```powershell
python -m unittest discover -s scripts/acceptance -p 'test_*.py' -v
```

## 统计分析

`scripts/analysis/score_sensitivity.py` 只在同一 OD 内改变权重并检查排名稳定性；`scripts/analysis/bootstrap_paired_metrics.py` 计算同一 OD 的配对差值和 Bootstrap 区间。两者都保留样本数和探索性限制，不生成全局最优结论。

## 一键复核

在已有证据目录上运行：

```powershell
.\scripts\acceptance\run-reproducibility-checks.ps1 -EvidenceDir backend/target/live-chain/20261024T134824Z_568c
```

如有待人工复核的路线材料，再增加 `-RouteEvidence <route-evidence.json>`。未提供路线材料时脚本会明确警告，技术链路通过不等于真实货车路线通过。

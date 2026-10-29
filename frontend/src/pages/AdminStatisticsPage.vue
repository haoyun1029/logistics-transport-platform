<script setup lang="ts">
import { useWorkspace } from '../workspace'
import { time } from '../business'
import OdStatisticsChart from '../components/OdStatisticsChart.vue'
const { statistics, loadOverview } = useWorkspace()
</script>

<template>
  <div class="portal-content">
    <OdStatisticsChart />
    <section class="section" id="statistics">
      <div class="section-title"><div><p class="kicker">HADOOP MAPREDUCE · HDFS</p><h2>按 OD 离线运输统计</h2></div><button class="outline-button" @click="loadOverview">刷新离线结果</button></div>
      <template v-if="statistics">
        <p class="source-note">{{ statistics.note }}</p>
        <div v-if="statistics.dataStatus === 'HADOOP_RESULT_AVAILABLE' || statistics.dataStatus === 'NO_TASKS'" class="business-feedback">
          <dl class="business-details"><div><dt>数据来源</dt><dd>Hadoop MapReduce / HDFS</dd></div><div><dt>完成时间</dt><dd>{{ time(statistics.completedAt) }}</dd></div><div><dt>作业 ID</dt><dd>{{ statistics.jobId }}</dd></div><div><dt>执行方式</dt><dd>{{ statistics.executionMode === 'LOCAL_ON_HDFS' ? 'VM 内 Hadoop 本地执行器，输入/输出均在 HDFS' : statistics.executionMode }}</dd></div></dl>
          <details><summary>原始输入与结果追溯</summary><p class="source-note">原始逐任务 JSONL：{{ statistics.inputPath }}</p><p class="source-note">输入 SHA-256：{{ statistics.inputSha256 }}</p><p class="source-note">HDFS 结果：{{ statistics.archivePath }}</p><p class="source-note">运行版本：{{ statistics.runId }}。这是运行时快照；新任务完成后需重新运行离线统计。</p></details>
        </div>
        <div v-if="statistics.records.length" class="task-table statistics-table"><div class="table-head"><span>OD / 有效反馈数</span><span>任务数</span><span>平均实际耗时</span><span>平均实际里程</span><span>平均实际油耗</span><span>异常事件</span></div><div v-for="record in statistics.records" :key="`${record.originCity}-${record.destinationCity}`" class="table-row"><span><strong>{{ record.originCity }} → {{ record.destinationCity }}</strong><small>{{ record.completedFeedbackCount ?? 0 }} 条完成反馈 · {{ record.simulatedTaskCount ?? 0 }} 条模拟任务 · {{ record.excludedSampleCount ?? 0 }} 条排除优化</small></span><span>{{ record.taskCount }}</span><span>{{ record.averageActualElapsedMinutes ?? '—' }} min</span><span>{{ record.averageActualDistanceKm ?? '—' }} km</span><span>{{ record.averageActualFuelLiters ?? '—' }} L</span><span>{{ record.anomalyCount }}</span></div></div>
        <div v-else class="empty-panel"><strong>{{ statistics.dataStatus === 'NO_TASKS' ? '该次离线输入没有运输任务' : '尚无可读取的完整 Hadoop 结果' }}</strong><p>完成任务后运行离线统计脚本。页面读取 HDFS 中已成功完成作业的结果。</p></div>
      </template>
      <div v-else class="empty-panel">正在读取 Hadoop 离线结果…</div>
    </section>
  </div>
</template>

import type { Portal } from './auth'

export type WorkspacePage = { path: string; title: string; description: string; component: string }

// Keep each portal focused on the workflow shown in the review sketches.
export const navigation: Record<Portal, WorkspacePage[]> = {
  admin: [
    { path: '/admin/users', title: '用户管理', description: '管理职员账号与司机注册申请', component: 'AdminAccountsPage' },
    { path: '/admin/drivers', title: '司机审核', description: '审核司机注册并维护司机资料', component: 'AdminDriversPage' },
    { path: '/admin/vehicles', title: '车辆管理', description: '维护车辆档案、状态和保养提醒日期', component: 'AdminVehiclesPage' },
    { path: '/admin/routes', title: '基础路线', description: '维护路线版本、地图资料与逐段货车核验', component: 'BusinessRoutesPage' },
    { path: '/admin/verification', title: '路线核验记录', description: '复核道路查询记录、成功资料和失败原因', component: 'AdminVerificationPage' },
    { path: '/admin/records', title: '运输记录', description: '查看运输任务、反馈和样本状态', component: 'BusinessAdminTasksPage' },
    { path: '/admin/optimization', title: '路径优化', description: '查看同起终点路线与运输样本的优化结果', component: 'BusinessOptimizationPage' },
    { path: '/admin/statistics', title: '离线统计', description: '查看 Hadoop MapReduce 与 HDFS 运输统计', component: 'AdminStatisticsPage' },
    { path: '/admin/telemetry', title: '轨迹证据', description: '查看 MongoDB GPS 点、异常和匹配证据', component: 'AdminTelemetryPage' },
    { path: '/admin/system', title: '组件状态', description: '检查业务服务及数据组件连接状态', component: 'AdminSystemPage' },
    { path: '/admin/policy', title: '系统配置', description: '设置评分权重与运输异常阈值', component: 'PolicyPage' },
    { path: '/admin/profile', title: '个人资料', description: '维护当前管理员的姓名与联系方式', component: 'ProfilePage' },
  ],
  dispatcher: [
    { path: '/dispatcher', title: '首页 / 调度中心', description: '查看今日任务、任务分配、车队运输状态与异常车辆', component: 'BusinessDispatcherHomePage' },
    { path: '/dispatcher/tasks', title: '运输任务管理', description: '创建运输任务并查看任务进度', component: 'BusinessDispatchPage' },
    { path: '/dispatcher/scheduling', title: '任务调度', description: '为待分配任务安排司机和车辆', component: 'BusinessTaskSchedulingPage' },
    { path: '/dispatcher/vehicles', title: '车辆档案', description: '查看真实车辆、绑定司机、能源与保养状态', component: 'DispatcherVehiclesPage' },
    { path: '/dispatcher/monitoring', title: '实时车辆监控', description: '查看运输车辆、任务路线和演示位置更新', component: 'BusinessMonitoringPage' },
    { path: '/dispatcher/profile', title: '个人资料', description: '维护当前调度员的姓名与联系方式', component: 'ProfilePage' },
  ],
  driver: [
    { path: '/driver/tasks', title: '我的运输任务', description: '查看待确认与运输中的任务', component: 'BusinessDriverPage' },
    { path: '/driver/vehicles', title: '车辆状态', description: '查看所分配货车状态与保养提醒', component: 'DriverVehicleStatusPage' },
    { path: '/driver/history', title: '历史运输记录', description: '查看已完成任务与运输反馈', component: 'BusinessHistoryPage' },
    { path: '/driver/profile', title: '个人资料', description: '维护当前司机的姓名与联系方式', component: 'ProfilePage' },
  ],
}

export function normalizePath(path: string) {
  return path.replace(/\/+$/, '') || '/'
}

export function resolvePage(portal: Portal, path: string) {
  const normalized = normalizePath(path)
  if (normalized === `/${portal}`) return navigation[portal][0]
  return navigation[portal].find(page => page.path === normalized)
}

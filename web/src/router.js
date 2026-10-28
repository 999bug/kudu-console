import { createRouter, createWebHistory } from 'vue-router'
import ClusterView from './views/ClusterView.vue'
import TablesView from './views/TablesView.vue'
import TableDetailView from './views/TableDetailView.vue'
import JobsView from './views/JobsView.vue'
import ClustersView from './views/ClustersView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/cluster' },
    { path: '/cluster', component: ClusterView, meta: { title: '集群状态' } },
    { path: '/tables', component: TablesView, meta: { title: '表管理' } },
    { path: '/table', component: TableDetailView, meta: { title: '表详情' } },
    { path: '/jobs', component: JobsView, meta: { title: '任务中心' } },
    { path: '/clusters', component: ClustersView, meta: { title: 'Kudu 平台' } },
    { path: '/settings', redirect: '/clusters' }
  ]
})

export default router

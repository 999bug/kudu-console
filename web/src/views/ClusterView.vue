<script setup>
import { computed, onMounted } from 'vue'
import { store, refreshCluster } from '../store'

const status = computed(() => store.cluster)
onMounted(() => {
  if (!store.cluster) refreshCluster()
})

const tsTotal = computed(() => status.value?.tabletServers?.length ?? status.value?.counts?.tabletServers ?? null)
const tsLive = computed(() => status.value?.counts?.liveTabletServers ?? null)
const leader = computed(() => (status.value?.masters || []).find((m) => m.role === 'LEADER') || null)

function roleClass(role) {
  if (role === 'LEADER') return 'tag pk'
  if (role === 'FOLLOWER') return 'tag'
  return 'tag plain muted'
}

function shortClusterId(id) {
  if (!id) return '—'
  return id
}
</script>

<template>
  <div v-if="!status" class="loading"><span class="spinner"></span>正在读取集群状态…</div>
  <div v-else>
    <div class="stats">
      <div class="stat">
        <div class="stat-label">表数量</div>
        <div class="stat-value">{{ status.counts?.tables ?? '—' }}</div>
        <div class="stat-note">含 impala 托管表</div>
      </div>
      <div class="stat">
        <div class="stat-label">Tablet Server</div>
        <div class="stat-value">
          {{ tsLive ?? '—' }}<span class="muted" style="font-size: 13px"> / {{ tsTotal ?? '—' }}</span>
        </div>
        <div class="stat-note">
          在线 / 总数<span v-if="status.counts?.deadTabletServers">，{{ status.counts.deadTabletServers }} 台离线</span>
        </div>
      </div>
      <div class="stat">
        <div class="stat-label">Master</div>
        <div class="stat-value">{{ status.masters?.length ?? '—' }}</div>
        <div class="stat-note">Leader {{ leader?.rpcAddress || '未知' }}</div>
      </div>
      <div class="stat">
        <div class="stat-label">服务版本</div>
        <div class="stat-value small">{{ status.kuduVersion || '—' }}</div>
        <div class="stat-note">响应 {{ status.elapsedMs }} ms</div>
      </div>
    </div>

    <div v-if="status.webUi && !status.webUi.available" class="banner warn" style="margin-top: 14px">
      master Web UI 不可达（{{ status.webUi.error }}），当前仅展示 RPC 侧信息：主节点角色、tablet server 存活与表统计不可用。
    </div>
    <div v-else-if="status.webUi?.degraded" class="banner warn" style="margin-top: 14px">
      master Web UI 可用但部分数据缺失，存活状态与表统计可能不完整。
    </div>

    <div class="card" style="margin-top: 14px">
      <div class="card-head">
        <div class="card-title">Master 节点</div>
        <div class="card-sub">Cluster ID {{ shortClusterId(status.clusterId) }}</div>
        <div class="card-actions">
          <a
            v-if="status.webUi?.leaderWebUrl"
            class="btn sm"
            :href="status.webUi.leaderWebUrl"
            target="_blank"
            rel="noreferrer"
          >打开 Leader Web UI</a>
        </div>
      </div>
      <div class="card-body flush grid-wrap">
        <table class="grid">
          <thead>
            <tr>
              <th>角色</th>
              <th>RPC 地址</th>
              <th>HTTP 地址</th>
              <th>启动时间</th>
              <th>版本</th>
              <th>UUID</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="master in status.masters" :key="master.rpcAddress">
              <td><span :class="roleClass(master.role)">{{ master.role || 'unknown' }}</span></td>
              <td class="mono">{{ master.rpcAddress || '—' }}</td>
              <td class="mono">
                <a v-if="master.webUrl" :href="master.webUrl" target="_blank" rel="noreferrer">{{ master.httpAddress }}</a>
                <span v-else class="muted">—</span>
              </td>
              <td class="nowrap">{{ master.startTime || '—' }}</td>
              <td class="mono">{{ master.version || '—' }}</td>
              <td class="mono muted">{{ master.uuid || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">Tablet Server</div>
        <div class="card-sub" v-if="status.webUi?.available">心跳时间来自 master Web UI</div>
        <div class="card-sub" v-else>Web UI 不可达，无法读取存活状态</div>
      </div>
      <div class="card-body flush grid-wrap">
        <table class="grid" v-if="status.tabletServers?.length">
          <thead>
            <tr>
              <th>状态</th>
              <th>RPC 地址</th>
              <th>HTTP</th>
              <th class="num">心跳距今</th>
              <th>启动时间</th>
              <th>版本</th>
              <th>UUID</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ts in status.tabletServers" :key="ts.address || ts.uuid">
              <td>
                <span class="status">
                  <span class="status-dot" :class="ts.live ? 'ok' : 'bad'"></span>
                  {{ ts.live ? '在线' : '离线' }}
                </span>
              </td>
              <td class="mono">{{ ts.address || '—' }}</td>
              <td class="mono">
                <a v-if="ts.webUrl" :href="ts.webUrl" target="_blank" rel="noreferrer">{{ ts.httpAddress }}</a>
                <span v-else class="muted">—</span>
              </td>
              <td class="num">{{ ts.heartbeatAgeSec ?? '—' }} s</td>
              <td class="nowrap">{{ ts.startTime || '—' }}</td>
              <td class="mono">{{ ts.version || '—' }}</td>
              <td class="mono muted">{{ ts.uuid }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">没有读取到 tablet server。若集群正常，请检查 master Web UI 端口（RPC 端口 + 1000）。</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { store, loadClusters, switchCluster, refreshCluster, toast, toastError } from '../store'
import Dialog from '../components/Dialog.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'

const dialog = reactive({ open: false, id: '', name: '', masters: '', operationTimeoutMs: null,
  adminTimeoutMs: null, webUiEnabled: true, webUiPortOffset: 1000 })
const busy = ref(false)
const error = ref('')
const probe = ref(null)
const deleting = ref(null)
const removing = ref(false)
const limits = ref(null)

const isEdit = computed(() => !!dialog.id)

async function load() {
  try {
    await loadClusters()
  } catch (e) {
    error.value = e.message
  }
  try {
    limits.value = await api.limits()
  } catch (e) {
    // 限制信息拿不到不影响主流程
  }
}

function openCreate() {
  Object.assign(dialog, {
    open: true, id: '', name: '', masters: '', operationTimeoutMs: null,
    adminTimeoutMs: null, webUiEnabled: true, webUiPortOffset: 1000
  })
  probe.value = null
  error.value = ''
}

function openEdit(cluster) {
  Object.assign(dialog, {
    open: true,
    id: cluster.id,
    name: cluster.name,
    masters: cluster.masters,
    operationTimeoutMs: cluster.operationTimeoutMs,
    adminTimeoutMs: cluster.adminTimeoutMs,
    webUiEnabled: cluster.webUiEnabled !== false,
    webUiPortOffset: cluster.webUiPortOffset === null || cluster.webUiPortOffset === undefined
      ? 1000 : cluster.webUiPortOffset
  })
  probe.value = null
  error.value = ''
}

function payload() {
  return {
    name: dialog.name.trim(),
    masters: dialog.masters.trim(),
    operationTimeoutMs: dialog.operationTimeoutMs ? Number(dialog.operationTimeoutMs) : null,
    adminTimeoutMs: dialog.adminTimeoutMs ? Number(dialog.adminTimeoutMs) : null,
    webUiEnabled: dialog.webUiEnabled,
    webUiPortOffset: dialog.webUiPortOffset === null || dialog.webUiPortOffset === ''
      ? null : Number(dialog.webUiPortOffset)
  }
}

async function save() {
  error.value = ''
  if (!dialog.name.trim()) {
    error.value = '请填写平台名称'
    return
  }
  if (!dialog.masters.trim()) {
    error.value = '请填写 master 地址'
    return
  }
  busy.value = true
  try {
    if (isEdit.value) {
      await api.updateCluster(dialog.id, payload())
      toast('平台已更新：' + dialog.name)
    } else {
      const created = await api.createCluster(payload())
      toast('平台已添加：' + created.name)
    }
    dialog.open = false
    await load()
    await refreshCluster()
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function test() {
  probe.value = null
  try {
    probe.value = await api.testCluster(dialog.masters)
  } catch (e) {
    probe.value = { ok: false, error: e.message }
  }
}

async function activate(cluster) {
  try {
    await switchCluster(cluster.id)
    toast('已切换到 ' + cluster.name)
  } catch (e) {
    toastError(e)
  }
}

async function remove() {
  if (!deleting.value) return
  removing.value = true
  try {
    await api.deleteCluster(deleting.value.id)
    toast('平台已删除：' + deleting.value.name)
    deleting.value = null
    await load()
    await refreshCluster()
  } catch (e) {
    toastError(e)
  } finally {
    removing.value = false
  }
}

function platformCountText() {
  return store.clusters.length ? `${store.clusters.length} 个平台` : '未配置'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        <div class="card-title">Kudu 平台</div>
        <div class="card-sub">{{ platformCountText() }}，切换后所有页面操作都作用于当前平台</div>
        <div class="card-actions">
          <button class="btn sm" @click="load">刷新</button>
          <button class="btn primary sm" @click="openCreate">添加平台</button>
        </div>
      </div>

      <div v-if="error" class="banner error" style="margin: 12px">{{ error }}</div>

      <div class="card-body flush grid-wrap" v-if="store.clusters.length">
        <table class="grid">
          <thead>
            <tr>
              <th>平台</th>
              <th>master 地址</th>
              <th>Web UI</th>
              <th>超时</th>
              <th>状态</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="cluster in store.clusters" :key="cluster.id">
              <td>
                <div style="font-weight: 600">{{ cluster.name }}</div>
                <div class="mono muted" style="font-size: 11px">{{ cluster.id }}</div>
              </td>
              <td class="mono wrap">{{ cluster.masters }}</td>
              <td class="muted">
                {{ cluster.webUiEnabled === false ? '已关闭' : '开启' }}
                <span v-if="cluster.webUiEnabled !== false" class="muted">
                  · +{{ cluster.webUiPortOffset ?? 1000 }}
                </span>
              </td>
              <td class="muted">
                {{ cluster.operationTimeoutMs || '默认' }} / {{ cluster.adminTimeoutMs || '默认' }} ms
              </td>
              <td>
                <span v-if="cluster.id === store.activeId" class="tag pk">当前</span>
                <span v-else class="tag">未选中</span>
              </td>
              <td class="nowrap">
                <button
                  v-if="cluster.id !== store.activeId"
                  class="btn ghost sm"
                  @click="activate(cluster)"
                >切换</button>
                <button class="btn ghost sm" @click="openEdit(cluster)">编辑</button>
                <button class="btn ghost sm" style="color: var(--danger)" @click="deleting = cluster">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty">
        还没有配置平台。点「添加平台」填入 master 地址即可，例如 <span class="mono">192.168.46.23:7051,192.168.46.23:7151</span>。
      </div>
    </div>

    <div class="card">
      <div class="card-head"><div class="card-title">全局限制</div></div>
      <div class="card-body">
        <dl class="kv" v-if="limits">
          <dt>每页默认 / 上限</dt><dd>{{ limits.defaultLimit }} / {{ limits.maxLimit }} 行</dd>
          <dt>offset 上限</dt><dd>{{ limits.maxOffset }}</dd>
          <dt>单次扫描上限</dt><dd>{{ limits.maxScanRows }} 行</dd>
          <dt>批量写入</dt><dd>{{ limits.batchSize }} 行 / 批</dd>
          <dt>清空行数上限</dt><dd>{{ limits.truncateMaxRows }}</dd>
        </dl>
        <div class="hint" style="margin-top: 10px">
          这些是 application.yml 里的全局参数，对所有平台生效；每个平台的超时与 Web UI 端口在「编辑」里单独设置。
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head"><div class="card-title">使用须知</div></div>
      <div class="card-body">
        <ul style="margin: 0; padding-left: 18px; color: var(--text-2); line-height: 1.8">
          <li>平台配置保存在 <span class="mono">config/clusters.json</span>，不再依赖启动参数；删掉该文件会回落到 application.yml 的默认地址。</li>
          <li>每个平台独立建连、独立缓存：某个平台不可达只影响它自己，其它平台照常使用。</li>
          <li>本控制台无鉴权，只用于测试环境；默认监听 <span class="mono">0.0.0.0:8088</span>，可通过 <span class="mono">server.address</span> 限制来源。</li>
          <li>查询条件只支持 AND，不支持 OR；<span class="mono">NE</span> 与 <span class="mono">PREFIX</span> 的后缀部分在客户端过滤。</li>
          <li>批量写入采用 MANUAL_FLUSH，没有事务回滚语义，失败行会在结果里逐行报告。</li>
        </ul>
      </div>
    </div>

    <Dialog v-if="dialog.open" :title="isEdit ? '编辑平台' : '添加平台'" @close="dialog.open = false">
      <div v-if="error" class="banner error">{{ error }}</div>

      <div class="field">
        <label class="label">平台名称</label>
        <input v-model="dialog.name" class="input" placeholder="例如：测试集群 / 生产只读" />
      </div>

      <div class="field" style="margin-top: 10px">
        <label class="label">Master RPC 地址（逗号分隔）</label>
        <textarea
          v-model="dialog.masters"
          class="textarea"
          rows="3"
          placeholder="192.168.46.23:7051,192.168.46.23:7151,192.168.46.23:7251"
          spellcheck="false"
        ></textarea>
        <span class="hint">格式 host:port，端口为 master RPC 端口（不是 Web UI 端口）。</span>
      </div>

      <div class="row" style="margin-top: 10px">
        <div class="field grow">
          <label class="label">操作超时（毫秒，可空）</label>
          <input v-model.number="dialog.operationTimeoutMs" class="input" type="number" placeholder="默认 30000" />
        </div>
        <div class="field grow">
          <label class="label">DDL 超时（毫秒，可空）</label>
          <input v-model.number="dialog.adminTimeoutMs" class="input" type="number" placeholder="默认 60000" />
        </div>
      </div>

      <div class="row" style="margin-top: 12px; align-items: flex-end">
        <div class="field grow">
          <label class="label">master Web UI</label>
          <label class="checkbox" style="height: 28px">
            <input type="checkbox" v-model="dialog.webUiEnabled" />启用（角色 / 存活 / 表统计）
          </label>
        </div>
        <div class="field" style="width: 160px">
          <label class="label">端口偏移</label>
          <input v-model.number="dialog.webUiPortOffset" class="input" type="number" placeholder="1000" />
        </div>
      </div>

      <div class="row" style="margin-top: 14px; align-items: center">
        <button class="btn" @click="test">测试连接</button>
        <span v-if="probe" class="status">
          <span class="status-dot" :class="probe.ok ? 'ok' : 'bad'"></span>
          <span v-if="probe.ok">连通，{{ probe.tableCount }} 张表，{{ probe.elapsedMs }} ms</span>
          <span v-else>{{ probe.error }}（{{ probe.elapsedMs }} ms）</span>
        </span>
      </div>

      <template #foot>
        <button class="btn" @click="dialog.open = false" :disabled="busy">取消</button>
        <button class="btn primary" @click="save" :disabled="busy">
          <span v-if="busy" class="spinner"></span>{{ isEdit ? '保存' : '添加' }}
        </button>
      </template>
    </Dialog>

    <ConfirmDialog
      v-if="deleting"
      title="删除平台"
      :message="`将从清单中移除「${deleting.name}」。只删除连接配置，不会删除 Kudu 上的任何表和数据。`"
      :expect="deleting.name"
      confirm-text="删除平台"
      :busy="removing"
      @confirm="remove"
      @close="deleting = null"
    />
  </div>
</template>

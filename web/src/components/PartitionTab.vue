<script setup>
import { computed } from 'vue'
import CellValue from './CellValue.vue'

const props = defineProps({ detail: { type: Object, required: true } })

const partition = computed(() => props.detail.partition || {})
const hashPartitions = computed(() => partition.value.hashPartitions || [])
const rangeColumns = computed(() => partition.value.rangeColumns || [])
const rangeBounds = computed(() => partition.value.rangeBounds)
const columnMap = computed(() => {
  const map = {}
  ;(props.detail.columns || []).forEach((c) => {
    map[c.name] = c
  })
  return map
})

function typeOf(name) {
  return columnMap.value[name]?.type || ''
}
</script>

<template>
  <div>
    <div class="stats" style="margin-bottom: 14px">
      <div class="stat">
        <div class="stat-label">Hash 分区组</div>
        <div class="stat-value">{{ hashPartitions.length }}</div>
      </div>
      <div class="stat">
        <div class="stat-label">Range 分区列</div>
        <div class="stat-value small">{{ rangeColumns.join(', ') || '无' }}</div>
      </div>
      <div class="stat">
        <div class="stat-label">Range 分区数</div>
        <div class="stat-value">{{ rangeBounds ? rangeBounds.length : '—' }}</div>
      </div>
      <div class="stat">
        <div class="stat-label">Tablet 数量（推算）</div>
        <div class="stat-value">{{ partition.estimatedTablets ?? '—' }}</div>
        <div class="stat-note">hash 桶 × range 分区</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head"><div class="card-title">Hash 分区</div></div>
      <div class="card-body flush">
        <table class="grid" v-if="hashPartitions.length">
          <thead><tr><th>#</th><th>分区列</th><th class="num">Buckets</th><th class="num">Seed</th></tr></thead>
          <tbody>
            <tr v-for="(hash, index) in hashPartitions" :key="index">
              <td class="num muted">{{ index + 1 }}</td>
              <td class="mono">{{ hash.columns.join(', ') }}</td>
              <td class="num">{{ hash.buckets }}</td>
              <td class="num">{{ hash.seed }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">没有 hash 分区，整表按 range / 单分区组织。</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">Range 分区边界</div>
        <div class="card-sub">下界含、上界不含（客户端不暴露已有分区的 bound 类型，按 Kudu 默认语义展示）</div>
      </div>
      <div class="card-body flush">
        <table class="grid" v-if="rangeBounds && rangeBounds.length">
          <thead>
            <tr>
              <th>#</th>
              <th v-for="column in rangeColumns" :key="'l' + column">{{ column }} 下界</th>
              <th v-for="column in rangeColumns" :key="'u' + column">{{ column }} 上界</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(bound, index) in rangeBounds" :key="index">
              <td class="num muted">{{ index + 1 }}</td>
              <td v-for="column in rangeColumns" :key="'lv' + column">
                <span v-if="bound.lower === null || bound.lower[column] === null || bound.lower[column] === undefined" class="null">无界</span>
                <CellValue v-else :value="bound.lower[column]" :type="typeOf(column)" />
              </td>
              <td v-for="column in rangeColumns" :key="'uv' + column">
                <span v-if="bound.upper === null || bound.upper[column] === null || bound.upper[column] === undefined" class="null">无界</span>
                <CellValue v-else :value="bound.upper[column]" :type="typeOf(column)" />
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else-if="rangeBounds === null" class="empty">
          当前客户端版本无法读取已有表的 range 分区边界，仅能展示分区列。
        </div>
        <div v-else-if="rangeColumns.length" class="empty">没有读取到 range 分区边界。</div>
        <div v-else class="empty">该表没有使用 range 分区。</div>
      </div>
    </div>
  </div>
</template>

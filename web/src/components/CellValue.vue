<script setup>
import { computed } from 'vue'
import { cellDisplay } from '../format'

const props = defineProps({
  value: { default: null },
  type: { type: String, default: '' },
  maxWidth: { type: Number, default: 320 }
})

const display = computed(() => cellDisplay(props.value, props.type))
</script>

<template>
  <span v-if="display.isNull" class="null">NULL</span>
  <span
    v-else
    :class="{ mono: display.mono, num: display.numeric }"
    :title="display.title || display.text"
    :style="maxWidth ? { display: 'inline-block', maxWidth: maxWidth + 'px', overflow: 'hidden', textOverflow: 'ellipsis', verticalAlign: 'bottom' } : null"
  >{{ display.text }}</span>
</template>

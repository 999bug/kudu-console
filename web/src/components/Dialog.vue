<script setup>
defineProps({
  title: { type: String, default: '' },
  wide: { type: Boolean, default: false },
  closable: { type: Boolean, default: true }
})
const emit = defineEmits(['close'])
</script>

<template>
  <div class="overlay" @mousedown.self="closable && emit('close')">
    <div class="modal" :class="{ wide }">
      <div class="modal-head">
        <div class="modal-title">{{ title }}</div>
        <button v-if="closable" class="btn ghost sm modal-close" @click="emit('close')">关闭</button>
      </div>
      <div class="modal-body">
        <slot />
      </div>
      <div class="modal-foot">
        <slot name="foot" />
      </div>
    </div>
  </div>
</template>

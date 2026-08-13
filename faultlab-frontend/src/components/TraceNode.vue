<script setup>
defineProps({
  node: {
    type: Object,
    required: true,
  },
})

function formatValue(value) {
  if (value === null || value === undefined || value === '') return '-'
  return value
}

function formatDuration(value) {
  if (value === null || value === undefined || value === '') return '-'
  return `${value} ms`
}

function isErrorSpan(node) {
  return String(node.status || '').toUpperCase() === 'ERROR'
}
</script>

<template>
  <div class="trace-node">
    <div class="trace-row" :class="{ error: isErrorSpan(node) }">
      <strong>{{ node.operationName || 'unknown.operation' }}</strong>
      <span>{{ node.component || 'unknown' }}</span>
      <span class="trace-duration">{{ formatDuration(node.durationMs) }}</span>
      <span :class="['trace-status', String(node.status || '').toLowerCase()]">
        {{ formatValue(node.status) }}
      </span>
      <span>{{ formatValue(node.errorMessage || node.tags?.errorMessage || node.startTime) }}</span>
    </div>
    <div v-if="Array.isArray(node.children) && node.children.length" class="trace-children">
      <TraceNode
        v-for="child in node.children"
        :key="child.spanId || child.operationName"
        :node="child"
      />
    </div>
  </div>
</template>

<script setup>
defineProps({
  node: {
    type: Object,
    required: true,
  },
})
</script>

<template>
  <div class="trace-node">
    <div class="trace-row">
      <strong>{{ node.operationName || 'unknown.operation' }}</strong>
      <span>{{ node.component || 'unknown' }}</span>
      <span>{{ node.durationMs ?? '暂无数据' }} ms</span>
      <span :class="['trace-status', String(node.status || '').toLowerCase()]">
        {{ node.status || '暂无数据' }}
      </span>
      <span>{{ node.startTime || '暂无数据' }}</span>
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

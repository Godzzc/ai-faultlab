<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { requestJson } from '../api.js'

const props = defineProps({
  experiment: { type: Object, default: null },
  diagnosis: { type: Object, default: null },
  metrics: { type: Array, default: () => [] },
  initialReplayExperimentId: { type: String, default: '' },
})

const replayResponse = ref(null)
const replayExperiment = ref(null)
const validation = ref(null)
const confirmReplay = ref(false)
const replayLoading = ref(false)
const replayError = ref('')
const validationLoading = ref(false)
const validationError = ref('')

const plan = computed(() => props.diagnosis?.remediationPlan ?? null)
const actions = computed(() => Array.isArray(plan.value?.actions) ? plan.value.actions : [])
const expectedEffects = computed(() => Array.isArray(plan.value?.expectedEffects) ? plan.value.expectedEffects : [])
const planWarnings = computed(() => Array.isArray(plan.value?.warnings) ? plan.value.warnings : [])
const parameterPatch = computed(() => Object.assign(
  {},
  ...actions.value.map((action) => action?.parameterPatch ?? {}),
))
const originalParams = computed(() => props.experiment?.params ?? null)
const unchangedParams = computed(() => {
  if (!originalParams.value) return {}
  return Object.fromEntries(
    Object.entries(originalParams.value).filter(([key]) => !(key in parameterPatch.value)),
  )
})
const canReplay = computed(() => (
  plan.value?.status === 'PROPOSED'
  && plan.value?.scenarioCode === props.experiment?.scenarioCode
  && Object.keys(parameterPatch.value).length > 0
  && Boolean(originalParams.value)
))
const metricComparisons = computed(() => Array.isArray(validation.value?.metricComparisons)
  ? validation.value.metricComparisons
  : [])
const traceComparison = computed(() => validation.value?.traceComparison ?? null)
const operationRows = computed(() => {
  if (!traceComparison.value) return []
  const before = traceComparison.value.beforeOperationCounts ?? {}
  const after = traceComparison.value.afterOperationCounts ?? {}
  return [...new Set([...Object.keys(before), ...Object.keys(after)])]
    .sort()
    .map((operationName) => ({
      operationName,
      before: before[operationName] ?? 0,
      after: after[operationName] ?? 0,
    }))
})

function display(value, fallback = '暂无数据') {
  return value === null || value === undefined || value === '' ? fallback : String(value)
}

function displayJson(value) {
  if (!value || Object.keys(value).length === 0) return '无'
  return JSON.stringify(value, null, 2)
}

function displayPercent(value) {
  if (value === null || value === undefined) return '基线为 0，无法计算百分比'
  const numeric = Number(value)
  return Number.isFinite(numeric) ? `${numeric.toFixed(2)}%` : '无法计算'
}

function directionText(direction) {
  return {
    INCREASE: '预期上升',
    DECREASE: '预期下降',
    STABLE: '预期保持稳定',
  }[direction] ?? display(direction)
}

function matchText(comparison) {
  if (comparison?.status === 'INCONCLUSIVE' || comparison?.matchedExpectation == null) return '证据不足'
  return comparison.matchedExpectation ? '符合预期' : '未符合预期'
}

function validationStatusText(status) {
  return {
    VERIFIED: '已验证',
    PARTIALLY_VERIFIED: '部分验证',
    NOT_VERIFIED: '未验证通过',
    INCONCLUSIVE: '证据不足，无法判断',
  }[status] ?? display(status)
}

function planStatusText(status) {
  return {
    PROPOSED: '已提出，可验证',
    UNSUPPORTED: '当前场景不支持',
    INVALID: '方案未通过策略校验',
  }[status] ?? display(status)
}

function persistRelation(replayExperimentId) {
  if (!replayExperimentId || !props.experiment?.experimentId || typeof window === 'undefined') return
  const url = new URL(window.location.href)
  url.searchParams.set('experimentId', props.experiment.experimentId)
  url.searchParams.set('replayExperimentId', replayExperimentId)
  window.history.replaceState({}, '', url)
}

async function executeReplay() {
  if (!canReplay.value || replayLoading.value) return
  replayLoading.value = true
  replayError.value = ''
  validationError.value = ''
  try {
    const originalExperimentId = props.experiment.experimentId
    replayResponse.value = await requestJson(
      `/api/experiments/${encodeURIComponent(originalExperimentId)}/remediation-replay`,
      {
        method: 'POST',
        body: JSON.stringify({
          planId: plan.value.planId,
          scenarioCode: plan.value.scenarioCode,
          parameterPatch: parameterPatch.value,
        }),
      },
    )
    confirmReplay.value = false
    persistRelation(replayResponse.value?.replayExperimentId)
    await loadReplayAndValidation(replayResponse.value?.replayExperimentId)
  } catch (error) {
    replayError.value = error?.message || 'Replay 执行失败'
  } finally {
    replayLoading.value = false
  }
}

async function loadReplayAndValidation(replayExperimentId) {
  if (!replayExperimentId || validationLoading.value) return
  validationLoading.value = true
  validationError.value = ''
  validation.value = null
  try {
    replayExperiment.value = await requestJson(
      `/api/experiments/${encodeURIComponent(replayExperimentId)}`,
    )
    if (
      replayExperiment.value?.sourceExperimentId
      && props.experiment?.experimentId
      && replayExperiment.value.sourceExperimentId !== props.experiment.experimentId
    ) {
      throw new Error('Replay 实验关联的原始 experimentId 与当前实验不一致')
    }
    validation.value = await requestJson(
      `/api/experiments/${encodeURIComponent(replayExperimentId)}/remediation-validation`,
    )
    persistRelation(replayExperimentId)
  } catch (error) {
    validationError.value = error?.message || 'Validation 加载失败'
  } finally {
    validationLoading.value = false
  }
}

watch(
  () => props.initialReplayExperimentId,
  (replayExperimentId) => {
    if (replayExperimentId && replayExperimentId !== replayExperiment.value?.experimentId) {
      loadReplayAndValidation(replayExperimentId)
    }
  },
)

onMounted(() => {
  if (props.initialReplayExperimentId) {
    loadReplayAndValidation(props.initialReplayExperimentId)
  }
})
</script>

<template>
  <section class="remediation-shell" data-testid="remediation-workbench">
    <div class="workbench-title">
      <div>
        <p class="eyebrow">Remediation Workbench</p>
        <h2>修复验证工作台</h2>
      </div>
      <span class="status-pill" :class="String(validation?.status || plan?.status || 'idle').toLowerCase()">
        {{ validation ? validationStatusText(validation.status) : planStatusText(plan?.status) }}
      </span>
    </div>

    <section class="workbench-block">
      <div class="block-heading"><span>1</span><h3>Experiment Overview</h3></div>
      <div v-if="experiment" class="summary-grid">
        <div><small>originalExperimentId</small><strong data-testid="original-id">{{ experiment.experimentId }}</strong></div>
        <div><small>scenarioCode</small><strong>{{ experiment.scenarioCode }}</strong></div>
        <div><small>status</small><strong>{{ experiment.status }}</strong></div>
        <div><small>traceId</small><strong>{{ display(experiment.traceId) }}</strong></div>
      </div>
      <p v-else class="empty small">原始实验不存在或尚未加载。</p>
      <details v-if="originalParams" class="json-details">
        <summary>原始实验参数</summary>
        <pre>{{ displayJson(originalParams) }}</pre>
      </details>
      <p v-else-if="experiment" class="state-note warning">该实验没有可用的持久化参数，无法安全执行 Replay。</p>
      <p class="state-note">Metrics：{{ metrics.length ? `${metrics.length} 项已加载` : '当前缺少 Metrics' }}；Trace 可在上方 Trace 区域查看。</p>
    </section>

    <section class="workbench-block">
      <div class="block-heading"><span>2</span><h3>Diagnosis &amp; Evidence</h3></div>
      <p v-if="diagnosis?.summary" class="diagnosis-summary">{{ diagnosis.summary }}</p>
      <p v-else class="empty small">尚无 AI 诊断。请先生成或刷新诊断报告。</p>
      <div v-if="diagnosis" class="evidence-columns">
        <div><small>Evidence</small><ul v-if="diagnosis.evidence?.length"><li v-for="item in diagnosis.evidence" :key="item">{{ item }}</li></ul><p v-else>暂无</p></div>
        <div><small>Runbook References</small><ul v-if="diagnosis.runbookReferences?.length"><li v-for="item in diagnosis.runbookReferences" :key="`${item.docId}-${item.section}`">{{ item.docId }} / {{ item.section }}</li></ul><p v-else>暂无</p></div>
      </div>
    </section>

    <section class="workbench-block">
      <div class="block-heading"><span>3</span><h3>Proposed Remediation</h3></div>
      <template v-if="plan">
        <div class="plan-meta">
          <span class="status-pill" :class="String(plan.status).toLowerCase()">{{ planStatusText(plan.status) }}</span>
          <code>{{ plan.planId }}</code>
          <code>{{ plan.scenarioCode }}</code>
        </div>
        <div v-if="actions.length" class="action-list">
          <article v-for="action in actions" :key="action.actionType">
            <strong>{{ action.actionType }}</strong>
            <p>{{ display(action.description) }}</p>
            <small>{{ display(action.rationale) }}</small>
            <pre>{{ displayJson(action.parameterPatch) }}</pre>
          </article>
        </div>
        <p v-else class="state-note warning">该方案没有可执行动作。</p>
        <div v-if="expectedEffects.length" class="effect-list">
          <div v-for="effect in expectedEffects" :key="effect.metricName">
            <code>{{ effect.metricName }}</code><strong>{{ directionText(effect.direction) }}</strong><span>{{ effect.description }}</span>
          </div>
        </div>
        <ul v-if="planWarnings.length" class="warning-list"><li v-for="warning in planWarnings" :key="warning">{{ warning }}</li></ul>
      </template>
      <p v-else class="empty small">诊断报告中尚无结构化 RemediationPlan；自然语言建议不会被当作可执行方案。</p>
    </section>

    <section class="workbench-block">
      <div class="block-heading"><span>4</span><h3>Replay Execution</h3></div>
      <div v-if="confirmReplay" class="confirmation" data-testid="replay-confirmation">
        <p>本次操作会创建新的 Replay 实验，不会修改原始实验，也不会触发生产环境修复。</p>
        <dl><dt>原始 experimentId</dt><dd>{{ experiment?.experimentId }}</dd><dt>scenarioCode</dt><dd>{{ plan?.scenarioCode }}</dd></dl>
        <div class="patch-columns"><div><small>待应用 remediation patch</small><pre>{{ displayJson(parameterPatch) }}</pre></div><div><small>保持不变的实验条件</small><pre>{{ displayJson(unchangedParams) }}</pre></div></div>
        <div class="replay-actions">
          <button type="button" class="secondary" :disabled="replayLoading" @click="confirmReplay = false">取消</button>
          <button type="button" data-testid="confirm-replay" :disabled="replayLoading" @click="executeReplay">{{ replayLoading ? 'Replay 执行中...' : '确认创建 Replay' }}</button>
        </div>
      </div>
      <button v-else type="button" data-testid="prepare-replay" :disabled="!canReplay || replayLoading" @click="confirmReplay = true">
        {{ replayLoading ? 'Replay 执行中...' : '执行修复验证' }}
      </button>
      <p v-if="plan && plan.status !== 'PROPOSED'" class="state-note warning">{{ planStatusText(plan.status) }}，Replay 已禁用。</p>
      <p v-if="replayError" class="inline-error" data-testid="replay-error">Replay 失败：{{ replayError }}</p>
      <div v-if="replayResponse" class="success-box">
        <span>Replay 已创建</span><strong data-testid="replay-id">{{ replayResponse.replayExperimentId }}</strong><small>status: {{ replayResponse.status }}</small>
      </div>
      <p v-if="replayResponse && !validation && !validationLoading && !validationError" class="state-note">Replay 成功，尚未获取 Validation。</p>
    </section>

    <section class="workbench-block">
      <div class="block-heading"><span>5</span><h3>Before / After Comparison</h3></div>
      <p v-if="validationLoading" class="loading-note">正在加载 Replay 实验和后端 Validation Report...</p>
      <p v-if="validationError" class="inline-error" data-testid="validation-error">Validation 加载失败：{{ validationError }}</p>
      <div v-if="metricComparisons.length" class="comparison-table-wrap">
        <table>
          <thead><tr><th>metricName</th><th>实际 Before</th><th>实际 After</th><th>absoluteChange</th><th>changePercent</th><th>预期方向</th><th>判断</th></tr></thead>
          <tbody><tr v-for="item in metricComparisons" :key="item.metricName"><td>{{ item.metricName }}</td><td>{{ display(item.beforeValue) }}</td><td>{{ display(item.afterValue) }}</td><td>{{ display(item.absoluteChange) }}</td><td>{{ displayPercent(item.changePercent) }}</td><td>{{ directionText(item.expectedDirection) }}</td><td><span class="comparison-result" :class="String(item.status).toLowerCase()">{{ matchText(item) }}</span><small v-if="item.note">{{ item.note }}</small></td></tr></tbody>
        </table>
      </div>
      <p v-else-if="validation" class="state-note warning">Validation 未返回可比较的 Metrics，当前证据不足。</p>
      <p v-else-if="!validationLoading && !validationError" class="empty small">尚无 Before / After 数据。</p>
    </section>

    <section class="workbench-block">
      <div class="block-heading"><span>6</span><h3>Validation Report</h3></div>
      <template v-if="validation">
        <div class="validation-header">
          <span class="status-pill" :class="String(validation.status).toLowerCase()" data-testid="validation-status">{{ validationStatusText(validation.status) }}</span>
          <p>{{ validation.summary }}</p>
        </div>
        <div v-if="validation.status === 'INCONCLUSIVE'" class="state-note warning">证据不足，不能判定修复成功；这与“修复未生效”不同。</div>
        <div class="summary-grid validation-grid">
          <div><small>originalExperimentId</small><strong>{{ validation.originalExperimentId }}</strong></div><div><small>replayExperimentId</small><strong>{{ validation.replayExperimentId }}</strong></div><div><small>scenarioCode</small><strong>{{ validation.scenarioCode }}</strong></div><div><small>validatedAt</small><strong>{{ display(validation.validatedAt) }}</strong></div><div><small>totalExpectedEffects</small><strong>{{ validation.totalExpectedEffects }}</strong></div><div><small>matchedEffects</small><strong>{{ validation.matchedEffects }}</strong></div><div><small>unmatchedEffects</small><strong>{{ validation.unmatchedEffects }}</strong></div><div><small>inconclusiveEffects</small><strong>{{ validation.inconclusiveEffects }}</strong></div>
        </div>
        <details class="json-details"><summary>后端确认的 appliedPatch</summary><pre>{{ displayJson(validation.appliedPatch) }}</pre></details>
        <div v-if="traceComparison" class="trace-comparison">
          <h4>Trace Comparison（辅助证据）</h4>
          <div class="summary-grid"><div><small>spanCount</small><strong>{{ traceComparison.beforeSpanCount }} → {{ traceComparison.afterSpanCount }}</strong></div><div><small>errorSpanCount</small><strong>{{ traceComparison.beforeErrorSpanCount }} → {{ traceComparison.afterErrorSpanCount }}</strong></div><div><small>totalDurationMs</small><strong>{{ traceComparison.beforeTotalDurationMs }} → {{ traceComparison.afterTotalDurationMs }}</strong></div></div>
          <div v-if="operationRows.length" class="operation-list"><div v-for="row in operationRows" :key="row.operationName"><code>{{ row.operationName }}</code><span>{{ row.before }} → {{ row.after }}</span></div></div>
        </div>
      </template>
      <p v-else-if="!validationLoading" class="empty small">尚无 Validation Report。</p>
    </section>
  </section>
</template>

<style scoped>
.remediation-shell{display:grid;gap:14px;margin-top:18px;padding-top:18px;border-top:1px solid var(--border)}.workbench-title,.validation-header,.plan-meta,.replay-actions{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}.workbench-block{display:grid;gap:12px;padding:14px;border:1px solid var(--border);border-radius:8px;background:#fff}.block-heading{display:flex;align-items:center;gap:9px}.block-heading span{display:grid;place-items:center;width:24px;height:24px;border-radius:6px;background:#e0e7ff;color:#4338ca;font:700 12px var(--mono)}.block-heading h3{margin:0;color:#0f172a;font-size:14px}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}.summary-grid>div,.evidence-columns>div{padding:10px;border:1px solid var(--border);border-radius:7px;background:var(--panel-soft)}small{display:block;color:var(--muted);font-family:var(--mono);font-size:11px}strong{overflow-wrap:anywhere}.status-pill{padding:5px 9px;border-radius:999px;background:#f1f5f9;color:#475569;font-size:12px;font-weight:700}.status-pill.proposed,.status-pill.verified{background:#dcfce7;color:#166534}.status-pill.partially_verified{background:#fef3c7;color:#92400e}.status-pill.not_verified,.status-pill.invalid{background:#fee2e2;color:#991b1b}.status-pill.inconclusive,.status-pill.unsupported{background:#fff7ed;color:#9a3412}.json-details summary{cursor:pointer;color:#334155;font-size:12px;font-weight:700}.json-details pre,.action-list pre,.patch-columns pre{margin:8px 0 0;padding:10px;border-radius:7px;background:#0f172a;color:#e2e8f0;overflow:auto;font:12px/1.5 var(--mono);white-space:pre-wrap}.state-note{padding:9px 10px;border-radius:7px;background:#eff6ff;color:#1d4ed8;font-size:12px}.state-note.warning{background:#fffbeb;color:#92400e}.diagnosis-summary{padding:11px;border-left:3px solid #7c3aed;background:#f5f3ff;color:#4c1d95}.evidence-columns,.patch-columns{display:grid;grid-template-columns:1fr 1fr;gap:10px}.evidence-columns ul{margin-top:7px}.action-list{display:grid;gap:8px}.action-list article{padding:11px;border:1px solid #c7d2fe;border-radius:7px;background:#f8faff}.action-list p{margin:5px 0;color:#334155}.effect-list{display:grid;gap:7px}.effect-list>div{display:grid;grid-template-columns:minmax(180px,1fr) 120px 2fr;gap:8px;padding:8px;border-bottom:1px solid var(--border);font-size:12px}.warning-list{color:#92400e}.confirmation{display:grid;gap:12px;padding:12px;border:1px solid #fbbf24;border-radius:7px;background:#fffbeb}.confirmation dl{display:grid;grid-template-columns:160px 1fr;gap:5px;margin:0;font-size:12px}.confirmation dd{margin:0;font-family:var(--mono);overflow-wrap:anywhere}.replay-actions{justify-content:flex-end}.remediation-shell button{min-height:38px;padding:8px 12px;border-radius:7px;background:#4338ca;color:#fff;font-weight:700}.remediation-shell button.secondary{background:#fff;color:#475569}.inline-error{padding:10px;border:1px solid #fecaca;border-radius:7px;background:#fef2f2;color:#b91c1c}.success-box{display:grid;gap:4px;padding:11px;border:1px solid #86efac;border-radius:7px;background:#f0fdf4}.success-box strong{font-family:var(--mono);color:#166534}.comparison-table-wrap{overflow-x:auto}.comparison-table-wrap table{min-width:980px}.comparison-result{display:block;font-weight:700}.comparison-result.matched{color:#15803d}.comparison-result.unmatched{color:#b91c1c}.comparison-result.inconclusive{color:#9a3412}.comparison-result+small{margin-top:4px}.validation-header{justify-content:flex-start}.validation-grid{grid-template-columns:repeat(4,minmax(0,1fr))}.trace-comparison{display:grid;gap:9px;padding-top:4px}.trace-comparison h4{margin:0}.operation-list{display:grid;gap:5px}.operation-list>div{display:flex;justify-content:space-between;gap:12px;padding:6px 8px;background:var(--panel-soft);font-size:12px}@media(max-width:900px){.summary-grid,.validation-grid{grid-template-columns:1fr 1fr}.effect-list>div{grid-template-columns:1fr}.evidence-columns,.patch-columns{grid-template-columns:1fr}}@media(max-width:600px){.summary-grid,.validation-grid{grid-template-columns:1fr}.workbench-title{align-items:flex-start}.confirmation dl{grid-template-columns:1fr}.confirmation dd{margin-bottom:6px}}
</style>

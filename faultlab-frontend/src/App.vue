<script setup>
import { computed, reactive, ref } from 'vue'
import TraceNode from './components/TraceNode.vue'

const scenarios = [
  {
    code: 'MQ_BACKLOG',
    name: 'MQ 消息堆积',
    description: '模拟生产速度高于消费速度，观察队列积压与慢消费指标。',
    defaults: {
      messageCount: 10,
      consumerDelayMs: 1000,
    },
    fields: [
      { key: 'messageCount', label: '消息数量', min: 1 },
      { key: 'consumerDelayMs', label: '消费延迟 ms', min: 0 },
    ],
  },
  {
    code: 'THREAD_POOL_SATURATION',
    name: '线程池饱和',
    description: '向受控线程池提交长任务，观察活跃线程、队列和拒绝任务。',
    defaults: {
      taskCount: 30,
      taskSleepMs: 3000,
    },
    fields: [
      { key: 'taskCount', label: '任务数量', min: 1 },
      { key: 'taskSleepMs', label: '任务耗时 ms', min: 0 },
    ],
  },
  {
    code: 'IDEMPOTENCY_CONFLICT',
    name: '幂等冲突',
    description: '模拟相同 Idempotency-Key 的重复提交与 requestHash 冲突。',
    defaults: {
      requestCount: 30,
      duplicateCount: 20,
      conflictCount: 8,
      processingDelayMs: 1000,
    },
    fields: [
      { key: 'requestCount', label: '请求总数', min: 1 },
      { key: 'duplicateCount', label: '重复请求数', min: 0 },
      { key: 'conflictCount', label: '冲突请求数', min: 0 },
      { key: 'processingDelayMs', label: '处理延迟 ms', min: 0 },
    ],
  },
  {
    code: 'CACHE_PENETRATION',
    name: '缓存穿透',
    alias: 'Cache Penetration',
    description: '大量请求查询不存在的数据，缓存和数据库都没有命中，导致请求持续打到 DB。可通过空值缓存、布隆过滤器或非法 key 限流进行治理。',
    defaults: {
      requestCount: 100,
      invalidKeyRatio: 0.8,
      enableNullCache: false,
      enableBloomFilter: false,
      dbDelayMs: 20,
    },
    fields: [
      { key: 'requestCount', label: '请求总数', type: 'number', min: 1 },
      { key: 'invalidKeyRatio', label: '非法 key 比例', type: 'number', min: 0, max: 1, step: 0.1 },
      { key: 'dbDelayMs', label: 'DB 延迟 ms', type: 'number', min: 0 },
      { key: 'enableNullCache', label: '启用空值缓存', type: 'boolean' },
      { key: 'enableBloomFilter', label: '启用布隆过滤器', type: 'boolean' },
    ],
  },
  {
    code: 'CACHE_BREAKDOWN',
    name: '缓存击穿',
    alias: 'Cache Breakdown',
    description: '热点 key 过期后，大量并发请求同时穿透到 DB，并重复触发缓存重建。可通过互斥锁、逻辑过期、热点 key 永不过期或异步刷新治理。',
    defaults: {
      requestCount: 100,
      hotKey: 'hot:item:1',
      concurrency: 20,
      rebuildDelayMs: 100,
      enableMutex: false,
      enableLogicalExpire: false,
    },
    fields: [
      { key: 'requestCount', label: '请求总数', type: 'number', min: 1 },
      { key: 'hotKey', label: '热点 key', type: 'text' },
      { key: 'concurrency', label: '并发数', type: 'number', min: 1 },
      { key: 'rebuildDelayMs', label: '重建延迟 ms', type: 'number', min: 0 },
      { key: 'enableMutex', label: '启用互斥锁', type: 'boolean' },
      { key: 'enableLogicalExpire', label: '启用逻辑过期', type: 'boolean' },
    ],
  },
  {
    code: 'CACHE_AVALANCHE',
    name: '缓存雪崩',
    alias: 'Cache Avalanche',
    description: '大量 key 同时过期或 Redis 短时间不可用，导致请求大面积打到 DB。可通过 TTL 随机化、缓存预热、多级缓存、限流降级和本地缓存兜底治理。',
    defaults: {
      keyCount: 50,
      requestCount: 200,
      sameTtl: true,
      enableTtlJitter: false,
      simulateRedisDown: false,
      enableFallback: false,
      dbDelayMs: 20,
    },
    fields: [
      { key: 'keyCount', label: '缓存 key 数', type: 'number', min: 1 },
      { key: 'requestCount', label: '请求总数', type: 'number', min: 1 },
      { key: 'dbDelayMs', label: 'DB 延迟 ms', type: 'number', min: 0 },
      { key: 'sameTtl', label: '相同 TTL', type: 'boolean' },
      { key: 'enableTtlJitter', label: '启用 TTL 随机化', type: 'boolean' },
      { key: 'simulateRedisDown', label: '模拟 Redis 不可用', type: 'boolean' },
      { key: 'enableFallback', label: '启用本地兜底', type: 'boolean' },
    ],
  },
]

const cacheMetricNames = {
  CACHE_PENETRATION: [
    'cache.request.count',
    'cache.miss.rate',
    'cache.db.query.count',
    'cache.invalid.key.count',
    'cache.null.cache.write.count',
    'cache.bloom.reject.count',
  ],
  CACHE_BREAKDOWN: [
    'cache.hot.key.request.count',
    'cache.hot.key.miss.count',
    'cache.db.query.count',
    'cache.rebuild.count',
    'cache.lock.acquire.count',
    'cache.lock.fail.count',
  ],
  CACHE_AVALANCHE: [
    'cache.key.count',
    'cache.expired.key.count',
    'cache.unavailable.count',
    'cache.miss.rate',
    'cache.db.query.count',
    'cache.fallback.count',
    'cache.request.error.count',
  ],
}

const selectedScenarioCode = ref(scenarios[0].code)
const params = reactive({ ...scenarios[0].defaults })
const experiment = ref(null)
const metrics = ref([])
const diagnosisReport = ref(null)
const ruleDiagnosis = ref(null)
const aiDiagnosis = ref(null)
const traceTree = ref(null)
const currentExperimentId = ref('')
const loading = reactive({
  start: false,
  refresh: false,
  diagnosis: false,
  ai: false,
})
const errorMessage = ref('')
const aiError = ref('')

const evaluationForm = reactive({
  retriever: 'all',
  topK: 3,
  report: true,
})
const evaluationLoading = ref(false)
const evaluationError = ref('')
const evaluationResult = ref(null)

const ragDebugCases = {
  mq_backlog_core_metrics: {
    experiment: {
      experimentId: 'mq_backlog_core_metrics',
      scenarioCode: 'MQ_BACKLOG',
      status: 'EVALUATING',
      traceId: 'trace_mq_backlog_core_metrics',
    },
    metrics: [
      { metricName: 'publishCount', metricValue: '10', metricUnit: 'count', component: 'RabbitMQ' },
      { metricName: 'consumeCount', metricValue: '1', metricUnit: 'count', component: 'RabbitMQ' },
      { metricName: 'avgConsumeMs', metricValue: '5000', metricUnit: 'ms', component: 'RabbitMQ' },
    ],
    traceTree: { traceId: 'trace_mq_backlog_core_metrics', roots: [] },
    ruleResult: {
      experimentId: 'mq_backlog_core_metrics',
      faultType: 'MQ_BACKLOG',
      faultName: 'MQ backlog',
      confidence: 0.85,
      matched: true,
      reason: 'publishCount is higher than consumeCount and avgConsumeMs is high.',
      evidence: ['publishCount=10', 'consumeCount=1', 'backlogCount=9', 'avgConsumeMs=5000'],
      suggestions: ['Increase consumer concurrency'],
    },
  },
  thread_pool_rejection: {
    experiment: {
      experimentId: 'thread_pool_rejection',
      scenarioCode: 'THREAD_POOL_SATURATION',
      status: 'EVALUATING',
      traceId: 'trace_thread_pool_rejection',
    },
    metrics: [
      { metricName: 'activeThreadCount', metricValue: '16', metricUnit: 'count', component: 'Executor' },
      { metricName: 'maximumPoolSize', metricValue: '16', metricUnit: 'count', component: 'Executor' },
      { metricName: 'rejectedTaskCount', metricValue: '3', metricUnit: 'count', component: 'Executor' },
    ],
    traceTree: { traceId: 'trace_thread_pool_rejection', roots: [] },
    ruleResult: {
      experimentId: 'thread_pool_rejection',
      faultType: 'THREAD_POOL_SATURATION',
      faultName: 'Thread pool saturation',
      confidence: 0.88,
      matched: true,
      reason: 'activeThreadCount reaches maximumPoolSize and rejectedTaskCount is greater than zero.',
      evidence: ['activeThreadCount=16', 'maximumPoolSize=16', 'rejectedTaskCount=3'],
      suggestions: ['Check queue size and reject policy'],
    },
  },
  idempotency_request_hash_conflict: {
    experiment: {
      experimentId: 'idempotency_request_hash_conflict',
      scenarioCode: 'IDEMPOTENCY_CONFLICT',
      status: 'EVALUATING',
      traceId: 'trace_idempotency_request_hash_conflict',
    },
    metrics: [
      { metricName: 'requestCount', metricValue: '5', metricUnit: 'count', component: 'Redis' },
      { metricName: 'hashMismatchCount', metricValue: '1', metricUnit: 'count', component: 'Redis' },
      { metricName: 'conflictCount', metricValue: '1', metricUnit: 'count', component: 'Redis' },
    ],
    traceTree: { traceId: 'trace_idempotency_request_hash_conflict', roots: [] },
    ruleResult: {
      experimentId: 'idempotency_request_hash_conflict',
      faultType: 'IDEMPOTENCY_CONFLICT',
      faultName: 'Idempotency conflict',
      confidence: 0.9,
      matched: true,
      reason: 'The same Idempotency-Key maps to different requestHash values.',
      evidence: ['requestCount=5', 'hashMismatchCount=1', 'conflictCount=1'],
      suggestions: ['Reject conflicting request hashes'],
    },
  },
}
const debugForm = reactive({
  caseId: 'mq_backlog_core_metrics',
  topK: 3,
  includeContent: false,
})
const debugLoading = ref(false)
const debugError = ref('')
const debugResult = ref(null)

const selectedScenario = computed(() =>
  scenarios.find((scenario) => scenario.code === selectedScenarioCode.value) ?? scenarios[0],
)

const traceRoots = computed(() => {
  return Array.isArray(traceTree.value?.roots) ? traceTree.value.roots : []
})

const evaluationSummaries = computed(() => {
  if (!evaluationResult.value) return []
  const summaries = evaluationResult.value.summaries ?? [evaluationResult.value]
  return summaries.filter(Boolean)
})

const activeScenarioCode = computed(() => experiment.value?.scenarioCode || selectedScenarioCode.value)

const cacheMetricHighlights = computed(() => {
  const names = cacheMetricNames[activeScenarioCode.value]
  if (!names || metrics.value.length === 0) return []
  return names.map((name) => {
    const metric = metrics.value.find((item) => item.metricName === name)
    return {
      name,
      value: metric?.metricValue,
      unit: metric?.metricUnit,
    }
  })
})

function selectScenario(code) {
  selectedScenarioCode.value = code
  Object.keys(params).forEach((key) => delete params[key])
  Object.assign(params, selectedScenario.value.defaults)
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, {
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers ?? {}),
    },
    ...options,
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(payload?.message || `请求失败：${response.status}`)
  }
  if (payload && typeof payload.code === 'number' && payload.code !== 0) {
    throw new Error(payload.message || '请求失败')
  }
  return payload?.data ?? payload ?? null
}

async function runRagEvaluation() {
  evaluationLoading.value = true
  evaluationError.value = ''
  evaluationResult.value = null
  try {
    evaluationResult.value = await requestJson('/ai/runbooks/evaluate', {
      method: 'POST',
      body: JSON.stringify({
        retriever: evaluationForm.retriever,
        topK: Number(evaluationForm.topK) || 3,
        report: evaluationForm.report,
      }),
    })
  } catch (error) {
    evaluationError.value = error.message || 'RAG evaluation failed'
  } finally {
    evaluationLoading.value = false
  }
}

async function runRagDebug() {
  debugLoading.value = true
  debugError.value = ''
  debugResult.value = null
  try {
    const selectedCase = ragDebugCases[debugForm.caseId]
    debugResult.value = await requestJson('/ai/runbooks/retrieve/debug', {
      method: 'POST',
      body: JSON.stringify({
        ...selectedCase,
        topK: Number(debugForm.topK) || 3,
        includeContent: debugForm.includeContent,
      }),
    })
  } catch (error) {
    debugError.value = error.message || 'RAG retrieval debug failed'
  } finally {
    debugLoading.value = false
  }
}

async function fetchTraceTree(traceId) {
  if (!traceId) {
    traceTree.value = null
    return
  }
  traceTree.value = await requestJson(`/api/traces/${encodeURIComponent(traceId)}`)
}

async function startExperiment() {
  loading.start = true
  errorMessage.value = ''
  try {
    const data = await requestJson('/api/experiments/start', {
      method: 'POST',
      body: JSON.stringify({
        scenarioCode: selectedScenarioCode.value,
        params: normalizedParams(),
      }),
    })
    experiment.value = data
    currentExperimentId.value = data?.experimentId || ''
    ruleDiagnosis.value = null
    aiDiagnosis.value = null
    aiError.value = ''
    diagnosisReport.value = null
    metrics.value = []
    traceTree.value = null
    await refreshExperimentData()
  } catch (error) {
    errorMessage.value = error.message || '启动演练失败'
  } finally {
    loading.start = false
  }
}

async function refreshExperimentData() {
  if (!currentExperimentId.value) {
    errorMessage.value = '请先启动实验，或输入 experimentId'
    return
  }
  loading.refresh = true
  errorMessage.value = ''
  try {
    const [detail, metricList, report] = await Promise.all([
      requestJson(`/api/experiments/${encodeURIComponent(currentExperimentId.value)}`),
      requestJson(`/api/experiments/${encodeURIComponent(currentExperimentId.value)}/metrics`),
      requestJson(`/api/diagnosis/${encodeURIComponent(currentExperimentId.value)}`),
    ])
    experiment.value = detail
    metrics.value = Array.isArray(metricList) ? metricList : []
    diagnosisReport.value = report
    ruleDiagnosis.value = parseRuleDiagnosis(report?.ruleResultJson) ?? ruleDiagnosis.value
    applyAiReport(report?.aiReportJson)
    await fetchTraceTree(detail?.traceId || experiment.value?.traceId)
  } catch (error) {
    errorMessage.value = error.message || '刷新实验数据失败'
  } finally {
    loading.refresh = false
  }
}

async function runRuleDiagnosis() {
  if (!currentExperimentId.value) {
    errorMessage.value = '请先启动实验，或输入 experimentId'
    return
  }
  loading.diagnosis = true
  errorMessage.value = ''
  try {
    ruleDiagnosis.value = await requestJson(
      `/api/diagnosis/${encodeURIComponent(currentExperimentId.value)}/rule`,
      { method: 'POST' },
    )
    await refreshExperimentData()
  } catch (error) {
    errorMessage.value = error.message || '执行规则诊断失败'
  } finally {
    loading.diagnosis = false
  }
}

async function generateAiDiagnosis() {
  if (!currentExperimentId.value) {
    aiError.value = '请先启动实验'
    return
  }
  loading.ai = true
  aiError.value = ''
  try {
    aiDiagnosis.value = await requestJson(
      `/api/diagnosis/${encodeURIComponent(currentExperimentId.value)}/ai/generate`,
      { method: 'POST' },
    )
    await refreshExperimentData()
  } catch (error) {
    aiError.value = error.message || '生成 AI 诊断报告失败'
  } finally {
    loading.ai = false
  }
}

function normalizedParams() {
  return Object.fromEntries(
    selectedScenario.value.fields.map((field) => {
      const value = params[field.key]
      if (field.type === 'boolean') return [field.key, Boolean(value)]
      if (field.type === 'text') return [field.key, String(value ?? '').trim()]
      return [field.key, Number(value)]
    }),
  )
}

function parseRuleDiagnosis(ruleResultJson) {
  if (!ruleResultJson) return null
  try {
    return JSON.parse(ruleResultJson)
  } catch {
    return null
  }
}

function applyAiReport(aiReportJson) {
  const parsed = parseAiDiagnosis(aiReportJson)
  if (parsed === 'PARSE_ERROR') {
    aiDiagnosis.value = null
    aiError.value = 'AI 报告解析失败'
    return
  }
  aiDiagnosis.value = parsed
  if (parsed) {
    aiError.value = ''
  }
}

function parseAiDiagnosis(aiReportJson) {
  if (!aiReportJson) return null
  if (typeof aiReportJson === 'object') return aiReportJson
  if (typeof aiReportJson !== 'string') return null
  try {
    return JSON.parse(aiReportJson)
  } catch {
    return 'PARSE_ERROR'
  }
}

function formatValue(value) {
  if (value === null || value === undefined || value === '') return '暂无数据'
  return value
}

function formatBoolean(value) {
  if (value === true) return 'true'
  if (value === false) return 'false'
  return '暂无数据'
}
function formatMetric(value) {
  if (typeof value !== 'number') return formatValue(value)
  return value.toFixed(4)
}

function formatRefs(items) {
  if (!Array.isArray(items) || items.length === 0) return 'None'
  return items
    .map((item) => `${item.docId || '-'}#${item.section || '-'}`)
    .join(', ')
}

function formatMetadata(metadata) {
  if (!metadata || Object.keys(metadata).length === 0) return '{}'
  return JSON.stringify(metadata, null, 2)
}
</script>

<template>
  <main class="dashboard">
    <header class="topbar">
      <div>
        <p class="eyebrow">Fault Simulation Dashboard</p>
        <h1>AI FaultLab 故障演练与智能诊断平台</h1>
      </div>
      <div class="status-strip">
        <span>Backend</span>
        <strong>/api</strong>
      </div>
    </header>

    <section v-if="errorMessage" class="alert">
      {{ errorMessage }}
    </section>

    <section class="layout">
      <aside class="panel control-panel">
        <div class="section-heading">
          <h2>场景选择</h2>
          <span>{{ selectedScenarioCode }}</span>
        </div>

        <div class="scenario-list">
          <button
            v-for="scenario in scenarios"
            :key="scenario.code"
            class="scenario-button"
            :class="{ active: selectedScenarioCode === scenario.code }"
            type="button"
            @click="selectScenario(scenario.code)"
          >
            <strong>{{ scenario.name }}</strong>
            <small>{{ scenario.alias ? `${scenario.alias} / ${scenario.code}` : scenario.code }}</small>
            <span>{{ scenario.description }}</span>
          </button>
        </div>

        <div class="section-heading compact">
          <h2>参数配置</h2>
        </div>
        <div class="form-grid">
          <label
            v-for="field in selectedScenario.fields"
            :key="field.key"
            class="field"
            :class="{ 'check-field form-check-field': field.type === 'boolean' }"
          >
            <template v-if="field.type === 'boolean'">
              <input v-model="params[field.key]" type="checkbox" />
              <span>{{ field.label }}</span>
            </template>
            <template v-else>
              <span>{{ field.label }}</span>
              <input
                v-model="params[field.key]"
                :type="field.type === 'text' ? 'text' : 'number'"
                :min="field.min"
                :max="field.max"
                :step="field.step ?? 1"
              />
            </template>
          </label>
        </div>

        <div v-if="selectedScenario.alias" class="scenario-note">
          {{ selectedScenario.description }}
        </div>

        <label class="field">
          <span>当前 experimentId</span>
          <input v-model.trim="currentExperimentId" placeholder="启动后自动填入，也可手动输入" />
        </label>

        <div class="actions">
          <button type="button" :disabled="loading.start" @click="startExperiment">
            {{ loading.start ? '启动中...' : '开始演练' }}
          </button>
          <button type="button" :disabled="loading.diagnosis" @click="runRuleDiagnosis">
            {{ loading.diagnosis ? '诊断中...' : '执行规则诊断' }}
          </button>
          <button type="button" :disabled="loading.ai" @click="generateAiDiagnosis">
            {{ loading.ai ? 'AI 诊断生成中...' : '生成 AI 诊断报告' }}
          </button>
          <button type="button" :disabled="loading.refresh" @click="refreshExperimentData">
            {{ loading.refresh ? '刷新中...' : '刷新实验数据' }}
          </button>
        </div>
      </aside>

      <section class="content-stack">
        <section class="panel">
          <div class="section-heading">
            <h2>实验信息</h2>
            <span>{{ formatValue(experiment?.status) }}</span>
          </div>
          <div v-if="experiment" class="info-grid">
            <div>
              <span>experimentId</span>
              <strong>{{ formatValue(experiment.experimentId) }}</strong>
            </div>
            <div>
              <span>traceId</span>
              <strong>{{ formatValue(experiment.traceId) }}</strong>
            </div>
            <div>
              <span>status</span>
              <strong>{{ formatValue(experiment.status) }}</strong>
            </div>
            <div>
              <span>scenarioCode</span>
              <strong>{{ formatValue(experiment.scenarioCode || selectedScenarioCode) }}</strong>
            </div>
            <div>
              <span>startTime</span>
              <strong>{{ formatValue(experiment.startTime) }}</strong>
            </div>
            <div>
              <span>endTime</span>
              <strong>{{ formatValue(experiment.endTime) }}</strong>
            </div>
          </div>
          <p v-else class="empty">暂无数据</p>
        </section>

        <section class="panel">
          <div class="section-heading">
            <h2>规则诊断结果</h2>
            <span>{{ formatValue(ruleDiagnosis?.confidence) }}</span>
          </div>
          <div v-if="ruleDiagnosis" class="diagnosis">
            <div class="info-grid">
              <div>
                <span>faultType</span>
                <strong>{{ formatValue(ruleDiagnosis.faultType) }}</strong>
              </div>
              <div>
                <span>faultName</span>
                <strong>{{ formatValue(ruleDiagnosis.faultName) }}</strong>
              </div>
              <div>
                <span>confidence</span>
                <strong>{{ formatValue(ruleDiagnosis.confidence) }}</strong>
              </div>
              <div>
                <span>matched</span>
                <strong>{{ formatBoolean(ruleDiagnosis.matched) }}</strong>
              </div>
            </div>
            <p class="reason">{{ formatValue(ruleDiagnosis.reason) }}</p>
            <div class="list-columns">
              <div>
                <h3>Evidence</h3>
                <ul v-if="ruleDiagnosis.evidence?.length">
                  <li v-for="item in ruleDiagnosis.evidence" :key="item">{{ item }}</li>
                </ul>
                <p v-else class="empty small">暂无数据</p>
              </div>
              <div>
                <h3>Suggestions</h3>
                <ul v-if="ruleDiagnosis.suggestions?.length">
                  <li v-for="item in ruleDiagnosis.suggestions" :key="item">{{ item }}</li>
                </ul>
                <p v-else class="empty small">暂无数据</p>
              </div>
            </div>
          </div>
          <p v-else class="empty">暂无数据</p>
        </section>

        <section class="panel ai-panel">
          <div class="section-heading">
            <h2>AI 诊断报告</h2>
            <span>{{ formatValue(aiDiagnosis?.confidence) }}</span>
          </div>
          <section v-if="aiError" class="alert ai-alert">
            {{ aiError }}
          </section>
          <div v-if="aiDiagnosis" class="diagnosis ai-diagnosis">
            <div v-if="aiDiagnosis.fallback === true" class="fallback-badge">
              当前为降级报告
            </div>
            <div class="info-grid">
              <div>
                <span>faultType</span>
                <strong>{{ formatValue(aiDiagnosis.faultType) }}</strong>
              </div>
              <div>
                <span>faultName</span>
                <strong>{{ formatValue(aiDiagnosis.faultName) }}</strong>
              </div>
              <div>
                <span>confidence</span>
                <strong>{{ formatValue(aiDiagnosis.confidence) }}</strong>
              </div>
              <div>
                <span>fallback</span>
                <strong>{{ formatBoolean(aiDiagnosis.fallback) }}</strong>
              </div>
            </div>
            <p class="reason ai-summary">{{ formatValue(aiDiagnosis.summary) }}</p>
            <div class="list-columns ai-list-grid">
              <div>
                <h3>Phenomenon</h3>
                <ul v-if="aiDiagnosis.phenomenon?.length">
                  <li v-for="item in aiDiagnosis.phenomenon" :key="item">{{ item }}</li>
                </ul>
                <p v-else class="empty small">暂无数据</p>
              </div>
              <div>
                <h3>Evidence</h3>
                <ul v-if="aiDiagnosis.evidence?.length">
                  <li v-for="item in aiDiagnosis.evidence" :key="item">{{ item }}</li>
                </ul>
                <p v-else class="empty small">暂无数据</p>
              </div>
              <div>
                <h3>Root Causes</h3>
                <ul v-if="aiDiagnosis.rootCauses?.length">
                  <li v-for="item in aiDiagnosis.rootCauses" :key="item">{{ item }}</li>
                </ul>
                <p v-else class="empty small">暂无数据</p>
              </div>
              <div>
                <h3>Suggestions</h3>
                <ul v-if="aiDiagnosis.suggestions?.length">
                  <li v-for="item in aiDiagnosis.suggestions" :key="item">{{ item }}</li>
                </ul>
                <p v-else class="empty small">暂无数据</p>
              </div>
              <div>
                <h3>Runbook References</h3>
                <ul v-if="aiDiagnosis.runbookReferences?.length">
                  <li
                    v-for="item in aiDiagnosis.runbookReferences"
                    :key="`${item.docId || ''}-${item.section || ''}-${item.title || ''}`"
                  >
                    {{ [item.docId, item.section, item.title].filter(Boolean).join(' / ') }}
                  </li>
                </ul>
                <p v-else class="empty small">暂无数据</p>
              </div>
            </div>
          </div>
          <p v-else-if="!aiError" class="empty">暂无 AI 诊断报告</p>
        </section>

        <section class="panel">
          <div class="section-heading">
            <h2>指标表格</h2>
            <span>{{ metrics.length }} items</span>
          </div>
          <div v-if="cacheMetricHighlights.length" class="cache-metric-grid">
            <article
              v-for="metric in cacheMetricHighlights"
              :key="metric.name"
              class="cache-metric-card"
            >
              <span>{{ metric.name }}</span>
              <strong>{{ formatValue(metric.value) }}</strong>
              <small>{{ formatValue(metric.unit) }}</small>
            </article>
          </div>
          <div class="table-wrap">
            <table v-if="metrics.length">
              <thead>
                <tr>
                  <th>metricName</th>
                  <th>metricValue</th>
                  <th>metricUnit</th>
                  <th>component</th>
                  <th>createdAt</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(metric, index) in metrics" :key="`${metric.metricName}-${index}`">
                  <td>{{ formatValue(metric.metricName) }}</td>
                  <td>{{ formatValue(metric.metricValue) }}</td>
                  <td>{{ formatValue(metric.metricUnit) }}</td>
                  <td>{{ formatValue(metric.component) }}</td>
                  <td>{{ formatValue(metric.createdAt) }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else class="empty">暂无数据</p>
          </div>
        </section>

        <section class="panel">
          <div class="section-heading">
            <h2>Trace 树</h2>
            <span>{{ traceRoots.length }} root</span>
          </div>
          <div v-if="traceRoots.length" class="trace-tree">
            <TraceNode v-for="node in traceRoots" :key="node.spanId || node.operationName" :node="node" />
          </div>
          <p v-else class="empty">暂无 Trace 数据</p>
        </section>
      </section>
    </section>

    <section class="rag-console">
      <div class="rag-console-header">
        <div>
          <p class="eyebrow">RAG Console</p>
          <h2>Retrieval Evaluation and Debug</h2>
        </div>
        <div class="status-strip">
          <span>AI Service</span>
          <strong>/ai</strong>
        </div>
      </div>

      <section class="rag-grid">
        <section class="panel rag-panel">
          <div class="section-heading">
            <h2>RAG Evaluation</h2>
            <span>{{ evaluationForm.retriever }} / topK={{ evaluationForm.topK }}</span>
          </div>

          <div class="rag-form">
            <label class="field">
              <span>retriever</span>
              <select v-model="evaluationForm.retriever">
                <option value="bm25">bm25</option>
                <option value="hybrid">hybrid</option>
                <option value="milvus">milvus</option>
                <option value="all">all</option>
              </select>
            </label>
            <label class="field">
              <span>topK</span>
              <input v-model.number="evaluationForm.topK" type="number" min="1" />
            </label>
            <label class="check-field">
              <input v-model="evaluationForm.report" type="checkbox" />
              <span>report</span>
            </label>
            <button type="button" :disabled="evaluationLoading" @click="runRagEvaluation">
              {{ evaluationLoading ? 'Running...' : 'Run Evaluation' }}
            </button>
          </div>

          <section v-if="evaluationError" class="alert">
            {{ evaluationError }}
          </section>

          <div v-if="evaluationSummaries.length" class="rag-stack">
            <div class="metric-card-grid">
              <article
                v-for="summary in evaluationSummaries"
                :key="summary.retrieverName"
                class="metric-card"
                :class="{ failed: summary.error }"
              >
                <span>{{ summary.retrieverName }}</span>
                <strong v-if="summary.error">error</strong>
                <strong v-else>{{ formatMetric(summary.hitAtK) }} Hit@K</strong>
                <small v-if="summary.error">{{ summary.error }}</small>
                <small v-else>
                  cases={{ summary.caseCount }}
                  recall={{ formatMetric(summary.recallAtK) }}
                  mrr={{ formatMetric(summary.mrr) }}
                </small>
              </article>
            </div>

            <div
              v-for="summary in evaluationSummaries"
              :key="`${summary.retrieverName}-cases`"
              class="rag-result-group"
            >
              <div class="section-heading compact">
                <h3>{{ summary.retrieverName }} case results</h3>
                <span v-if="summary.error">failed</span>
              </div>
              <section v-if="summary.error" class="alert">
                {{ summary.error }}
              </section>
              <div v-else class="table-wrap rag-table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>caseId</th>
                      <th>hit</th>
                      <th>recall</th>
                      <th>reciprocalRank</th>
                      <th>expected</th>
                      <th>retrieved</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="result in summary.results || []" :key="result.caseId">
                      <td>{{ result.caseId }}</td>
                      <td>{{ formatBoolean(result.hit) }}</td>
                      <td>{{ formatMetric(result.recall) }}</td>
                      <td>{{ formatMetric(result.reciprocalRank) }}</td>
                      <td>{{ formatRefs(result.expected) }}</td>
                      <td>{{ formatRefs(result.retrieved) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            <div v-if="evaluationResult?.markdownReport" class="rag-result-group">
              <div class="section-heading compact">
                <h3>Markdown Report</h3>
              </div>
              <pre class="markdown-report">{{ evaluationResult.markdownReport }}</pre>
            </div>
          </div>
          <p v-else-if="!evaluationLoading && !evaluationError" class="empty">
            No evaluation result
          </p>
        </section>

        <section class="panel rag-panel">
          <div class="section-heading">
            <h2>Retrieval Debug</h2>
            <span>{{ debugForm.caseId }}</span>
          </div>

          <div class="rag-form debug-form">
            <label class="field wide">
              <span>demo case</span>
              <select v-model="debugForm.caseId">
                <option value="mq_backlog_core_metrics">mq_backlog_core_metrics</option>
                <option value="thread_pool_rejection">thread_pool_rejection</option>
                <option value="idempotency_request_hash_conflict">idempotency_request_hash_conflict</option>
              </select>
            </label>
            <label class="field">
              <span>topK</span>
              <input v-model.number="debugForm.topK" type="number" min="1" />
            </label>
            <label class="check-field">
              <input v-model="debugForm.includeContent" type="checkbox" />
              <span>includeContent</span>
            </label>
            <button type="button" :disabled="debugLoading" @click="runRagDebug">
              {{ debugLoading ? 'Debugging...' : 'Debug Retrieval' }}
            </button>
          </div>

          <section v-if="debugError" class="alert">
            {{ debugError }}
          </section>

          <div v-if="debugResult" class="rag-stack">
            <div v-if="debugResult.warnings?.length" class="warning-box">
              <strong>Warnings</strong>
              <ul>
                <li v-for="warning in debugResult.warnings" :key="warning">{{ warning }}</li>
              </ul>
            </div>

            <div class="rag-result-group">
              <div class="section-heading compact">
                <h3>queryText</h3>
                <span>{{ debugResult.faultType }}</span>
              </div>
              <pre class="query-text">{{ debugResult.queryText }}</pre>
            </div>

            <div
              v-for="stage in [
                ['vectorResults', 'Vector Results'],
                ['bm25Results', 'BM25 Results'],
                ['fusionResults', 'Fusion Results'],
                ['rerankResults', 'Rerank Results'],
                ['finalResults', 'Final Results'],
              ]"
              :key="stage[0]"
              class="rag-result-group"
            >
              <div class="section-heading compact">
                <h3>{{ stage[1] }}</h3>
                <span>{{ debugResult[stage[0]]?.length || 0 }} chunks</span>
              </div>
              <div v-if="debugResult[stage[0]]?.length" class="debug-list">
                <article
                  v-for="(chunk, index) in debugResult[stage[0]]"
                  :key="`${stage[0]}-${chunk.docId}-${chunk.section}-${index}`"
                  class="debug-chunk"
                >
                  <div class="debug-chunk-head">
                    <strong>{{ chunk.docId }} / {{ chunk.section }}</strong>
                    <span>{{ formatMetric(chunk.score) }}</span>
                  </div>
                  <p>{{ chunk.title }}</p>
                  <small>{{ chunk.faultType }}</small>
                  <pre class="metadata">{{ formatMetadata(chunk.metadata) }}</pre>
                  <pre v-if="chunk.content" class="chunk-content">{{ chunk.content }}</pre>
                </article>
              </div>
              <p v-else class="empty small">No chunks</p>
            </div>
          </div>
          <p v-else-if="!debugLoading && !debugError" class="empty">
            No debug result
          </p>
        </section>
      </section>
    </section>
  </main>
</template>

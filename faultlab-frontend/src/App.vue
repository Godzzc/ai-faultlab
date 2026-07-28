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
]

const selectedScenarioCode = ref(scenarios[0].code)
const params = reactive({ ...scenarios[0].defaults })
const experiment = ref(null)
const metrics = ref([])
const diagnosisReport = ref(null)
const ruleDiagnosis = ref(null)
const traceTree = ref(null)
const currentExperimentId = ref('')
const loading = reactive({
  start: false,
  refresh: false,
  diagnosis: false,
})
const errorMessage = ref('')

const selectedScenario = computed(() =>
  scenarios.find((scenario) => scenario.code === selectedScenarioCode.value) ?? scenarios[0],
)

const traceRoots = computed(() => {
  return Array.isArray(traceTree.value?.roots) ? traceTree.value.roots : []
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
  return payload?.data ?? null
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
    errorMessage.value = '请先启动演练，或输入 experimentId'
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
    await fetchTraceTree(detail?.traceId || experiment.value?.traceId)
  } catch (error) {
    errorMessage.value = error.message || '刷新实验数据失败'
  } finally {
    loading.refresh = false
  }
}

async function runRuleDiagnosis() {
  if (!currentExperimentId.value) {
    errorMessage.value = '请先启动演练，或输入 experimentId'
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

function normalizedParams() {
  return Object.fromEntries(
    Object.entries(params).map(([key, value]) => [key, Number(value)]),
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

function formatValue(value) {
  if (value === null || value === undefined || value === '') return '暂无数据'
  return value
}

function formatBoolean(value) {
  if (value === true) return 'true'
  if (value === false) return 'false'
  return '暂无数据'
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
            <small>{{ scenario.code }}</small>
            <span>{{ scenario.description }}</span>
          </button>
        </div>

        <div class="section-heading compact">
          <h2>参数配置</h2>
        </div>
        <div class="form-grid">
          <label v-for="field in selectedScenario.fields" :key="field.key" class="field">
            <span>{{ field.label }}</span>
            <input v-model.number="params[field.key]" type="number" :min="field.min" />
          </label>
        </div>

        <label class="field">
          <span>当前 experimentId</span>
          <input v-model.trim="currentExperimentId" placeholder="启动后自动填充，也可手动输入" />
        </label>

        <div class="actions">
          <button type="button" :disabled="loading.start" @click="startExperiment">
            {{ loading.start ? '启动中...' : '开始演练' }}
          </button>
          <button type="button" :disabled="loading.diagnosis" @click="runRuleDiagnosis">
            {{ loading.diagnosis ? '诊断中...' : '执行规则诊断' }}
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

        <section class="panel">
          <div class="section-heading">
            <h2>指标表格</h2>
            <span>{{ metrics.length }} items</span>
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
  </main>
</template>


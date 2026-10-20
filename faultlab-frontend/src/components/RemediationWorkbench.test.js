import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RemediationWorkbench from './RemediationWorkbench.vue'
import { requestJson } from '../api.js'

vi.mock('../api.js', () => ({ requestJson: vi.fn() }))

const experiment = {
  experimentId: 'exp-original',
  scenarioCode: 'DOWNSTREAM_TIMEOUT',
  status: 'COMPLETED',
  traceId: 'trace-original',
  params: {
    requestCount: 100,
    concurrency: 20,
    downstreamDelayMs: 300,
    timeoutMs: 100,
    timeoutRatio: 0.8,
    enableFallback: false,
  },
}

const remediationPlan = {
  planId: 'plan-1',
  experimentId: 'exp-original',
  scenarioCode: 'DOWNSTREAM_TIMEOUT',
  status: 'PROPOSED',
  confidence: 0.8,
  actions: [{
    actionType: 'ENABLE_FALLBACK',
    description: 'Enable fallback.',
    rationale: 'Reduce surfaced API errors.',
    parameterPatch: { enableFallback: true },
  }],
  expectedEffects: [
    { metricName: 'api.error.count', direction: 'DECREASE', description: 'Errors should fall.' },
    { metricName: 'downstream.timeout.count', direction: 'STABLE', description: 'Timeouts stay stable.' },
  ],
  warnings: [],
}

const diagnosis = {
  summary: 'Downstream timeouts are surfacing as API errors.',
  evidence: ['downstream.timeout.count=80'],
  runbookReferences: [{ docId: 'downstream-timeout', section: 'Remediation' }],
  remediationPlan,
}

const replayDetail = {
  experimentId: 'exp-replay',
  sourceExperimentId: 'exp-original',
  scenarioCode: 'DOWNSTREAM_TIMEOUT',
  status: 'COMPLETED',
}

function validation(status = 'VERIFIED', comparisons = [{
  metricName: 'api.error.count',
  beforeValue: 80,
  afterValue: 0,
  absoluteChange: -80,
  changePercent: -100,
  expectedDirection: 'DECREASE',
  matchedExpectation: true,
  status: 'MATCHED',
}]) {
  return {
    originalExperimentId: 'exp-original',
    replayExperimentId: 'exp-replay',
    scenarioCode: 'DOWNSTREAM_TIMEOUT',
    status,
    appliedPatch: { enableFallback: true },
    totalExpectedEffects: comparisons.length,
    matchedEffects: comparisons.filter((item) => item.status === 'MATCHED').length,
    unmatchedEffects: comparisons.filter((item) => item.status === 'UNMATCHED').length,
    inconclusiveEffects: comparisons.filter((item) => item.status === 'INCONCLUSIVE').length,
    metricComparisons: comparisons,
    traceComparison: {
      beforeSpanCount: 3,
      afterSpanCount: 3,
      beforeErrorSpanCount: 1,
      afterErrorSpanCount: 0,
      beforeTotalDurationMs: 500,
      afterTotalDurationMs: 420,
      beforeOperationCounts: { 'downstream.call': 1 },
      afterOperationCounts: { 'downstream.call': 1 },
    },
    summary: '1 of 1 expected metric effects matched.',
    validatedAt: '2026-10-20T12:00:00',
  }
}

function mountWorkbench(overrides = {}) {
  return mount(RemediationWorkbench, {
    props: {
      experiment,
      diagnosis,
      metrics: [{ metricName: 'api.error.count', metricValue: '80' }],
      ...overrides,
    },
  })
}

async function confirmAndExecute(wrapper) {
  await wrapper.get('[data-testid="prepare-replay"]').trigger('click')
  await wrapper.get('[data-testid="confirm-replay"]').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  requestJson.mockReset()
  window.history.replaceState({}, '', '/')
})

describe('RemediationWorkbench', () => {
  it('renders the workbench, experiment, diagnosis, and structured plan', () => {
    const wrapper = mountWorkbench()
    expect(wrapper.get('[data-testid="remediation-workbench"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('exp-original')
    expect(wrapper.text()).toContain('Downstream timeouts are surfacing as API errors.')
    expect(wrapper.text()).toContain('ENABLE_FALLBACK')
    expect(wrapper.text()).toContain('预期保持稳定')
  })

  it.each(['UNSUPPORTED', 'INVALID'])('%s plans cannot execute Replay', async (status) => {
    const wrapper = mountWorkbench({ diagnosis: { ...diagnosis, remediationPlan: { ...remediationPlan, status } } })
    expect(wrapper.get('[data-testid="prepare-replay"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Replay 已禁用')
  })

  it('submits the exact Replay DTO and shows the replay id', async () => {
    requestJson
      .mockResolvedValueOnce({ replayExperimentId: 'exp-replay', status: 'COMPLETED' })
      .mockResolvedValueOnce(replayDetail)
      .mockResolvedValueOnce(validation())
    const wrapper = mountWorkbench()

    await confirmAndExecute(wrapper)

    expect(requestJson).toHaveBeenNthCalledWith(
      1,
      '/api/experiments/exp-original/remediation-replay',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          planId: 'plan-1',
          scenarioCode: 'DOWNSTREAM_TIMEOUT',
          parameterPatch: { enableFallback: true },
        }),
      }),
    )
    expect(wrapper.get('[data-testid="replay-id"]').text()).toBe('exp-replay')
    expect(window.location.search).toContain('replayExperimentId=exp-replay')
  })

  it('prevents duplicate Replay submission while a request is in flight', async () => {
    let resolveReplay
    requestJson.mockImplementationOnce(() => new Promise((resolve) => { resolveReplay = resolve }))
    const wrapper = mountWorkbench()
    await wrapper.get('[data-testid="prepare-replay"]').trigger('click')
    const button = wrapper.get('[data-testid="confirm-replay"]')
    await button.trigger('click')
    await button.trigger('click')
    expect(requestJson).toHaveBeenCalledTimes(1)
    expect(button.attributes('disabled')).toBeDefined()
    resolveReplay({ replayExperimentId: 'exp-replay', status: 'COMPLETED' })
  })

  it('shows a clear Replay error and restores the action state', async () => {
    requestJson.mockRejectedValueOnce(new Error('policy rejected patch'))
    const wrapper = mountWorkbench()
    await confirmAndExecute(wrapper)
    expect(wrapper.get('[data-testid="replay-error"]').text()).toContain('policy rejected patch')
    expect(wrapper.get('[data-testid="confirm-replay"]').attributes('disabled')).toBeUndefined()
  })

  it('calls the Validation API after loading the persisted Replay relation', async () => {
    requestJson.mockResolvedValueOnce(replayDetail).mockResolvedValueOnce(validation())
    mountWorkbench({ initialReplayExperimentId: 'exp-replay' })
    await flushPromises()
    expect(requestJson).toHaveBeenNthCalledWith(1, '/api/experiments/exp-replay')
    expect(requestJson).toHaveBeenNthCalledWith(2, '/api/experiments/exp-replay/remediation-validation')
  })

  it.each([
    ['VERIFIED', '已验证'],
    ['PARTIALLY_VERIFIED', '部分验证'],
    ['NOT_VERIFIED', '未验证通过'],
    ['INCONCLUSIVE', '证据不足，无法判断'],
  ])('renders backend Validation status %s without deriving another status', async (status, label) => {
    requestJson.mockResolvedValueOnce(replayDetail).mockResolvedValueOnce(validation(status))
    const wrapper = mountWorkbench({ initialReplayExperimentId: 'exp-replay' })
    await flushPromises()
    expect(wrapper.get('[data-testid="validation-status"]').text()).toBe(label)
    if (status === 'INCONCLUSIVE') {
      expect(wrapper.text()).toContain('这与“修复未生效”不同')
    }
  })

  it('handles null changePercent without NaN or Infinity', async () => {
    const comparisons = [{
      metricName: 'downstream.fallback.count', beforeValue: 0, afterValue: 80,
      absoluteChange: 80, changePercent: null, expectedDirection: 'INCREASE',
      matchedExpectation: true, status: 'MATCHED', note: 'baseline is zero',
    }]
    requestJson.mockResolvedValueOnce(replayDetail).mockResolvedValueOnce(validation('VERIFIED', comparisons))
    const wrapper = mountWorkbench({ initialReplayExperimentId: 'exp-replay' })
    await flushPromises()
    expect(wrapper.text()).toContain('基线为 0，无法计算百分比')
    expect(wrapper.text()).not.toContain('NaN')
    expect(wrapper.text()).not.toContain('Infinity')
  })

  it('shows explicit missing Metrics and missing comparison states', async () => {
    requestJson.mockResolvedValueOnce(replayDetail).mockResolvedValueOnce(validation('INCONCLUSIVE', []))
    const wrapper = mountWorkbench({ metrics: [], initialReplayExperimentId: 'exp-replay' })
    await flushPromises()
    expect(wrapper.text()).toContain('当前缺少 Metrics')
    expect(wrapper.text()).toContain('Validation 未返回可比较的 Metrics')
  })

  it('recovers Validation after refresh without executing Replay again', async () => {
    requestJson.mockResolvedValueOnce(replayDetail).mockResolvedValueOnce(validation())
    const wrapper = mountWorkbench({ initialReplayExperimentId: 'exp-replay' })
    await flushPromises()
    expect(wrapper.get('[data-testid="validation-status"]').text()).toBe('已验证')
    expect(requestJson.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
  })

  it('recovers from a Validation network error when a new replay id is supplied', async () => {
    requestJson.mockResolvedValueOnce(replayDetail).mockRejectedValueOnce(new Error('network unavailable'))
    const wrapper = mountWorkbench({ initialReplayExperimentId: 'exp-replay' })
    await flushPromises()
    expect(wrapper.get('[data-testid="validation-error"]').text()).toContain('network unavailable')

    requestJson
      .mockResolvedValueOnce({ ...replayDetail, experimentId: 'exp-replay-2' })
      .mockResolvedValueOnce({ ...validation(), replayExperimentId: 'exp-replay-2' })
    await wrapper.setProps({ initialReplayExperimentId: 'exp-replay-2' })
    await flushPromises()
    expect(wrapper.find('[data-testid="validation-error"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="validation-status"]').text()).toBe('已验证')
  })

  it('rejects a replay linked to a different original id', async () => {
    requestJson.mockResolvedValueOnce({ ...replayDetail, sourceExperimentId: 'exp-other' })
    const wrapper = mountWorkbench({ initialReplayExperimentId: 'exp-replay' })
    await flushPromises()
    expect(wrapper.get('[data-testid="validation-error"]').text()).toContain('不一致')
    expect(requestJson).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="original-id"]').text()).toBe('exp-original')
  })

  it('disables Replay when original persisted params are unavailable', () => {
    const wrapper = mountWorkbench({ experiment: { ...experiment, params: null } })
    expect(wrapper.get('[data-testid="prepare-replay"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('没有可用的持久化参数')
  })
})

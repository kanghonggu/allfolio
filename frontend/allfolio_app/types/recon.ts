// 대사·검증 (P2 #18~21)

export type RunType = 'VALIDATION' | 'RECONCILIATION' | 'ALL'

export interface ReconRun {
  id: string
  runDate: string
  runType: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  triggerType: string
  internalAsOf: string | null
  externalAsOf: string | null
  startedAt: string
  finishedAt: string | null
}

export interface ReconSummary {
  id: string
  ruleCode: string
  status: 'PASSED' | 'DIFF_FOUND' | 'FAILED'
  checkedCnt: number
  diffCnt: number
  kdAbsorbedCnt: number
  errorMsg: string | null
  elapsedMs: number
}

export interface ReconRunDetail {
  run: ReconRun
  summaries: ReconSummary[]
}

export interface ReconDiffDetail {
  id: string
  ruleCode: string
  symbol: string | null
  fieldName: string | null
  diffType: 'VALUE_MISMATCH' | 'MISSING_INTERNAL' | 'MISSING_EXTERNAL' | 'RULE_VIOLATION'
  internalValue: number | null
  externalValue: number | null
  diffValue: number | null
  extras: string | null
  kdId: string | null
}

export interface ReconKd {
  id: string
  kdCode: string
  targetSymbol: string | null
  targetField: string | null
  valueType: 'ABS' | 'RATIO'
  allowValue: number
  reason: string
  apldStrtDt: string
  apldEndDt: string
  useYn: boolean
  createdAt: string
}

export interface RegisterKdPayload {
  kdCode: string
  targetSymbol?: string
  targetField?: string
  valueType: 'ABS' | 'RATIO'
  allowValue: number
  reason: string
  apldStrtDt: string
}

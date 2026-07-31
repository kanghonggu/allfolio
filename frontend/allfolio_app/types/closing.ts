// 마감 관제 (P3 #26~29)

export type WfRollup = 'STANDBY' | 'FINISH' | 'ERROR' | 'RUNNING' | 'PAUSED'
export type WfJobStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'ERROR' | 'PAUSED'

export interface WfStepCard {
  stepCd: string
  stepName: string
  stepGroup: string | null
  rollup: WfRollup
  errorCnt: number
  pendingCnt: number
  url: string | null
  cutoffEnd: string | null
}

export interface WfDayView {
  ymd: string
  isHoliday: boolean
  steps: WfStepCard[]
}

export interface WfMonthView {
  month: string
  days: WfDayView[]
}

export interface WfJobLogView {
  id: string
  ymd: string
  stepCd: string
  subStepCd: string
  execSeq: number
  status: WfJobStatus
  startedAt: string | null
  finishedAt: string | null
  autoManual: string
  executor: string
  remark: string | null
  errorDetail: string | null
}

export interface WfSubStepView {
  subStepCd: string
  subStepName: string
  actionType: 'CHAIN' | 'POLL' | 'MANUAL'
  actionRef: string | null
  autoManual: string
  closingCheckYn: boolean
  scheduledToday: boolean
  latest: WfJobLogView | null
  history: WfJobLogView[]
}

export interface WfStepDetail {
  stepCd: string
  stepName: string
  rollup: WfRollup
  essentialStepCd: string | null
  cutoffStart: string | null
  cutoffEnd: string | null
  subSteps: WfSubStepView[]
}

export interface WfDayDetail {
  ymd: string
  isHoliday: boolean
  steps: WfStepDetail[]
}

export interface WfRunSummary {
  ymd: string
  executedSteps: string[]
  gateSkippedSteps: string[]
  notScheduledSteps: string[]
}

export interface ClosingStepEvent {
  ymd: string
  stepCd: string
  subStepCd: string
  execSeq: number
  status: WfJobStatus
  level: 'info' | 'error'
  remark: string | null
}

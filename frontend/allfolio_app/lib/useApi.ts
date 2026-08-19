'use client'

import { useMemo } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { createUnifiedApi } from './unified-api'
import { createReportApi } from './report-api'
import { createReportArchiveApi, type ReportType } from './report-archive-api'
import { createGoalApi } from './goal-api'
import { createAiApi } from './ai-api'
import { createCashFlowApi } from './cashflow-api'
import { createBenchmarkApi } from './benchmark-api'
import { createExclusionPresetAdminApi } from './exclusion-preset-admin-api'
import { createMarketApi } from './market-api'
import { createDisclosureApi } from './disclosure-api'

export function useUnifiedApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createUnifiedApi(accessToken) : null),
    [accessToken],
  )
}

export function useReportApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createReportApi(accessToken) : null),
    [accessToken],
  )
}

export function useGoalApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createGoalApi(accessToken) : null),
    [accessToken],
  )
}

export function useAiApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createAiApi(accessToken) : null),
    [accessToken],
  )
}

export function useCashFlowApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createCashFlowApi(accessToken) : null),
    [accessToken],
  )
}

export function useBenchmarkApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createBenchmarkApi(accessToken) : null),
    [accessToken],
  )
}

export function useReportArchiveApi(reportType: ReportType) {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createReportArchiveApi(accessToken, reportType) : null),
    [accessToken, reportType],
  )
}

export function useExclusionPresetAdminApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createExclusionPresetAdminApi(accessToken) : null),
    [accessToken],
  )
}

export function useMarketApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createMarketApi(accessToken) : null),
    [accessToken],
  )
}

export function useDisclosureApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createDisclosureApi(accessToken) : null),
    [accessToken],
  )
}

'use client'

import { useMemo } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { createUnifiedApi } from './unified-api'
import { createReportApi } from './report-api'
import { createGoalApi } from './goal-api'
import { createAiApi } from './ai-api'
import { createCashFlowApi } from './cashflow-api'
import { createBenchmarkApi } from './benchmark-api'

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

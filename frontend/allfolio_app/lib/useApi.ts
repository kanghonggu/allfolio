'use client'

import { useMemo } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { createUnifiedApi } from './unified-api'
import { createReportApi } from './report-api'
import { createGoalApi } from './goal-api'

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

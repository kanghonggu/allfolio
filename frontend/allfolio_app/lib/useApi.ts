'use client'

import { useSession } from 'next-auth/react'
import { useMemo } from 'react'
import { createUnifiedApi } from './unified-api'
import { createReportApi } from './report-api'

export function useUnifiedApi() {
  const { data: session } = useSession()
  return useMemo(
    () => (session?.accessToken ? createUnifiedApi(session.accessToken) : null),
    [session?.accessToken],
  )
}

export function useReportApi() {
  const { data: session } = useSession()
  return useMemo(
    () => (session?.accessToken ? createReportApi(session.accessToken) : null),
    [session?.accessToken],
  )
}

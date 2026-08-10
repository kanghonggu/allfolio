'use client'

import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '@/lib/queryClient'
import { AuthProvider } from '@/contexts/AuthContext'
import Toaster from '@/components/Toaster'
import FeedbackWidget from '@/components/FeedbackWidget'

export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        {children}
        <FeedbackWidget />
        <Toaster />
      </QueryClientProvider>
    </AuthProvider>
  )
}

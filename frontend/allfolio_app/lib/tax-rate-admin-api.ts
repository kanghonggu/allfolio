import axios from 'axios'
import type { TaxRate, RegisterTaxRate } from '@/types/tax-rate'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/admin/tax-rates`

export function createTaxRateAdminApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    list: async (): Promise<TaxRate[]> => (await api.get<TaxRate[]>('')).data,
    register: async (body: RegisterTaxRate): Promise<TaxRate> => (await api.post<TaxRate>('', body)).data,
  }
}

export type IncomeType = 'DIVIDEND' | 'INTEREST' | 'DISTRIBUTION'

export interface TaxRate {
  id: string
  country: string
  incomeType: IncomeType
  rate: number
  effectiveStart: string
  effectiveEnd: string | null
  updatedBy: string | null
  updatedAt: string
}

export interface RegisterTaxRate {
  country: string
  incomeType: IncomeType
  rate: number
  effectiveStart: string
}

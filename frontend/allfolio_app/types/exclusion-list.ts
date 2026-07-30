// types/exclusion-list.ts
export interface ExclusionItem {
  id: string
  symbol: string
  memo: string | null
  addedAt: string
}

export interface ExclusionList {
  id: string
  name: string
  category: string
  description: string | null
  active: boolean
  itemCount: number
  items: ExclusionItem[]
  updatedAt: string
}

export interface PresetSymbol { symbol: string; reason: string }
export interface Preset { name: string; symbols: PresetSymbol[] }

export interface CreateList { name: string; category: string; description?: string | null }
export interface UpdateList { name: string; category: string; description?: string | null; active: boolean }

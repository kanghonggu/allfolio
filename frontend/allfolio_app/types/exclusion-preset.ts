export interface ExclusionPreset {
  id: string
  symbol: string
  listName: string
  reason: string
  updatedBy: string | null
  updatedAt: string
}

export interface UpsertPresetRequest {
  symbol: string
  listName: string
  reason: string
}

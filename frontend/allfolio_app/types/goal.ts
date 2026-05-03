export type GoalCategory = 'RETIREMENT' | 'HOUSING' | 'EDUCATION' | 'TRAVEL' | 'EMERGENCY' | 'OTHER'

export interface GoalRequest {
  name: string
  description?: string
  targetAmount: number
  targetDate?: string | null
  category: GoalCategory
}

export interface GoalResponse {
  id: string
  userId: string
  name: string
  description?: string
  targetAmount: number
  targetDate?: string
  category: GoalCategory
  currentAmount: number
  progressPct: number
  remainingAmount: number
  daysRemaining?: number
  createdAt: string
  updatedAt: string
}

export interface GoalsResponse {
  goals: GoalResponse[]
  totalNav: number
  generatedAt: string
}

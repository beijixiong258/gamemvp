export interface Region { id: string; parentId: string | null; regionName: string; enabled: boolean }
export type SaveStatus = 'STUDYING' | 'STAGE_EXAM_READY' | 'EXAM_READY' | 'COMPLETED'
export interface GameSave {
  id: string; createdAt: string; status: SaveStatus; birthYear: number; currentYear: number
  currentMonth: number; turnInMonth: number; age: number; totalTurnNumber: number; growthStage: string
}
export interface Character {
  id: string; saveId: string; name: string; type: number; npcCode: string | null; enabled: boolean
  wallet: number; sickTurnsRemaining: number; birthRegionId: string; currentRegionId: string; birthday: string
  officialPosition: string | null; officialRank: string | null; degree: string | null; titlesJson: string | null
  personalitySummary: string | null; currentState: string | null
  characterZhili: number; characterDaode: number; characterZhengzhi: number; characterJiaoji: number
  characterTineng: number; characterJiankang: number; characterPilao: number
}
export interface Scholar {
  id: string; characterId: string; abilityShizi: number; abilityJingyi: number
  abilityWenzhang: number; abilityCelun: number; abilityWenxue: number
}
export interface FamilyBackground { id: string; saveId: string; initialWealth: number; backgroundSummary: string }
export interface LibraryBook {
  bookCode: string; bookName: string; equipmentId: string; rarityCode: string; rarityName: string; rarityColor: string
  currentProgress: number; requiredProgress: number; totalReadTurnNumber: number; completed: boolean
  readable: boolean; playerReadingEnabled: boolean; blockedReasons: string[]; knowledgeSummary: string
  ownedQuantity: number; price: number; supplierNpcCode: string; totalKnowledge: number; acquiredKnowledge: number
}
export interface Exam {
  id: string; saveId: string; characterId: string; examType: string; questionText: string
  passThreshold: number; baseAbilityScore: number; stateOffset: number; diceRoll: number; luckOffset: number
  knowledgeTotal: number; aiThoughtBubble: string | null; playerChoice: string | null; playerInput: string | null
  aiPlayerContentModifier: number | null; finalScore: number | null; status: string
  aiAnswerText: string | null; aiContent: string | null; turnNumber: number
}
export interface Milestone {
  id: string; eventCode: string; eventSummary: string; occurredTurnNumber: number; lifeMilestone: boolean
}
export interface InventoryItem {
  equipment: { id: string; equipmentCode: string; equipmentName: string; equipmentType: string
    rarityCode: string; rarityName: string; rarityColor: string; description: string; price: number }
  quantity: number
}
export interface SaveDetail {
  save: GameSave; character: Character; familyBackground: FamilyBackground; scholarProfile: Scholar
  books: LibraryBook[]; exams: Exam[]; milestones: Milestone[]; knowledgeTotal: number
  backpack: InventoryItem[]; npcs: Character[]
}
export interface SaveSummary {
  saveId: string; characterName: string; age: number; currentYear: number; status: SaveStatus; createdAt: string
  officialPosition: string | null; officialRank: string | null; degree: string | null; titles: string[]
}
export interface Dialogue {
  id: string; saveId: string; actorId: string; counterpartId: string; sceneCode: string
  startedTurnNumber: number; version: number; ended: boolean; messagesJson: string
}
export interface DialogueMessage { speaker: 'actor' | 'counterpart'; text: string; manualEnd?: boolean
  executedTrades?: { equipmentName: string; quantity: number; cost: number }[] }
export interface ReadingQuestion {
  questionId: string; actorId: string; bookCode: string; bookName: string; sceneCode: string
  turnNumber: number; currentProgress: number; question: string
}
export interface Scene { sceneCode: string; sceneName: string; description: string; availableActionCode: string[]; availableNpcCode: string[] }
export interface OperationResult {
  detail?: SaveDetail; dialogue?: Dialogue; reply?: string; summary?: string; feedback?: string
  changes?: { progressGain: number; fatigueChange: number; healthChange: number; diceRoll: number | null
    abilityGain: Record<string, number> }
  applied?: { detail: SaveDetail; summary: string; trades: { equipmentName: string; quantity: number; cost: number }[] }
  exam?: Exam; score?: number; evaluation?: string; equipmentName?: string; quantity?: number; cost?: number
}
export type OperationKind = 'action' | 'free' | 'acquire' | 'dialogue-start' | 'dialogue-message'
  | 'question' | 'answer' | 'thought' | 'exam' | 'background'
export interface PendingRequest {
  kind: OperationKind; saveId: string; path: string; label: string; body?: Record<string, unknown>
}

// 与服务端接口一一对应的类型（server/src/main/java/app/lyng/web）。

export type Id = string

export interface Person {
  id: Id
  name: string
}

export interface Line {
  id: number
  round: number
  kind: 'narration' | 'say' | 'do' | 'wait' | 'inner' | 'closing'
  speakerId: Id | null
  text: string
  byUser: boolean
  runId: Id | null
  createdAt: string
}

export interface Message {
  id: number
  speakerId: Id
  kind: 'say' | 'inner'
  text: string
  byUser: boolean
  opportunity: 'user' | 'reply' | 'continue' | 'reach'
  unread: boolean
  runId: Id | null
  createdAt: string
}

export interface SceneSummary {
  id: Id
  worldId: Id
  worldName: string
  number: number
  tone: 'daily' | 'adventure'
  scale: 'mild' | 'adult'
  setup: string
  status: 'open' | 'closed'
  cast: Person[]
  possessedId: Id | null
  followId: Id | null
  last: string | null
  pending: boolean
  failure: string | null
  running: boolean
  createdAt: string
  closedAt: string | null
}

export interface ChatSummary {
  id: Id
  worldId: Id
  worldName: string
  a: Person
  b: Person
  playedId: Id | null
  last: Message | null
  unread: number
  pending: boolean
  failure: string | null
  running: boolean
  lastAt: string
}

export interface WorldCard {
  id: Id
  name: string
  characters: Person[]
  scenes: number
  busy: boolean
}

export interface Home {
  worlds: WorldCard[]
  openScenes: SceneSummary[]
  recentScenes: SceneSummary[]
  chats: ChatSummary[]
  unread: number
  drafts: DraftPage[]
  fake: boolean
}

export interface CharacterCard {
  id: Id
  name: string
  profile: string
  openScene: SceneSummary | null
}

export interface WorldPage {
  id: Id
  name: string
  publicText: string
  narratorText: string
  characters: CharacterCard[]
  scenes: SceneSummary[]
  chats: ChatSummary[]
  createdAt: string
}

export interface Self {
  text: string
  runId: Id | null
  createdAt: string
}

export interface View {
  otherId: Id
  text: string
  runId: Id | null
  createdAt: string
}

export interface ViewItem extends View {
  otherName: string
  versions: number
}

export interface ConcernItem {
  id: Id
  aboutId: Id | null
  aboutName: string | null
  /** 人物自己写的一句线索；诞生时或整理时写下的没有。 */
  cue: string | null
  text: string
  status: 'active' | 'background' | 'resolved'
  resolution: string | null
  runId: Id | null
  createdAt: string
  updatedAt: string
}

export interface CharacterPage {
  id: Id
  worldId: Id
  worldName: string
  name: string
  profile: string
  self: Self | null
  selfVersions: number
  views: ViewItem[]
  concerns: ConcernItem[]
  memories: number
  openScene: SceneSummary | null
  chats: ChatSummary[]
  others: Person[]
  createdAt: string
}

export interface MemoryItem {
  id: Id
  /** 人物自己写的一句线索；整理时写下的没有。 */
  cue: string | null
  content: string
  /** 整理时另写的“这件事意味着什么”。 */
  meaning: string | null
  permanent: boolean
  about: Person | null
  people: Person[]
  sourceKind: 'scene' | 'chat' | 'birth'
  sourceId: Id | null
  sourceLabel: string | null
  runId: Id | null
  createdAt: string
}

export interface Memories {
  items: MemoryItem[]
  total: number
}

export interface Source {
  sourceKind: 'scene' | 'chat'
  sourceId: Id
  sourceLabel: string
  lines: number
  firstAt: string
  lastAt: string
}

export interface Trace {
  id: number
  sourceKind: 'scene' | 'chat'
  sourceId: Id
  sourceLabel: string
  refId: number
  text: string
  createdAt: string
}

export interface ScenePage {
  scene: SceneSummary
  pending: string[]
  lines: Line[]
  previousClosing: string | null
}

export interface ChatPage {
  chat: ChatSummary
  pending: string[]
  messages: Message[]
  more: boolean
  busy: SceneSummary[]
}

export interface Question {
  question: string
  options: string[]
  recommended: string
}

export interface Answer {
  question: string
  answer: string
}

export interface DraftView {
  name: string
  text: string
}

export interface DraftConcern {
  about: string | null
  text: string
}

export interface DraftCharacter {
  name: string
  profile: string
  views: DraftView[]
  concerns: DraftConcern[]
}

export interface Draft {
  world: { name: string; publicText: string; narratorText: string } | null
  characters: DraftCharacter[]
}

export interface Conflict {
  problem: string
  suggestion: string
}

export interface DraftPage {
  id: Id
  worldId: Id | null
  worldName: string | null
  request: string
  status: 'asking' | 'writing' | 'ready' | 'born' | 'failed'
  questions: Question[] | null
  answers: Answer[]
  content: Draft | null
  conflicts: Conflict[] | null
  failure: string | null
  updatedAt: string
}

export interface Revision {
  target: string
  text: string
  runId: Id
}

export interface Born {
  worldId: Id
  characterIds: Id[]
}

export interface RunItem {
  id: Id
  purpose: string
  purposeLabel: string
  title: string
  characterId: Id | null
  sceneId: Id | null
  conversationId: Id | null
  draftId: Id | null
  status: 'running' | 'ok' | 'failed'
  failure: string | null
  attempts: number
  cost: number | null
  startedAt: string
  finishedAt: string | null
}

export interface ModelCall {
  attempt: number
  step: number
  model: string
  provider: string | null
  outcome: 'ok' | 'tool' | 'error' | 'refused'
  error: string | null
  cost: number | null
  latencyMs: number | null
  firstTokenMs: number | null
  promptTokens: number | null
  completionTokens: number | null
  reasoningTokens: number | null
  finishReason: string | null
  createdAt: string
}

export interface ToolLog {
  attempt: number
  name: string
  arguments: string
  result: string
}

export interface RunPage {
  run: RunItem
  request: { model: string; messages: { role: string; content: string }[]; tools: string[] } | null
  output: string | null
  reasoning: string | null
  tools: ToolLog[] | null
  outcome: Record<string, unknown> | null
  calls: ModelCall[]
}

export interface Usage {
  spent: number
  budget: number | null
  purposes: { purpose: string; calls: number; failures: number; cost: number; medianMs: number | null }[]
  days: { day: string; cost: number; calls: number }[]
}

export interface Settings {
  scale: 'mild' | 'adult'
  budgetUsd: number | null
  lastPresence: string | null
}

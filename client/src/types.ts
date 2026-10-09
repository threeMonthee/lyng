// 与服务端 web 包里的 JSON 一一对应。

export interface Beat {
  tag: "行" | "说";
  text: string;
}

export type EventKind = "narration" | "user" | "character" | "failure" | "resolved";

export interface SceneEvent {
  seq: number;
  round: number;
  kind: EventKind;
  speakerId: string | null;
  speaker: string | null;
  action: "说" | "做" | "静候" | null;
  text: string;
  beats: Beat[];
  inner: string | null;
  silent: boolean;
  step: "opening" | "narrator" | "character" | "closing" | null;
  refSeq: number | null;
  resolved: boolean;
}

export interface Person {
  id: string;
  name: string;
}

export interface SceneView {
  id: string;
  number: number;
  status: "open" | "ended";
  tone: string;
  setup: string;
  worldId: string;
  worldName: string;
  possessed: Person;
  others: Person[];
  events: SceneEvent[];
  opened: boolean;
  busy: boolean;
}

export interface SceneSummary {
  id: string;
  number: number;
  status: "open" | "ended";
  worldName: string;
  possessed: string;
  others: string[];
  startedAt: string;
}

export interface WorldView {
  id: string;
  name: string;
  publicText: string;
  characters: Person[];
}

export interface CharacterView {
  id: string;
  worldId: string;
  worldName: string;
  name: string;
  profile: string;
  memory: {
    memories: { sceneNumber: number; text: string }[];
    relations: { name: string; view: string }[];
  };
}

export interface Draft {
  world: { name: string; publicText: string; narratorText: string } | null;
  characters: { name: string; profile: string }[];
}

export interface ModelCall {
  id: number;
  kind: string;
  subject: string | null;
  model: string;
  provider: string | null;
  outcome: string;
  error: string | null;
  cost: number | null;
  latencyMs: number | null;
  firstTokenMs: number | null;
  reasoningTokens: number | null;
}

// 与服务端接口（server/.../web/Api.java）一一对应。

export type LineKind = "narration" | "say" | "do" | "wait" | "inner";

export type Line = {
  seq: number;
  round: number;
  kind: LineKind;
  speakerId: string | null;
  byUser: boolean;
  text: string;
};

export type Person = { id: string; name: string };

export type SceneView = {
  id: string;
  number: number;
  worldId: string;
  worldName: string;
  cast: Person[];
  possessedId: string | null;
  followId: string | null;
  tone: string;
  setup: string;
  status: "open" | "ended";
  pending: string[] | null;
  error: string | null;
  startedAt: string;
  endedAt: string | null;
  lines: Line[];
  busy: boolean;
};

export type SceneItem = {
  id: string;
  number: number;
  worldId: string;
  cast: string[];
  possessedId: string | null;
  status: "open" | "ended";
  failed: boolean;
  preview: string | null;
  startedAt: string;
};

export type Overview = {
  worlds: { id: string; name: string }[];
  characters: { id: string; worldId: string; name: string }[];
  scenes: SceneItem[];
  spent: number;
  budget: number | null;
  fake: boolean;
};

export type Draft = {
  world: { name: string; publicText: string; narratorText: string } | null;
  characters: { name: string; profile: string }[];
};

export type Memory = { sceneId: string; sceneNumber: number; memory: string };

export type CharacterPage = {
  character: { id: string; worldId: string; name: string; profile: string };
  worldName: string;
  memories: Memory[];
  views: { name: string; text: string }[];
  scenes: { id: string; number: number; status: "open" | "ended"; startedAt: string; cast: string[] }[];
};

export type WorldPage = {
  world: { id: string; name: string; publicText: string; narratorText: string };
  characters: { id: string; worldId: string; name: string }[];
};

export type Call = {
  createdAt: string;
  kind: string;
  subject: string | null;
  model: string;
  provider: string | null;
  outcome: string;
  error: string | null;
  cost: number | null;
  latencyMs: number | null;
  reasoningTokens: number | null;
};

export type Usage = { spent: number; budget: number | null; fake: boolean; calls: Call[] };

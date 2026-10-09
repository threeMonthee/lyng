import type { Beat, SceneEvent, SceneView } from "./types";
import type { SseMessage } from "./sse";

/** 一场戏在客户端的状态：已落库的事件，加上正在流式生成、尚未落库的暂定气泡。 */
export interface TimelineState {
  events: SceneEvent[];
  /** 人物姓名 → 这次生成已经写完的气泡（暂定）。 */
  drafts: Record<string, Beat[]>;
  /** 一轮正在进行（客户端发起的，或刷新时服务端仍在跑的）。 */
  running: boolean;
  /** 本轮的传输层错误（连接断了、服务出错），不是人物的选择。 */
  error: string | null;
}

export const emptyTimeline: TimelineState = { events: [], drafts: {}, running: false, error: null };

export function fromScene(scene: SceneView): TimelineState {
  return { events: scene.events, drafts: {}, running: scene.busy, error: null };
}

export function begin(state: TimelineState): TimelineState {
  return { ...state, running: true, error: null };
}

export function fail(state: TimelineState, message: string): TimelineState {
  return { ...state, running: false, drafts: {}, error: message };
}

/** 应用服务端推来的一条 SSE 消息。 */
export function apply(state: TimelineState, message: SseMessage): TimelineState {
  const data = message.data as SceneEvent & { speaker: string; tag: Beat["tag"]; error?: string };
  switch (message.event) {
    case "user":
    case "narration":
    case "failure":
      return { ...state, events: [...state.events, data] };
    case "resolved":
      return { ...state, events: [...state.events, data] };
    case "bubble": {
      const beats = state.drafts[data.speaker] ?? [];
      return { ...state, drafts: { ...state.drafts, [data.speaker]: [...beats, { tag: data.tag, text: data.text }] } };
    }
    case "retract": {
      const { [data.speaker]: _dropped, ...rest } = state.drafts;
      return { ...state, drafts: rest };
    }
    case "commit": {
      const { [data.speaker]: _done, ...rest } = state.drafts;
      return { ...state, drafts: rest, events: [...state.events, data] };
    }
    case "done":
      return { ...state, running: false, drafts: {} };
    case "error":
      return fail(state, data.error ?? "服务出错了");
    default:
      return state;
  }
}

/** 还没有被重试解决的失败：失败事件本身，减去被后来的 resolved 指向的。 */
export function unresolvedFailures(events: SceneEvent[]): SceneEvent[] {
  const resolved = new Set(events.filter((e) => e.kind === "resolved").map((e) => e.refSeq));
  return events.filter((e) => e.kind === "failure" && !resolved.has(e.seq));
}

/** 画面上要渲染的事件：不渲染 resolved 标记，也不渲染已经解决了的失败。 */
export function visibleEvents(events: SceneEvent[]): SceneEvent[] {
  const open = new Set(unresolvedFailures(events).map((e) => e.seq));
  return events.filter((e) => e.kind !== "resolved" && (e.kind !== "failure" || open.has(e.seq)));
}

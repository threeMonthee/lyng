import { describe, expect, it } from "vitest";
import { SseParser } from "./sse";
import { apply, begin, emptyTimeline, unresolvedFailures, visibleEvents } from "./timeline";
import type { SceneEvent } from "./types";

const event = (over: Partial<SceneEvent>): SceneEvent => ({
  seq: 1, round: 1, kind: "narration", speakerId: null, speaker: null, action: null, text: "", beats: [],
  inner: null, silent: false, step: null, refSeq: null, resolved: false, ...over,
});
const msg = (name: string, data: unknown) => ({ event: name, data });

describe("SseParser", () => {
  it("reassembles messages split at arbitrary points", () => {
    const text = 'event:bubble\ndata:{"speaker":"阿青","tag":"说","text":"你好"}\n\nevent:done\ndata:{"ok":true}\n\n';
    for (const size of [1, 2, 3, 7, 50, text.length]) {
      const parser = new SseParser();
      const out = [];
      for (let i = 0; i < text.length; i += size) out.push(...parser.push(text.slice(i, i + size)));
      expect(out.map((m) => m.event)).toEqual(["bubble", "done"]);
      expect(out[0].data).toEqual({ speaker: "阿青", tag: "说", text: "你好" });
    }
  });

  it("ignores keep-alive comments and handles CRLF", () => {
    const out = new SseParser().push(': ping\n\r\nevent:done\r\ndata:{"ok":true}\r\n\r\n');
    expect(out).toEqual([{ event: "done", data: { ok: true } }]);
  });
});

describe("timeline", () => {
  it("shows bubbles as they are written and replaces them with the committed turn", () => {
    let s = begin(emptyTimeline);
    s = apply(s, msg("user", event({ seq: 2, kind: "user", speaker: "阿远", action: "说", text: "我回来了。" })));
    s = apply(s, msg("bubble", { speaker: "阿青", tag: "行", text: "她抬眼。" }));
    s = apply(s, msg("bubble", { speaker: "阿青", tag: "说", text: "你回来了。" }));
    expect(s.drafts["阿青"]).toHaveLength(2);
    s = apply(s, msg("commit", event({ seq: 3, kind: "character", speaker: "阿青", beats: [{ tag: "行", text: "她抬眼。" }, { tag: "说", text: "你回来了。" }], inner: "终于。" })));
    expect(s.drafts).toEqual({});
    expect(s.events.map((e) => e.kind)).toEqual(["user", "character"]);
    s = apply(s, msg("done", { ok: true }));
    expect(s.running).toBe(false);
  });

  it("retracts the tentative bubbles of a failed attempt", () => {
    let s = apply(emptyTimeline, msg("bubble", { speaker: "阿青", tag: "说", text: "坏的" }));
    s = apply(s, msg("retract", { speaker: "阿青" }));
    expect(s.drafts).toEqual({});
  });

  it("hides failures once a later resolved marker points at them", () => {
    const failure = event({ seq: 4, kind: "failure", step: "character", speaker: "阿青", text: "空响应" });
    let events = [event({ seq: 1 }), failure];
    expect(unresolvedFailures(events)).toEqual([failure]);
    events = [...events, event({ seq: 5, kind: "resolved", refSeq: 4 })];
    expect(unresolvedFailures(events)).toEqual([]);
    expect(visibleEvents(events).map((e) => e.seq)).toEqual([1]);
  });

  it("turns a transport error into a visible error, not a character choice", () => {
    const s = apply(begin(emptyTimeline), msg("error", { error: "服务出错了" }));
    expect(s.error).toBe("服务出错了");
    expect(s.running).toBe(false);
  });
});

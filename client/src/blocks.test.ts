import { describe, expect, it } from "vitest";
import { parseSse } from "./api";
import { actionText, toBlocks } from "./blocks";
import type { Line } from "./types";

const line = (seq: number, round: number, kind: Line["kind"], speakerId: string | null, text = "", byUser = false): Line => ({
  seq,
  round,
  kind,
  speakerId,
  byUser,
  text,
});

describe("toBlocks", () => {
  it("同一个人同一轮的言行合成一块，心声挂在这一块上", () => {
    const blocks = toBlocks([
      line(1, 0, "narration", null, "入夜。"),
      line(2, 1, "say", "xf", "雪琪。", true),
      line(3, 1, "do", "lu", "她抬眼。"),
      line(4, 1, "say", "lu", "你来了。"),
      line(5, 1, "inner", "lu", "终于。"),
      line(6, 2, "wait", "xf", "", true),
      line(7, 2, "wait", "lu"),
      line(8, 2, "inner", "lu", "随他去。"),
    ]);
    expect(blocks.map((b) => (b.type === "narration" ? "叙事" : `${b.speakerId}:${b.items.length}:${b.inner ?? ""}`))).toEqual([
      "叙事",
      "xf:1:",
      "lu:2:终于。",
      "xf:1:",
      "lu:1:随他去。",
    ]);
  });

  it("下一轮的同一个人另起一块", () => {
    const blocks = toBlocks([line(1, 1, "say", "lu", "一"), line(2, 1, "inner", "lu", "x"), line(3, 2, "say", "lu", "二")]);
    expect(blocks).toHaveLength(2);
  });
});

describe("actionText", () => {
  it("用户写的做补上主语：附身时是你", () => {
    expect(actionText(line(1, 1, "do", "xf", "把伞往她那边倾了倾", true), "张小凡", true)).toBe("你把伞往她那边倾了倾");
    expect(actionText(line(1, 1, "do", "xf", "把伞往她那边倾了倾", true), "张小凡", false)).toBe("张小凡把伞往她那边倾了倾");
    expect(actionText(line(1, 1, "do", "lu", "陆雪琪抬眼。"), "陆雪琪", false)).toBe("陆雪琪抬眼。");
    expect(actionText(line(1, 1, "wait", "xf", "", true), "张小凡", true)).toBe("你没有开口");
  });
});

describe("parseSse", () => {
  it("只取完整的事件，半截留到下次", () => {
    const { events, rest } = parseSse('event: line\ndata: {"seq":1}\n\nevent: done\ndata: {}\n\nevent: dra');
    expect(events).toEqual([
      { event: "line", data: { seq: 1 } },
      { event: "done", data: {} },
    ]);
    expect(rest).toBe("event: dra");
  });
});

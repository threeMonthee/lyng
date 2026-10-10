import type { Line } from "./types";

/** 页面上的一块：叙事者的一段，或某人一次连续的言行（带同次生成的心声）。 */
export type Block =
  | { type: "narration"; key: string; text: string }
  | { type: "turn"; key: string; speakerId: string; byUser: boolean; items: Line[]; inner: string | null };

/** 把一场戏的行排成块：同一个人在同一轮里连续的说、行合成一块，心声挂在这一块上。 */
export function toBlocks(lines: Line[]): Block[] {
  const blocks: Block[] = [];
  let open: Extract<Block, { type: "turn" }> | null = null;
  let openRound = -1;
  for (const line of lines) {
    if (line.kind === "narration") {
      blocks.push({ type: "narration", key: `n${line.seq}`, text: line.text });
      open = null;
      continue;
    }
    if (line.kind === "inner") {
      if (open && open.speakerId === line.speakerId) open.inner = line.text;
      open = null;
      continue;
    }
    const sameTurn = open && open.speakerId === line.speakerId && openRound === line.round && open.byUser === line.byUser;
    if (!sameTurn) {
      open = { type: "turn", key: `t${line.seq}`, speakerId: line.speakerId!, byUser: line.byUser, items: [], inner: null };
      openRound = line.round;
      blocks.push(open);
    }
    open!.items.push(line);
  }
  return blocks;
}

/** 用户写的“做”不带主语，显示时补上：附身时是“你”，否则是人物的名字。 */
export function actionText(line: Line, name: string, mine: boolean): string {
  if (line.kind === "wait") return `${mine ? "你" : name}没有开口`;
  if (!line.byUser || line.text.startsWith("你") || line.text.startsWith(name)) return line.text;
  return `${mine ? "你" : name}${line.text}`;
}

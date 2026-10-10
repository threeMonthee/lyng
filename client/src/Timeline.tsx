import { useState } from "react";
import { actionText, toBlocks, type Block } from "./blocks";
import type { Line, SceneView } from "./types";
import { Avatar } from "./ui";

/** 正在进行的一步：人物正在写的段（暂定）与谁在写（null 是叙事者，undefined 是没人）。 */
export type Live = {
  running: boolean;
  drafts: Record<string, { kind: "say" | "do"; text: string }[]>;
  typing: string | null | undefined;
};

export function Timeline({ scene, live, names }: { scene: SceneView; live: Live; names: Map<string, string> }) {
  const [shown, setShown] = useState<Set<string>>(new Set());
  const toggle = (key: string) =>
    setShown((s) => {
      const next = new Set(s);
      if (!next.delete(key)) next.add(key);
      return next;
    });

  const blocks = toBlocks(scene.lines);
  // 暂定的段接在后面，样子与落库后的一样，落库时无缝替换
  for (const [speakerId, drafts] of Object.entries(live.drafts)) {
    if (drafts.length === 0) continue;
    blocks.push({
      type: "turn",
      key: `draft-${speakerId}`,
      speakerId,
      byUser: false,
      inner: null,
      items: drafts.map((d, i): Line => ({ seq: -i, round: -1, kind: d.kind, speakerId, byUser: false, text: d.text })),
    });
  }

  const typingSpeaker = typeof live.typing === "string" && !(live.drafts[live.typing]?.length) ? live.typing : null;

  return (
    <>
      {blocks.map((b, i) => (
        <BlockView
          key={b.key}
          block={b}
          previous={blocks[i - 1]}
          scene={scene}
          names={names}
          shown={shown.has(b.key)}
          onToggle={() => toggle(b.key)}
        />
      ))}
      {live.running && live.typing === null && <p className="narration typing">…</p>}
      {typingSpeaker && (
        <div className="turn">
          <Avatar name={names.get(typingSpeaker) ?? ""} size={30} />
          <div className="turn-body">
            <div className="turn-name">{names.get(typingSpeaker)}</div>
            <div className="bubble typing">…</div>
          </div>
        </div>
      )}
    </>
  );
}

function BlockView({
  block,
  previous,
  scene,
  names,
  shown,
  onToggle,
}: {
  block: Block;
  previous: Block | undefined;
  scene: SceneView;
  names: Map<string, string>;
  shown: boolean;
  onToggle: () => void;
}) {
  if (block.type === "narration") return <p className="narration">{block.text}</p>;

  const name = names.get(block.speakerId) ?? "";
  const mine = block.speakerId === scene.possessedId;
  const continued = previous?.type === "turn" && previous.speakerId === block.speakerId;
  const canReveal = block.inner !== null;
  const reveal = canReveal ? { onClick: onToggle, title: shown ? "收起心声" : "点一下看心声", role: "button" } : {};

  return (
    <div className={`turn${mine ? " mine" : ""}${continued ? " continued" : ""}${canReveal ? " revealable" : ""}`}>
      {!mine && (continued ? <span className="avatar-gap" /> : <Avatar name={name} size={30} />)}
      <div className="turn-body">
        {!mine && !continued && <div className="turn-name">{name}</div>}
        {block.items.map((line) =>
          line.kind === "say" ? (
            <div key={line.seq} className="bubble" {...reveal}>
              {line.text}
            </div>
          ) : (
            <div key={line.seq} className={`action${line.kind === "wait" ? " quiet" : ""}`} {...reveal}>
              {actionText(line, name, mine)}
            </div>
          ),
        )}
        {shown && block.inner && (
          <div className="inner">
            <span className="inner-label">心声</span>
            {block.inner}
          </div>
        )}
      </div>
    </div>
  );
}

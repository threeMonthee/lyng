import { useState } from "react";
import { api } from "./api";
import { go } from "./route";
import type { Draft, Overview } from "./types";
import { AutoTextarea, Avatar, ErrorNote, Page, useElapsed } from "./ui";

/** 一句话创作 → 一页预览（全部可以手改）→ 诞生（圣经 §7）。 */
export function CreatePage({ overview, query, refresh }: { overview: Overview; query: URLSearchParams; refresh: () => void }) {
  const [worldId, setWorldId] = useState(query.get("world") ?? "");
  const [request, setRequest] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState<"draft" | "birth" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const elapsed = useElapsed(busy === "draft");
  const world = overview.worlds.find((w) => w.id === worldId);

  const generate = async () => {
    setBusy("draft");
    setError(null);
    try {
      setDraft(await api<Draft>("/drafts", "POST", { request, worldId: worldId || null }));
    } catch (e) {
      setError((e as Error).message);
    }
    setBusy(null);
  };

  const birth = async () => {
    if (!draft) return;
    setBusy("birth");
    setError(null);
    try {
      const born = await api<{ worldId: string; characterIds: string[] }>("/births", "POST", { worldId: worldId || null, draft });
      refresh();
      go(`#/new?world=${born.worldId}&cast=${born.characterIds.join(",")}`);
    } catch (e) {
      setError((e as Error).message);
      setBusy(null);
    }
  };

  if (!draft) {
    return (
      <Page title="创作" subtitle={world ? `在${world.name}里添人物。` : "一句话写下你想进入的世界和想遇见的人。"}>
        <div className="form">
          {overview.worlds.length > 0 && (
            <div className="field">
              <span className="field-label">在哪里</span>
              <div className="chips">
                <button type="button" className={`chip${worldId === "" ? " on" : ""}`} onClick={() => setWorldId("")}>
                  一个新世界
                </button>
                {overview.worlds.map((w) => (
                  <button key={w.id} type="button" className={`chip${w.id === worldId ? " on" : ""}`} onClick={() => setWorldId(w.id)}>
                    {w.name}
                  </button>
                ))}
              </div>
            </div>
          )}
          <label className="field">
            <span className="field-label">一句话</span>
            <AutoTextarea
              className="textarea large"
              value={request}
              onChange={(e) => setRequest(e.target.value)}
              placeholder={world ? "比如：再添一位，碧瑶" : "比如：诛仙，青云大试后，陆雪琪"}
              disabled={busy !== null}
              maxRows={6}
            />
            <span className="field-hint">写了几个人就生成几个人。来自原作的世界，设定与过去取自原作；从开场那一刻起，故事沿着你们走。</span>
          </label>
          {error && <ErrorNote message={error} />}
          <div className="form-actions">
            <button className="btn primary" disabled={busy !== null || !request.trim()} onClick={() => void generate()}>
              {busy === "draft" ? `正在写 · ${elapsed} 秒` : "生成"}
            </button>
            {busy === "draft" && <span className="field-hint">通常要一到两分钟。</span>}
          </div>
        </div>
      </Page>
    );
  }

  const setWorld = (patch: Partial<NonNullable<Draft["world"]>>) => setDraft({ ...draft, world: { ...draft.world!, ...patch } });
  const setCharacter = (i: number, patch: Partial<Draft["characters"][number]>) =>
    setDraft({ ...draft, characters: draft.characters.map((c, j) => (j === i ? { ...c, ...patch } : c)) });

  return (
    <Page
      title="预览"
      subtitle="诞生之前都可以改。诞生之后，档案就是他们的过去，不再改写。"
      actions={
        <>
          <button className="btn" disabled={busy !== null} onClick={() => setDraft(null)}>
            重新写一句
          </button>
          <button className="btn primary" disabled={busy !== null || draft.characters.length === 0} onClick={() => void birth()}>
            {busy === "birth" ? "正在诞生…" : "诞生"}
          </button>
        </>
      }
    >
      <div className="preview">
        {draft.world && (
          <section className="card">
            <input className="title-input" value={draft.world.name} onChange={(e) => setWorld({ name: e.target.value })} aria-label="世界名" />
            <label className="field">
              <span className="field-label">人人知道的事</span>
              <AutoTextarea className="textarea" value={draft.world.publicText} onChange={(e) => setWorld({ publicText: e.target.value })} maxRows={20} />
            </label>
            <label className="field">
              <span className="field-label">只有叙事者知道的事</span>
              <AutoTextarea className="textarea" value={draft.world.narratorText} onChange={(e) => setWorld({ narratorText: e.target.value })} maxRows={12} />
            </label>
          </section>
        )}
        {draft.characters.map((c, i) => (
          <section className="card" key={i}>
            <div className="card-head">
              <Avatar name={c.name || "?"} size={36} />
              <input className="title-input" value={c.name} onChange={(e) => setCharacter(i, { name: e.target.value })} aria-label="姓名" />
              {draft.characters.length > 1 && (
                <button className="btn ghost small" onClick={() => setDraft({ ...draft, characters: draft.characters.filter((_, j) => j !== i) })}>
                  不要这个人
                </button>
              )}
            </div>
            <AutoTextarea className="textarea" value={c.profile} onChange={(e) => setCharacter(i, { profile: e.target.value })} maxRows={30} />
          </section>
        ))}
        {error && <ErrorNote message={error} />}
      </div>
    </Page>
  );
}

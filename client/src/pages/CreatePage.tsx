import { useEffect, useState } from "react";
import { api } from "../api";
import { go } from "../route";
import type { Draft, WorldView } from "../types";
import { ErrorLine, Group, Header } from "../ui";

export function CreatePage() {
  const presetWorld = new URLSearchParams(location.hash.split("?")[1] ?? "").get("world") ?? "";
  const [worlds, setWorlds] = useState<WorldView[]>([]);
  const [worldId, setWorldId] = useState(presetWorld);
  const [request, setRequest] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<WorldView[]>("/worlds").then(setWorlds).catch(() => {});
  }, []);

  const generate = async () => {
    setBusy(true);
    setError(null);
    try {
      setDraft(await api<Draft>("/creation/draft", "POST", { request, worldId: worldId || null }));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const confirm = async () => {
    if (!draft) return;
    setBusy(true);
    setError(null);
    try {
      await api("/creation/confirm", "POST", { draft, worldId: worldId || null });
      go("#/");
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  if (draft) {
    return (
      <div className="page">
        <Header title="预览" back="#/create" />
        <main className="content">
          <p className="hint">这是生成的设定，可以直接修改。确认以后，人物的档案就定下来，不能再改。</p>
          {draft.world && (
            <Group title="世界">
              <label>
                名字
                <input value={draft.world.name} onChange={(e) => setDraft({ ...draft, world: { ...draft.world!, name: e.target.value } })} />
              </label>
              <label>
                人人知道的部分
                <textarea
                  rows={8}
                  value={draft.world.publicText}
                  onChange={(e) => setDraft({ ...draft, world: { ...draft.world!, publicText: e.target.value } })}
                />
              </label>
              <label>
                只有叙事者知道的部分
                <textarea
                  rows={5}
                  value={draft.world.narratorText}
                  onChange={(e) => setDraft({ ...draft, world: { ...draft.world!, narratorText: e.target.value } })}
                />
              </label>
            </Group>
          )}
          {draft.characters.map((c, i) => (
            <Group key={i} title={`人物 ${i + 1}`}>
              <label>
                姓名
                <input
                  value={c.name}
                  onChange={(e) => setDraft({ ...draft, characters: draft.characters.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)) })}
                />
              </label>
              <label>
                档案
                <textarea
                  rows={14}
                  value={c.profile}
                  onChange={(e) => setDraft({ ...draft, characters: draft.characters.map((x, j) => (j === i ? { ...x, profile: e.target.value } : x)) })}
                />
              </label>
            </Group>
          ))}
          <ErrorLine message={error} />
          <div className="actions">
            <button onClick={() => setDraft(null)} disabled={busy}>
              重新写一句
            </button>
            <button className="primary" onClick={confirm} disabled={busy}>
              {busy ? "正在诞生…" : "确认诞生"}
            </button>
          </div>
        </main>
      </div>
    );
  }

  return (
    <div className="page">
      <Header title="创作" back="#/" />
      <main className="content">
        <Group
          title="用一句话描述"
          footer={worldId ? "描述里写了几个人物就会诞生几个。" : "同时描述世界和人物，例如：诛仙，青云大试后，陆雪琪。描述里写了几个人物就会诞生几个。"}
        >
          <label>
            世界
            <select value={worldId} onChange={(e) => setWorldId(e.target.value)}>
              <option value="">新世界</option>
              {worlds.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.name}
                </option>
              ))}
            </select>
          </label>
          <textarea
            aria-label="一句话"
            rows={4}
            placeholder={worldId ? "例如：碧瑶" : "例如：诛仙，青云大试后，陆雪琪"}
            value={request}
            onChange={(e) => setRequest(e.target.value)}
          />
        </Group>
        {busy && <p className="hint">正在生成，可能要半分钟…</p>}
        <ErrorLine message={error} onRetry={generate} />
        <div className="actions">
          <button className="primary" onClick={generate} disabled={busy || !request.trim()}>
            生成
          </button>
        </div>
      </main>
    </div>
  );
}


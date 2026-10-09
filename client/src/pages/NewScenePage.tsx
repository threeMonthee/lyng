import { useEffect, useState } from "react";
import { api } from "../api";
import { go } from "../route";
import type { WorldView } from "../types";
import { ErrorLine, Group, Header } from "../ui";

export function NewScenePage({ query }: { query: URLSearchParams }) {
  const [worlds, setWorlds] = useState<WorldView[]>([]);
  const [worldId, setWorldId] = useState(query.get("world") ?? "");
  const [possessed, setPossessed] = useState(query.get("a") ?? "");
  const [other, setOther] = useState(query.get("b") ?? "");
  const [tone, setTone] = useState("日常相处");
  const [setup, setSetup] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<WorldView[]>("/worlds")
      .then((all) => {
        const playable = all.filter((w) => w.characters.length >= 2);
        setWorlds(playable);
        if (!worldId && playable[0]) setWorldId(playable[0].id);
      })
      .catch((e) => setError((e as Error).message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const world = worlds.find((w) => w.id === worldId);
  const characters = world?.characters ?? [];

  useEffect(() => {
    if (!world) return;
    const ids = world.characters.map((c) => c.id);
    const a = ids.includes(possessed) ? possessed : ids[0];
    const b = ids.includes(other) && other !== a ? other : ids.find((id) => id !== a) ?? "";
    if (a !== possessed) setPossessed(a);
    if (b !== other) setOther(b);
  }, [world, possessed, other]);

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      const scene = await api<{ id: string }>("/scenes", "POST", { possessedId: possessed, otherId: other, tone, setup });
      go(`#/scene/${scene.id}`);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <Header title="开一场戏" back="#/" />
      <main className="content">
        {worlds.length === 0 && <p className="hint">需要先有一个至少两个人物的世界。</p>}
        {worlds.length > 0 && (
          <>
            <Group title="谁在场">
              <label>
                世界
                <select value={worldId} onChange={(e) => setWorldId(e.target.value)}>
                  {worlds.map((w) => (
                    <option key={w.id} value={w.id}>
                      {w.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                你附身的人物
                <select value={possessed} onChange={(e) => setPossessed(e.target.value)}>
                  {characters.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                在场的另一位
                <select value={other} onChange={(e) => setOther(e.target.value)}>
                  {characters
                    .filter((c) => c.id !== possessed)
                    .map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                </select>
              </label>
            </Group>
            <Group title="基调" footer="日常相处：事件轻，重心在人物之间。冒险：张力足，世界的动静多一些。">
              <div className="segmented">
                {["日常相处", "冒险"].map((t) => (
                  <button key={t} className={tone === t ? "on" : ""} onClick={() => setTone(t)}>
                    {t}
                  </button>
                ))}
              </div>
            </Group>
            <Group title="开场" footer="写下处境和基调就够了，没写的共同经历和对白，叙事者不会替你们补。">
              <textarea
                aria-label="开场处境"
                rows={4}
                placeholder="例如：雨夜，客栈后院，阿远刚回来。"
                value={setup}
                onChange={(e) => setSetup(e.target.value)}
              />
            </Group>
            <ErrorLine message={error} />
            <div className="actions">
              <button className="primary" onClick={start} disabled={busy || !setup.trim() || !possessed || !other}>
                开场
              </button>
            </div>
          </>
        )}
      </main>
    </div>
  );
}

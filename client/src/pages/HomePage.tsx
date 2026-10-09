import { useEffect, useState } from "react";
import { api } from "../api";
import { go } from "../route";
import type { SceneSummary, WorldView } from "../types";
import { ErrorLine, Group, Header } from "../ui";

export function HomePage() {
  const [worlds, setWorlds] = useState<WorldView[] | null>(null);
  const [scenes, setScenes] = useState<SceneSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([api<WorldView[]>("/worlds"), api<SceneSummary[]>("/scenes")])
      .then(([w, s]) => {
        setWorlds(w);
        setScenes(s);
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  const open = scenes.filter((s) => s.status === "open");
  const past = scenes.filter((s) => s.status === "ended");
  const canPlay = (worlds ?? []).some((w) => w.characters.length >= 2);

  return (
    <div className="page">
      <Header
        title="lyng"
        right={
          <button className="link" onClick={() => go("#/settings")}>
            设置
          </button>
        }
      />
      <main className="content">
        <ErrorLine message={error} />
        <div className="actions">
          <button className="primary" onClick={() => go("#/create")}>
            创作
          </button>
          <button className="primary" disabled={!canPlay} onClick={() => go("#/new")}>
            开一场戏
          </button>
        </div>
        {worlds && worlds.length === 0 && <p className="hint">还没有世界和人物。点“创作”，用一句话写下你想进入的世界和想遇见的人。</p>}
        {open.length > 0 && (
          <Group title="进行中">
            {open.map((s) => (
              <SceneRow key={s.id} scene={s} />
            ))}
          </Group>
        )}
        {(worlds ?? []).map((w) => (
          <Group key={w.id} title={w.name}>
            {w.characters.map((c) => (
              <button key={c.id} className="row-button" onClick={() => go(`#/character/${c.id}`)}>
                {c.name}
                <span className="chevron">›</span>
              </button>
            ))}
            <button className="row-button accent" onClick={() => go(`#/create?world=${w.id}`)}>
              在这个世界里再创作一个人物
            </button>
          </Group>
        ))}
        {past.length > 0 && (
          <Group title="最近的戏">
            {past.map((s) => (
              <SceneRow key={s.id} scene={s} />
            ))}
          </Group>
        )}
      </main>
    </div>
  );
}

function SceneRow({ scene }: { scene: SceneSummary }) {
  return (
    <button className="row-button" onClick={() => go(`#/scene/${scene.id}`)}>
      <span>
        第 {scene.number} 场 · {scene.possessed} 与 {scene.others.join("、")}
        <span className="sub"> {scene.worldName}</span>
      </span>
      <span className="chevron">›</span>
    </button>
  );
}

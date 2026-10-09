import { useEffect, useState } from "react";
import { api } from "../api";
import type { CharacterView } from "../types";
import { ErrorLine, Group, Header } from "../ui";

export function CharacterPage({ id }: { id: string }) {
  const [c, setC] = useState<CharacterView | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<CharacterView>(`/characters/${id}`)
      .then(setC)
      .catch((e) => setError((e as Error).message));
  }, [id]);

  return (
    <div className="page">
      <Header title={c?.name ?? "人物"} back="#/" />
      <main className="content">
        <ErrorLine message={error} />
        {c && (
          <>
            <p className="hint">{c.worldName}</p>
            <Group title="档案">
              <p className="prose">{c.profile}</p>
            </Group>
            <Group title="TA 记得的事" footer="用 TA 自己的口吻写的，你能看，不能改。">
              {c.memory.memories.length === 0 && <p className="hint">还没有。一场戏结束以后，TA 会回想这一场。</p>}
              {c.memory.memories.map((m) => (
                <p key={m.sceneNumber} className="prose">
                  <span className="sub">第 {m.sceneNumber} 场　</span>
                  {m.text}
                </p>
              ))}
            </Group>
            {c.memory.relations.length > 0 && (
              <Group title="TA 怎么看">
                {c.memory.relations.map((r) => (
                  <p key={r.name} className="prose">
                    <span className="sub">{r.name}　</span>
                    {r.view}
                  </p>
                ))}
              </Group>
            )}
          </>
        )}
      </main>
    </div>
  );
}

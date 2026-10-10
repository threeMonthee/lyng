import { useEffect, useState } from "react";
import { api } from "./api";
import type { CharacterPage as Data } from "./types";
import { Avatar, ErrorNote, formatTime } from "./ui";

/** 人物页：档案、TA 记得的事、TA 怎么看某人、TA 经历过的场景。都只读（圣经 §6.1）。 */
export function CharacterPage({ id }: { id: string }) {
  const [data, setData] = useState<Data | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [profileOpen, setProfileOpen] = useState(false);

  useEffect(() => {
    api<Data>(`/characters/${id}`).then(setData, (e) => setError((e as Error).message));
  }, [id]);

  if (error) return <div className="center-state"><ErrorNote message={error} /></div>;
  if (!data) return <div className="center-state muted">…</div>;
  const { character } = data;

  return (
    <div className="page">
      <header className="person-head">
        <Avatar name={character.name} size={64} />
        <div>
          <h1>{character.name}</h1>
          <p className="page-sub">
            来自<a href={`#/world/${character.worldId}`}>{data.worldName}</a>
          </p>
        </div>
        <div className="page-actions">
          <a className="btn primary" href={`#/new?world=${character.worldId}&cast=${character.id}`}>
            和 TA 开一场
          </a>
        </div>
      </header>

      <section className="section">
        <h2>TA 怎么看</h2>
        {data.views.length === 0 ? (
          <p className="empty">还没有和谁一起经历过一场戏。</p>
        ) : (
          data.views.map((v) => (
            <div key={v.name} className="view-row">
              <Avatar name={v.name} size={28} />
              <div>
                <div className="view-name">{v.name}</div>
                <p className="quote">{v.text}</p>
              </div>
            </div>
          ))
        )}
      </section>

      <section className="section">
        <h2>TA 记得的事</h2>
        {data.memories.length === 0 ? (
          <p className="empty">散场之后，TA 会用自己的口吻记下这一场。</p>
        ) : (
          [...data.memories].reverse().map((m) => (
            <a key={m.sceneId} className="memory" href={`#/scene/${m.sceneId}`}>
              <span className="memory-label">第 {m.sceneNumber} 场</span>
              <p className="quote">{m.memory}</p>
            </a>
          ))
        )}
        <p className="fine">这些是 TA 自己的记忆，你能看，不能改。</p>
      </section>

      <section className="section">
        <h2>档案</h2>
        <div className={`profile${profileOpen ? " open" : ""}`}>{character.profile}</div>
        <button className="link" onClick={() => setProfileOpen(!profileOpen)}>
          {profileOpen ? "收起" : "展开全部"}
        </button>
      </section>

      {data.scenes.length > 0 && (
        <section className="section">
          <h2>经历过的场景</h2>
          {data.scenes.map((s) => (
            <a key={s.id} className="list-row" href={`#/scene/${s.id}`}>
              <span>第 {s.number} 场</span>
              <span className="muted">
                {s.status === "ended" ? "已结束" : "进行中"} · {formatTime(s.startedAt)}
              </span>
            </a>
          ))}
        </section>
      )}
    </div>
  );
}

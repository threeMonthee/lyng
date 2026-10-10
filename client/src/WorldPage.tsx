import { useEffect, useState } from "react";
import { api } from "./api";
import type { WorldPage as Data } from "./types";
import { Avatar, ErrorNote, Page } from "./ui";

export function WorldPage({ id }: { id: string }) {
  const [data, setData] = useState<Data | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [secretOpen, setSecretOpen] = useState(false);

  useEffect(() => {
    api<Data>(`/worlds/${id}`).then(setData, (e) => setError((e as Error).message));
  }, [id]);

  if (error) return <div className="center-state"><ErrorNote message={error} /></div>;
  if (!data) return <div className="center-state muted">…</div>;

  return (
    <Page
      title={data.world.name}
      actions={
        <>
          <a className="btn" href={`#/create?world=${id}`}>
            添人物
          </a>
          <a className="btn primary" href={`#/new?world=${id}`}>
            新的一场
          </a>
        </>
      }
    >
      <section className="section">
        <h2>住在这里的人</h2>
        <div className="people">
          {data.characters.map((c) => (
            <a key={c.id} href={`#/character/${c.id}`} className="person-card">
              <Avatar name={c.name} size={44} />
              <span>{c.name}</span>
            </a>
          ))}
        </div>
      </section>
      <section className="section">
        <h2>人人知道的事</h2>
        <div className="prose">{data.world.publicText}</div>
      </section>
      <section className="section">
        <h2>只有叙事者知道的事</h2>
        {secretOpen ? (
          <div className="prose">{data.world.narratorText}</div>
        ) : (
          <p className="empty">人物不知道这些。看了也不会改变什么，只是剧透。</p>
        )}
        <button className="link" onClick={() => setSecretOpen(!secretOpen)}>
          {secretOpen ? "收起" : "看一眼"}
        </button>
      </section>
    </Page>
  );
}

import type { Overview } from "./types";

export function Home({ overview }: { overview: Overview }) {
  if (overview.worlds.length === 0) {
    return (
      <div className="center-state welcome">
        <h1>进入一个世界，遇见那个人。</h1>
        <p>用一句话写下你想去的作品世界和想遇见的人，比如“诛仙，青云大试后，陆雪琪”。</p>
        <a className="btn primary" href="#/create">
          开始创作
        </a>
      </div>
    );
  }
  const open = overview.scenes.find((s) => s.status === "open");
  return (
    <div className="center-state">
      <h1>他们一直在那里。</h1>
      <p>{open ? "接着上次那一场，或者开新的一场。" : "开新的一场，或者看看他们记得什么。"}</p>
      <div className="row-actions">
        {open && (
          <a className="btn" href={`#/scene/${open.id}`}>
            回到上一场
          </a>
        )}
        <a className="btn primary" href="#/new">
          新的一场
        </a>
      </div>
    </div>
  );
}

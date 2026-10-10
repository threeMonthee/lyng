import { PenIcon } from "./icons";
import type { Route } from "./route";
import type { Overview } from "./types";
import { Avatar, AvatarStack, formatTime } from "./ui";

export function Sidebar({ overview, route }: { overview: Overview | null; route: Route }) {
  const names = new Map(overview?.characters.map((c) => [c.id, c.name]));
  const active = route.path.join("/");

  return (
    <aside className="sidebar">
      <div className="side-top">
        <span className="brand">lyng</span>
        <a className="icon-btn" href="#/create" title="创作世界与人物" aria-label="创作世界与人物">
          <PenIcon />
        </a>
      </div>

      <a className={`new-scene${active === "new" ? " active" : ""}`} href="#/new">
        新的一场
      </a>

      <nav className="side-scroll">
        {overview && overview.scenes.length > 0 && (
          <section>
            <h2 className="side-title">场景</h2>
            {overview.scenes.map((s) => {
              const cast = s.cast.map((id) => names.get(id) ?? "");
              return (
                <a key={s.id} href={`#/scene/${s.id}`} className={`scene-item${active === `scene/${s.id}` ? " active" : ""}`}>
                  <AvatarStack names={cast} size={26} />
                  <span className="scene-item-text">
                    <span className="scene-item-row">
                      <span className="scene-item-title">{cast.join("、")}</span>
                      <span className="scene-item-time">{formatTime(s.startedAt)}</span>
                    </span>
                    <span className={`scene-item-preview${s.failed ? " warn" : ""}`}>
                      {s.failed ? "有一步没有完成" : s.status === "ended" ? `第 ${s.number} 场 · 已结束` : (s.preview ?? "还没有开场")}
                    </span>
                  </span>
                </a>
              );
            })}
          </section>
        )}

        {overview && overview.worlds.length > 0 && (
          <section>
            <h2 className="side-title">世界与人物</h2>
            {overview.worlds.map((w) => (
              <div key={w.id} className="world-group">
                <a href={`#/world/${w.id}`} className={`world-item${active === `world/${w.id}` ? " active" : ""}`}>
                  {w.name}
                </a>
                {overview.characters
                  .filter((c) => c.worldId === w.id)
                  .map((c) => (
                    <a key={c.id} href={`#/character/${c.id}`} className={`char-item${active === `character/${c.id}` ? " active" : ""}`}>
                      <Avatar name={c.name} size={22} />
                      {c.name}
                    </a>
                  ))}
              </div>
            ))}
          </section>
        )}
      </nav>

      {overview && (
        <a className={`side-foot${active === "usage" ? " active" : ""}`} href="#/usage">
          <span>已花 ${overview.spent.toFixed(4)}</span>
          {overview.fake && <span className="fake-mark">替身模式</span>}
        </a>
      )}
    </aside>
  );
}

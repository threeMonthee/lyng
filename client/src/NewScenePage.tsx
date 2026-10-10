import { useState } from "react";
import { api } from "./api";
import { go } from "./route";
import type { Overview } from "./types";
import { AutoTextarea, Avatar, ErrorNote, Page, Segmented } from "./ui";

type Tone = "日常相处" | "冒险";

/** 开一场戏：世界、在场的人、你的身份、基调、开场处境（圣经 §4.1）。 */
export function NewScenePage({ overview, query }: { overview: Overview; query: URLSearchParams }) {
  const [worldId, setWorldId] = useState(query.get("world") ?? overview.worlds[0]?.id ?? "");
  const [cast, setCast] = useState<string[]>(() => (query.get("cast") ?? "").split(",").filter(Boolean));
  const [identity, setIdentity] = useState<string>(query.get("possessed") ?? "");
  const [observeFollow, setObserveFollow] = useState<string>("");
  const [tone, setTone] = useState<Tone>("日常相处");
  const [setup, setSetup] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (overview.worlds.length === 0) {
    return (
      <div className="center-state">
        <h1>还没有世界</h1>
        <p>先用一句话创作一个世界和住在里面的人。</p>
        <a className="btn primary" href="#/create">
          开始创作
        </a>
      </div>
    );
  }

  const people = overview.characters.filter((c) => c.worldId === worldId);
  const name = (id: string) => people.find((p) => p.id === id)?.name ?? "";
  const chosen = cast.filter((id) => people.some((p) => p.id === id));
  const observing = identity === "" || !chosen.includes(identity);
  const follow = chosen.includes(observeFollow) ? observeFollow : chosen[0];
  const possessedOk = observing || chosen.length >= 2;

  const toggle = (id: string) => setCast((c) => (c.includes(id) ? c.filter((x) => x !== id) : [...c, id]));

  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      const { id } = await api<{ id: string }>("/scenes", "POST", {
        worldId,
        cast: chosen,
        possessedId: observing ? null : identity,
        followId: observing ? follow : null,
        tone,
        setup,
      });
      go(`#/scene/${id}`);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  };

  return (
    <Page title="新的一场" subtitle="定下这一场在哪里、有谁、你以什么身份进去。">
      <div className="form">
        {overview.worlds.length > 1 && (
          <label className="field">
            <span className="field-label">世界</span>
            <div className="chips">
              {overview.worlds.map((w) => (
                <button key={w.id} type="button" className={`chip${w.id === worldId ? " on" : ""}`} onClick={() => setWorldId(w.id)}>
                  {w.name}
                </button>
              ))}
            </div>
          </label>
        )}

        <div className="field">
          <span className="field-label">在场的人</span>
          {people.length === 0 ? (
            <p className="field-hint">
              这个世界里还没有人物。<a href={`#/create?world=${worldId}`}>在这里添一个</a>
            </p>
          ) : (
            <div className="chips">
              {people.map((p) => (
                <button key={p.id} type="button" className={`chip person${chosen.includes(p.id) ? " on" : ""}`} onClick={() => toggle(p.id)}>
                  <Avatar name={p.name} size={22} />
                  {p.name}
                </button>
              ))}
            </div>
          )}
        </div>

        {chosen.length > 0 && (
          <div className="field">
            <span className="field-label">你</span>
            <div className="chips">
              {chosen.map((id) => (
                <button key={id} type="button" className={`chip${!observing && identity === id ? " on" : ""}`} onClick={() => setIdentity(id)}>
                  附身{name(id)}
                </button>
              ))}
              <button type="button" className={`chip${observing ? " on" : ""}`} onClick={() => setIdentity("")}>
                旁观
              </button>
            </div>
            {observing && chosen.length > 1 && (
              <div className="chips sub">
                <span className="field-hint">镜头跟随</span>
                {chosen.map((id) => (
                  <button key={id} type="button" className={`chip small${follow === id ? " on" : ""}`} onClick={() => setObserveFollow(id)}>
                    {name(id)}
                  </button>
                ))}
              </div>
            )}
            <p className="field-hint">
              {observing
                ? "你隐身在旁，看他们自己过日子；随时可以附身进去。"
                : !possessedOk
                  ? "附身之后场上还要有一个由 AI 驱动的人，再选一个人，或者旁观。"
                  : `你以${name(identity)}的身份说话、做事；${name(identity)}不会知道自己被附身过。`}
            </p>
          </div>
        )}

        <div className="field">
          <span className="field-label">基调</span>
          <Segmented<Tone>
            value={tone}
            onChange={setTone}
            options={[
              { value: "日常相处", label: "日常相处" },
              { value: "冒险", label: "冒险" },
            ]}
          />
        </div>

        <label className="field">
          <span className="field-label">开场处境</span>
          <AutoTextarea
            className="textarea"
            value={setup}
            onChange={(e) => setSetup(e.target.value)}
            placeholder="地点、时刻、正在发生什么。比如：入夜，山海苑后院，他来找她，手里拿着那根噬魂。"
            maxRows={10}
          />
          <span className="field-hint">叙事者只用你写的这些开场，不替他们补写过去。要跳过一段时间，就写在这里，比如“三年后”。</span>
        </label>

        {error && <ErrorNote message={error} />}
        <div className="form-actions">
          <button className="btn primary" disabled={busy || chosen.length === 0 || !possessedOk || !setup.trim()} onClick={() => void start()}>
            {busy ? "正在开场…" : "开场"}
          </button>
        </div>
      </div>
    </Page>
  );
}

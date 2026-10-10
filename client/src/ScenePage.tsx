import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from "react";
import { api, stream, type StreamEvent } from "./api";
import { CheckIcon, ChevronIcon, SendIcon } from "./icons";
import type { Line, SceneView } from "./types";
import { Timeline, type Live } from "./Timeline";
import { AutoTextarea, AvatarStack, ErrorNote, Popover, Segmented } from "./ui";

type Mode = "说" | "做";

const idle: Live = { running: false, drafts: {}, typing: undefined };

export function ScenePage({ id, refresh }: { id: string; refresh: () => void }) {
  const [scene, setScene] = useState<SceneView | null>(null);
  const [live, setLive] = useState<Live>(idle);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [mode, setMode] = useState<Mode>("说");
  const [text, setText] = useState("");
  const [menu, setMenu] = useState<"identity" | "end" | null>(null);
  const autoOpened = useRef(false);
  const scroller = useRef<HTMLDivElement>(null);
  const stick = useRef(true);

  const reload = useCallback(async () => {
    const fresh = await api<SceneView>(`/scenes/${id}`);
    setScene(fresh);
    return fresh;
  }, [id]);

  useEffect(() => {
    reload().catch((e) => setLoadError((e as Error).message));
  }, [reload]);

  const onEvent = useCallback((e: StreamEvent) => {
    switch (e.event) {
      case "line": {
        const line = e.data as Line;
        setScene((s) => (s ? { ...s, lines: [...s.lines, line] } : s));
        setLive((l) => {
          const drafts = { ...l.drafts };
          if (line.speakerId) delete drafts[line.speakerId];
          return { ...l, drafts, typing: (line.speakerId ?? null) === l.typing ? undefined : l.typing };
        });
        break;
      }
      case "draft":
        setLive((l) => ({
          ...l,
          drafts: { ...l.drafts, [e.data.speakerId]: [...(l.drafts[e.data.speakerId] ?? []), { kind: e.data.kind, text: e.data.text }] },
        }));
        break;
      case "reset":
        setLive((l) => ({ ...l, drafts: { ...l.drafts, [e.data.speakerId]: [] } }));
        break;
      case "typing":
        setLive((l) => ({ ...l, typing: e.data.speakerId ?? null }));
        break;
    }
  }, []);

  /** 跑一个会调用模型的动作。流结束后重新读一次场景，以数据库为准。 */
  const run = useCallback(
    async (path: string, body?: unknown) => {
      setActionError(null);
      setLive({ running: true, drafts: {}, typing: undefined });
      stick.current = true;
      try {
        await stream(`/scenes/${id}/${path}`, body, onEvent);
      } catch (e) {
        setActionError((e as Error).message);
      }
      setLive(idle);
      await reload().catch(() => {});
      refresh();
    },
    [id, onEvent, reload, refresh],
  );

  // 新的一场：自动请叙事者开场
  useEffect(() => {
    if (!scene || autoOpened.current || live.running) return;
    if (scene.pending?.[0] === "opening" && !scene.error && !scene.busy) {
      autoOpened.current = true;
      void run("resume");
    }
  }, [scene, live.running, run]);

  // 页面打开时服务端还在跑（刷新过页面）：等它跑完
  useEffect(() => {
    if (!scene?.busy || live.running) return;
    const timer = window.setInterval(() => void reload().catch(() => {}), 1500);
    return () => window.clearInterval(timer);
  }, [scene?.busy, live.running, reload]);

  // 贴着底部时，新内容出现就跟着滚下去；往上翻看时不打扰
  useEffect(() => {
    const el = scroller.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [scene?.lines.length, live]);

  if (loadError) return <div className="center-state"><ErrorNote message={loadError} /></div>;
  if (!scene) return <div className="center-state muted">…</div>;

  const names = new Map(scene.cast.map((c) => [c.id, c.name]));
  const ended = scene.status === "ended";
  const observing = scene.possessedId === null;
  const blocked = live.running || scene.busy || scene.pending !== null || ended;
  const possessedName = scene.possessedId ? names.get(scene.possessedId) : null;

  const send = (action: "说" | "做" | "静候") => {
    const body = action === "静候" ? "" : text.trim();
    if (action !== "静候" && !body) return;
    if (action !== "静候") setText("");
    void run("act", { action, text: body });
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      if (!blocked) send(mode);
    }
  };

  const setIdentity = async (possessedId: string | null, followId: string | null) => {
    setMenu(null);
    try {
      setScene(await api<SceneView>(`/scenes/${id}/identity`, "PUT", { possessedId, followId }));
      refresh();
    } catch (e) {
      setActionError((e as Error).message);
    }
  };

  const failedStep = scene.pending && scene.error && !live.running ? scene.pending[0] : null;
  const stepLabel = (step: string) =>
    step === "opening" ? "开场没有写出来" : step === "closing" ? "收尾没有写出来" : step === "narrator" ? "叙事者这一步没有完成" : `${names.get(step) ?? "人物"}这一步没有完成`;

  return (
    <div className="scene">
      <header className="scene-head">
        <div className="scene-title">
          <AvatarStack names={scene.cast.map((c) => c.name)} size={30} />
          <div>
            <div className="scene-title-main">{scene.cast.map((c) => c.name).join("、")}</div>
            <div className="scene-title-sub">
              {scene.worldName} · 第 {scene.number} 场 · {scene.tone}
            </div>
          </div>
        </div>
        <div className="scene-tools">
          <div className="anchor">
            <button className="pill" disabled={ended} onClick={() => setMenu(menu === "identity" ? null : "identity")}>
              {observing ? `旁观 · 跟随${names.get(scene.followId ?? "") ?? ""}` : `你是${possessedName}`}
              {!ended && <ChevronIcon />}
            </button>
            <Popover open={menu === "identity"} onClose={() => setMenu(null)}>
              {blocked && <p className="popover-note">这一步完成之后才能切换。</p>}
              <div className="menu-title">附身</div>
              {scene.cast.map((c) => (
                <button key={c.id} className="menu-item" disabled={blocked || scene.cast.length < 2} onClick={() => void setIdentity(c.id, null)}>
                  {c.name}
                  {scene.possessedId === c.id && <CheckIcon />}
                </button>
              ))}
              {scene.cast.length < 2 && <p className="popover-note">只有一个人物时只能旁观。</p>}
              <div className="menu-title">旁观，跟随</div>
              {scene.cast.map((c) => (
                <button key={c.id} className="menu-item" disabled={blocked} onClick={() => void setIdentity(null, c.id)}>
                  {c.name}
                  {observing && scene.followId === c.id && <CheckIcon />}
                </button>
              ))}
            </Popover>
          </div>
          {!ended && (
            <div className="anchor">
              <button className="btn ghost" disabled={live.running || scene.busy} onClick={() => setMenu(menu === "end" ? null : "end")}>
                结束
              </button>
              <Popover open={menu === "end"} onClose={() => setMenu(null)}>
                <p className="popover-text">结束这一场？叙事者会写一两句收尾，这一场就成了他们各自的经历。</p>
                <div className="popover-actions">
                  <button className="btn" onClick={() => setMenu(null)}>
                    再待一会儿
                  </button>
                  <button
                    className="btn primary"
                    onClick={() => {
                      setMenu(null);
                      void run("end", { closing: true });
                    }}
                  >
                    结束这一场
                  </button>
                </div>
              </Popover>
            </div>
          )}
        </div>
      </header>

      <div
        className="scene-scroll"
        ref={scroller}
        onScroll={(e) => {
          const el = e.currentTarget;
          stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
        }}
      >
        <div className="timeline">
          <p className="setup">{scene.setup}</p>
          <Timeline scene={scene} live={live} names={names} />

          {failedStep && (
            <div className="failure">
              <p className="failure-title">{stepLabel(failedStep)}</p>
              <p className="failure-detail">{scene.error}。这是技术失败，不是谁的选择。</p>
              <div className="row-actions">
                <button className="btn primary" onClick={() => void run("resume")}>
                  重试
                </button>
                {failedStep === "closing" && (
                  <button className="btn" onClick={() => void run("end", { closing: false })}>
                    不要收尾，直接结束
                  </button>
                )}
              </div>
            </div>
          )}
          {scene.pending && !scene.error && !scene.busy && !live.running && scene.pending[0] !== "opening" && (
            <div className="failure">
              <p className="failure-title">上次有一步没有跑完</p>
              <div className="row-actions">
                <button className="btn primary" onClick={() => void run("resume")}>
                  接着做完
                </button>
              </div>
            </div>
          )}
          {actionError && <ErrorNote message={actionError} />}
          {ended && (
            <div className="ended">
              <p>这一场结束了。</p>
              <a
                className="btn"
                href={`#/new?world=${scene.worldId}&cast=${scene.cast.map((c) => c.id).join(",")}${scene.possessedId ? `&possessed=${scene.possessedId}` : ""}`}
              >
                再开一场
              </a>
            </div>
          )}
          {!ended && scene.lines.length === 1 && !live.running && (
            <p className="hint">点一下人物的话，可以看到 TA 没说出口的心声。</p>
          )}
        </div>
      </div>

      {!ended && (
        <footer className="composer">
          {observing ? (
            <div className="observe-bar">
              <button className="btn primary" disabled={blocked} onClick={() => void run("act", { action: "继续" })}>
                继续
              </button>
              <span className="hint-inline">点一下，看他们往下走一段。想进去，就在右上角附身一个人。</span>
            </div>
          ) : (
            <div className="compose-row">
              <Segmented<Mode>
                value={mode}
                onChange={setMode}
                options={[
                  { value: "说", label: "说" },
                  { value: "做", label: "做" },
                ]}
              />
              <div className="input-wrap">
                <AutoTextarea
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                  onKeyDown={onKeyDown}
                  maxRows={8}
                  placeholder={mode === "说" ? `以${possessedName}的身份说话` : "写下你做的事，不用写主语：把伞往她那边倾了倾"}
                />
                <button className="send" disabled={blocked || !text.trim()} onClick={() => send(mode)} aria-label={mode}>
                  <SendIcon />
                </button>
              </div>
              <button className="btn ghost" disabled={blocked} onClick={() => send("静候")} title="不开口，看对方怎么做">
                静候
              </button>
            </div>
          )}
        </footer>
      )}
    </div>
  );
}

import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../api";
import { go } from "../route";
import { streamPost } from "../sse";
import { apply, begin, emptyTimeline, fail, fromScene, unresolvedFailures, visibleEvents, type TimelineState } from "../timeline";
import type { Beat, SceneEvent, SceneView } from "../types";
import { useLongPress } from "../useLongPress";
import { Avatar, ErrorLine, Header } from "../ui";

const STEP_LABEL: Record<string, string> = {
  opening: "叙事者的开场",
  narrator: "叙事者这一步",
  closing: "叙事者的收尾",
};

export function ScenePage({ id }: { id: string }) {
  const [scene, setScene] = useState<SceneView | null>(null);
  const [tl, setTl] = useState<TimelineState>(emptyTimeline);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [mode, setMode] = useState<"说" | "做">("说");
  const [text, setText] = useState("");
  const [confirmEnd, setConfirmEnd] = useState(false);
  const [innerShown, setInnerShown] = useState<Set<number>>(new Set());
  const opened = useRef(false);
  const bottom = useRef<HTMLDivElement>(null);

  const reload = useCallback(async () => {
    const fresh = await api<SceneView>(`/scenes/${id}`);
    setScene(fresh);
    setTl((s) => ({ ...fromScene(fresh), error: s.error }));
    return fresh;
  }, [id]);

  useEffect(() => {
    reload().catch((e) => setLoadError((e as Error).message));
  }, [reload]);

  const run = useCallback(
    async (path: string, body?: unknown) => {
      setTl(begin);
      try {
        await streamPost(path, body, (m) => setTl((s) => apply(s, m)));
      } catch (e) {
        setTl((s) => fail(s, (e as Error).message));
      }
      await reload().catch(() => {});
    },
    [reload],
  );

  // 新建的一场：叙事者开场
  useEffect(() => {
    if (!scene || opened.current) return;
    if (scene.status === "open" && !scene.opened && !scene.busy && unresolvedFailures(scene.events).length === 0) {
      opened.current = true;
      void run(`/scenes/${id}/open`);
    }
  }, [scene, id, run]);

  // 刷新页面时服务端还在跑一轮：等它跑完
  useEffect(() => {
    if (!scene?.busy || tl.running) return;
    const timer = window.setInterval(() => void reload().catch(() => {}), 1500);
    return () => window.clearInterval(timer);
  }, [scene?.busy, tl.running, reload]);

  useEffect(() => {
    bottom.current?.scrollIntoView({ block: "end" });
  }, [tl.events.length, tl.drafts, tl.running, innerShown]);

  if (loadError) return <div className="page"><Header title="" back="#/" /><main className="content"><ErrorLine message={loadError} /></main></div>;
  if (!scene) return <div className="center-note">…</div>;

  const failures = unresolvedFailures(tl.events);
  const ended = scene.status === "ended";
  const locked = tl.running || scene.busy || failures.length > 0 || !scene.opened || ended;
  const ai = scene.others[0];

  const send = (action: "说" | "做" | "静候", body: string) => {
    setText("");
    void run(`/scenes/${id}/act`, { action, text: body });
  };
  const toggleInner = (seq: number) =>
    setInnerShown((s) => {
      const next = new Set(s);
      if (!next.delete(seq)) next.add(seq);
      return next;
    });

  const waitingForAi = tl.running && Object.keys(tl.drafts).length === 0;

  return (
    <div className="scene page">
      <Header
        title={`${scene.possessed.name} · ${scene.others.map((o) => o.name).join("、")}`}
        back="#/"
        right={
          !ended && scene.opened && (
            <button className="link" disabled={tl.running} onClick={() => setConfirmEnd(true)}>
              结束
            </button>
          )
        }
      />
      <div className="timeline">
        {tl.events.length <= 1 && !ended && <p className="hint center">长按人物的话，可以看到 TA 没说出口的心声。</p>}
        {visibleEvents(tl.events).map((e) => (
          <EventItem
            key={e.seq}
            event={e}
            scene={scene}
            innerShown={innerShown.has(e.seq)}
            onToggleInner={() => toggleInner(e.seq)}
            onRetry={() => void run(`/scenes/${id}/retry`)}
            onSkipClosing={() => void run(`/scenes/${id}/end?closing=false`)}
            busy={tl.running}
          />
        ))}
        {Object.entries(tl.drafts).map(([name, beats]) => (
          <Turn key={`draft-${name}`} name={name} beats={beats} inner={null} mine={false} shown={false} onToggle={() => {}} />
        ))}
        {waitingForAi && (
          <div className="narration wait">{scene.opened && ai ? `${ai.name} …` : "…"}</div>
        )}
        <ErrorLine message={tl.error} />
        <div ref={bottom} />
      </div>

      {ended ? (
        <footer className="composer ended">
          <p>这一场结束了。</p>
          <div className="actions">
            <button onClick={() => go("#/")}>回首页</button>
            <button className="primary" onClick={() => go(`#/new?world=${scene.worldId}&a=${scene.possessed.id}&b=${scene.others[0]?.id ?? ""}`)}>
              再开一场
            </button>
          </div>
        </footer>
      ) : confirmEnd ? (
        <footer className="composer">
          <p>结束这场戏？</p>
          <div className="actions">
            <button onClick={() => setConfirmEnd(false)}>取消</button>
            <button onClick={() => { setConfirmEnd(false); void run(`/scenes/${id}/end?closing=false`); }}>直接结束</button>
            <button className="primary" onClick={() => { setConfirmEnd(false); void run(`/scenes/${id}/end?closing=true`); }}>
              收个尾再结束
            </button>
          </div>
        </footer>
      ) : (
        <footer className="composer">
          {failures.length > 0 && !tl.running && <p className="hint">上一步没有成功，重试以后才能继续。</p>}
          <div className="composer-top">
            <div className="segmented small">
              {(["说", "做"] as const).map((m) => (
                <button key={m} className={mode === m ? "on" : ""} onClick={() => setMode(m)} disabled={locked}>
                  {m}
                </button>
              ))}
            </div>
            <button className="link" disabled={locked} onClick={() => send("静候", "")}>
              静候
            </button>
          </div>
          <div className="composer-row">
            <textarea
              aria-label={mode === "说" ? "你说" : "你做"}
              rows={1}
              placeholder={mode === "说" ? "说…" : "做…"}
              value={text}
              disabled={locked}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing && text.trim()) {
                  e.preventDefault();
                  send(mode, text);
                }
              }}
            />
            <button className="send" disabled={locked || !text.trim()} onClick={() => send(mode, text)} aria-label="发送">
              发送
            </button>
          </div>
        </footer>
      )}
    </div>
  );
}

function EventItem(props: {
  event: SceneEvent;
  scene: SceneView;
  innerShown: boolean;
  onToggleInner: () => void;
  onRetry: () => void;
  onSkipClosing: () => void;
  busy: boolean;
}) {
  const { event: e, scene } = props;
  switch (e.kind) {
    case "narration":
      return <div className="narration">{e.text}</div>;
    case "user":
      return <UserEvent event={e} />;
    case "character":
      return <Turn name={e.speaker!} beats={e.silent ? [{ tag: "行", text: `${e.speaker}没有开口。` }] : e.beats} inner={e.inner} mine={false} shown={props.innerShown} onToggle={props.onToggleInner} />;
    case "failure": {
      const who = e.step === "character" ? `${e.speaker ?? scene.others[0]?.name}的回应` : STEP_LABEL[e.step ?? ""] ?? "这一步";
      return (
        <div className="failure" role="alert">
          <p>
            {who}没有成功：{e.text}
          </p>
          <div className="actions">
            <button className="primary" disabled={props.busy} onClick={props.onRetry}>
              重试
            </button>
            {e.step === "closing" && (
              <button disabled={props.busy} onClick={props.onSkipClosing}>
                直接结束
              </button>
            )}
          </div>
        </div>
      );
    }
    default:
      return null;
  }
}

function UserEvent({ event: e }: { event: SceneEvent }) {
  if (e.action === "说") return <Turn name={e.speaker!} beats={[{ tag: "说", text: e.text }]} inner={null} mine shown={false} onToggle={() => {}} />;
  const line = e.action === "做" ? e.text : "你没有开口。";
  return <div className="action right">{line}</div>;
}

function Turn(props: { name: string; beats: Beat[]; inner: string | null; mine: boolean; shown: boolean; onToggle: () => void }) {
  const press = useLongPress(props.onToggle);
  const side = props.mine ? "right" : "left";
  return (
    <div className={`turn ${side}`} {...(props.inner ? press : {})}>
      <div className={`speaker ${side}`}>
        <Avatar name={props.name} />
        <span className="name">{props.name}</span>
      </div>
      {props.beats.map((b, i) =>
        b.tag === "说" ? (
          <div key={i} className={`bubble ${props.mine ? "me" : "ai"}`}>
            {b.text}
          </div>
        ) : (
          <div key={i} className={`action ${side}`}>
            {b.text}
          </div>
        ),
      )}
      {props.shown && props.inner && <div className={`inner ${side}`}>{props.inner}</div>}
    </div>
  );
}

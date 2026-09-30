"""匿名盲评页：同一场景、不同模型并排；只出现代号，不出现模型 ID。"""

import json

from dataset import Dataset

PAGE = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>盲评对照</title>
<style>
:root {
  --bg: #f5f5f7; --panel: #ffffff; --text: #1d1d1f; --muted: #6e6e73; --faint: #a1a1a6;
  --line: #e5e5ea; --bubble: #e9e9eb; --me: #0a84ff; --me-text: #ffffff; --inner: #f2f2f7;
  --fail: #c9342b; --fail-bg: #fdecea;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --bg: #000000; --panel: #1c1c1e; --text: #f5f5f7; --muted: #a1a1a6; --faint: #6e6e73;
    --line: #2c2c2e; --bubble: #2c2c2e; --me: #0a84ff; --me-text: #ffffff; --inner: #2c2c2e;
    --fail: #ff6b60; --fail-bg: #3a1d1b;
  }
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--text);
  font: 15px/1.6 -apple-system, BlinkMacSystemFont, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif; }
header { padding: 16px; max-width: 1400px; margin: 0 auto; }
h1 { font-size: 17px; margin: 0 0 4px; font-weight: 600; }
.hint { color: var(--muted); font-size: 13px; margin: 0; }
nav { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; }
nav button { border: 1px solid var(--line); background: var(--panel); color: var(--text);
  border-radius: 8px; padding: 4px 10px; font: inherit; font-size: 13px; cursor: pointer; }
nav button[aria-pressed="true"] { background: var(--text); color: var(--bg); border-color: var(--text); }
main { max-width: 1400px; margin: 0 auto; padding: 0 16px 48px; }
details { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 8px 12px; margin-bottom: 12px; }
summary { cursor: pointer; color: var(--muted); font-size: 13px; }
details pre { white-space: pre-wrap; font: inherit; font-size: 13px; margin: 8px 0 0; }
.grid { display: grid; grid-auto-flow: column; grid-auto-columns: minmax(300px, 1fr); gap: 12px; overflow-x: auto; padding-bottom: 8px; }
.col { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; min-width: 0; }
.col h2 { font-size: 15px; margin: 0 0 12px; text-align: center; font-weight: 600; }
.banner { color: var(--muted); font-size: 13px; text-align: center; padding: 8px; border: 1px dashed var(--line); border-radius: 8px; margin-bottom: 12px; }
.narr { color: var(--muted); text-align: center; font-size: 14px; margin: 14px 8px; white-space: pre-wrap; }
.row { display: flex; gap: 8px; margin: 8px 0; align-items: flex-start; }
.row.me { flex-direction: row-reverse; }
.avatar { flex: none; width: 28px; height: 28px; border-radius: 50%; background: var(--bubble); color: var(--muted);
  display: flex; align-items: center; justify-content: center; font-size: 13px; }
.who { font-size: 12px; color: var(--muted); margin-bottom: 2px; }
.row.me .who { text-align: right; }
.bubble { background: var(--bubble); border-radius: 16px; padding: 7px 12px; white-space: pre-wrap; display: inline-block; max-width: 100%; }
.row.me .bubble { background: var(--me); color: var(--me-text); }
.stack { max-width: 80%; display: flex; flex-direction: column; }
.row.me .stack { align-items: flex-end; }
.act { color: var(--muted); font-size: 14px; margin: 6px 36px; white-space: pre-wrap; }
.act.me { text-align: right; }
.tap { cursor: pointer; }
.inner { background: var(--inner); color: var(--muted); font-size: 13px; border-radius: 10px; padding: 6px 10px; margin: 4px 36px 8px; white-space: pre-wrap; }
.meta { color: var(--faint); font-size: 12px; text-align: center; margin: 8px 0; }
.fail { color: var(--fail); background: var(--fail-bg); font-size: 13px; border-radius: 8px; padding: 6px 10px; margin: 8px 0; }
</style>
</head>
<body>
<header>
  <h1>盲评对照</h1>
  <p class="hint">同一场景，不同模型并排，模型以代号表示。点气泡或动作可看心声。</p>
  <nav id="scenes" aria-label="场景"></nav>
  <nav id="repeats" aria-label="第几次"></nav>
</header>
<main>
  <details><summary>开场处境与评估要点</summary><pre id="brief"></pre></details>
  <div class="grid" id="grid"></div>
</main>
<script id="data" type="application/json">__DATA__</script>
<script>
const DATA = JSON.parse(document.getElementById("data").textContent);
const STATUS = { failed: "技术失败", refused: "服务商拒绝", stopped: "运行中止", not_run: "未执行" };
let cur = { key: DATA.variants[0] && DATA.variants[0].key, repeat: 1 };

function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
}

function innerToggle(targets, inner, after) {
  let box = null;
  for (const t of targets) {
    t.classList.add("tap");
    t.addEventListener("click", () => {
      if (box) { box.remove(); box = null; return; }
      box = el("div", "inner", inner);
      after().after(box);
    });
  }
}

function bubble(name, text, me) {
  const row = el("div", "row" + (me ? " me" : ""));
  row.append(el("div", "avatar", name.slice(0, 1)));
  const stack = el("div", "stack");
  stack.append(el("div", "who", name));
  const b = el("div", "bubble", text);
  stack.append(b);
  row.append(stack);
  return [row, b];
}

function renderEvents(box, events, possessed) {
  for (const e of events) {
    if (e.kind === "narration") box.append(el("div", "narr", e.text));
    else if (e.kind === "note") box.append(el("div", "meta", e.text));
    else if (e.kind === "failure") box.append(el("div", "fail", "技术失败 · " + e.speaker + " · " + e.text));
    else if (e.kind === "user") {
      if (e.action === "说") box.append(bubble(e.speaker, e.text, true)[0]);
      else if (e.action === "做") box.append(el("div", "act me", e.text));
      else box.append(el("div", "meta", "· 静候 ·"));
    } else if (e.kind === "character") {
      const nodes = [], taps = [];
      if (e.silent) {
        const n = el("div", "act", e.speaker + " 没有开口");
        nodes.push(n); taps.push(n);
      }
      for (const [tag, text] of (e.beats || [])) {
        if (tag === "说") { const [row, b] = bubble(e.speaker, text, e.speaker === possessed); nodes.push(row); taps.push(b); }
        else { const n = el("div", "act", text); nodes.push(n); taps.push(n); }
      }
      box.append(...nodes);
      innerToggle(taps, e.inner, () => nodes[nodes.length - 1]);
    }
  }
}

function render() {
  const variant = DATA.variants.find(v => v.key === cur.key);
  const scenes = document.getElementById("scenes");
  scenes.replaceChildren(...DATA.variants.map(v => {
    const b = el("button", null, v.label);
    b.setAttribute("aria-pressed", v.key === cur.key);
    b.onclick = () => { cur.key = v.key; render(); };
    return b;
  }));
  const repeats = document.getElementById("repeats");
  repeats.replaceChildren(...DATA.repeats.map(r => {
    const b = el("button", null, "第 " + r + " 次");
    b.setAttribute("aria-pressed", r === cur.repeat);
    b.onclick = () => { cur.repeat = r; render(); };
    return b;
  }));
  if (!variant) return;
  document.getElementById("brief").textContent = variant.brief;
  const grid = document.getElementById("grid");
  grid.replaceChildren();
  for (const code of DATA.codes) {
    const unit = DATA.units.find(u => u.key === variant.key && u.repeat === cur.repeat && u.code === code);
    if (!unit) continue;
    const col = el("section", "col");
    col.append(el("h2", null, code));
    if (unit.status !== "completed") col.append(el("div", "banner", STATUS[unit.status] + (unit.reason ? "：" + unit.reason : "")));
    renderEvents(col, unit.events, variant.possessed);
    grid.append(col);
  }
}
render();
</script>
</body>
</html>
"""


def blind_html(ds: Dataset, record: dict) -> str:
    variants, seen = [], set()
    for u in record["units"]:
        key = f"{u['scene']}·{u['scale']}"
        if key in seen:
            continue
        seen.add(key)
        scene = ds.scenes[u["scene"]]
        multi = len(scene.scales) > 1
        variants.append({
            "key": key,
            "label": f"{scene.id} {scene.name}" + (f"·{u['scale']}" if multi else ""),
            "possessed": scene.possessed,
            "brief": f"【开场处境】\n{scene.opening}\n\n【评估要点】\n{scene.notes}",
        })
    units = [
        {"key": f"{u['scene']}·{u['scale']}", "code": u["code"], "repeat": u["repeat"],
         "status": u["status"], "reason": u.get("reason"), "events": u["events"]}
        for u in record["units"]
    ]
    data = {
        "codes": record["codes"],
        "repeats": sorted({u["repeat"] for u in record["units"]}),
        "variants": variants,
        "units": units,
    }
    payload = json.dumps(data, ensure_ascii=False).replace("</", "<\\/")
    return PAGE.replace("__DATA__", payload)

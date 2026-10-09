import { useEffect, useState } from "react";
import { api } from "../api";
import type { ModelCall } from "../types";
import { ErrorLine, Group, Header } from "../ui";

interface Settings {
  models: Record<string, string>;
  defaultModel: string;
  preferredProvider: string | null;
}

interface Usage {
  totalCost: number;
  budget: number | null;
  recent: ModelCall[];
}

const ROLES: [string, string][] = [
  ["character", "人物"],
  ["narrator", "叙事者（开场与收尾）"],
  ["narrator_round", "叙事者（逐轮决定）"],
  ["creation", "创作"],
  ["recall", "回想"],
];

export function SettingsPage({ onLogout }: { onLogout: () => void }) {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [usage, setUsage] = useState<Usage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    api<Settings>("/settings").then((s) => {
      setSettings(s);
      setDraft(s.models);
    }).catch((e) => setError((e as Error).message));
    api<Usage>("/usage").then(setUsage).catch(() => {});
  }, []);

  const save = async () => {
    setError(null);
    try {
      const s = await api<Settings>("/settings", "PUT", draft);
      setSettings(s);
      setDraft(s.models);
      setSaved(true);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <div className="page">
      <Header title="设置" back="#/" />
      <main className="content">
        <ErrorLine message={error} />
        {settings && (
          <Group
            title="模型"
            footer={`写“模型ID”或“模型ID@推理设置”（off、low、medium、high），不写推理设置就用模型默认。留空恢复默认（${settings.defaultModel}）。首选服务商：${settings.preferredProvider || "不指定"}，故障时回退。`}
          >
            {ROLES.map(([key, label]) => (
              <label key={key}>
                {label}
                <input value={draft[key] ?? ""} onChange={(e) => { setSaved(false); setDraft({ ...draft, [key]: e.target.value }); }} />
              </label>
            ))}
            <div className="actions">
              <button className="primary" onClick={save}>
                {saved ? "已保存" : "保存"}
              </button>
            </div>
          </Group>
        )}
        {usage && (
          <Group title="用量" footer="每次模型调用都记账：服务商、耗时、费用。">
            <p className="prose">
              累计 ${usage.totalCost.toFixed(4)}
              {usage.budget !== null && ` / 上限 $${usage.budget}`}
            </p>
            <div className="calls">
              {usage.recent.slice(0, 20).map((c) => (
                <div key={c.id} className={`call ${c.outcome === "ok" ? "" : "bad"}`}>
                  <span>{c.kind}{c.subject ? `·${c.subject}` : ""}</span>
                  <span>{c.provider ?? "—"}</span>
                  <span>{c.latencyMs !== null ? `${(c.latencyMs / 1000).toFixed(1)}s` : "—"}</span>
                  <span>{c.cost !== null ? `$${c.cost}` : "—"}</span>
                  <span>{c.outcome === "ok" ? "" : (c.error ?? c.outcome)}</span>
                </div>
              ))}
            </div>
          </Group>
        )}
        <div className="actions">
          <button onClick={onLogout}>退出登录</button>
        </div>
      </main>
    </div>
  );
}

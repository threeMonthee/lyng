import { useEffect, useState } from "react";
import { api } from "./api";
import type { Usage } from "./types";
import { ErrorNote, Page } from "./ui";

const KIND: Record<string, string> = {
  opening: "开场",
  round: "叙事者",
  character: "人物",
  closing: "收尾",
  recall: "回想",
  draft: "创作",
};

/** 每次模型调用都记账（requires §4）：用途、模型、服务商、耗时、费用、结果。 */
export function UsagePage() {
  const [usage, setUsage] = useState<Usage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Usage>("/usage").then(setUsage, (e) => setError((e as Error).message));
  }, []);

  if (error) return <div className="center-state"><ErrorNote message={error} /></div>;
  if (!usage) return <div className="center-state muted">…</div>;

  return (
    <Page
      wide
      title="用量"
      subtitle={
        <>
          累计 ${usage.spent.toFixed(4)}
          {usage.budget !== null && ` · 上限 $${usage.budget}`}
          {usage.fake && " · 替身模式，不花钱"}
        </>
      }
    >
      {usage.calls.length === 0 ? (
        <p className="empty">还没有调用过模型。</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>时间</th>
              <th>用途</th>
              <th>模型</th>
              <th>服务商</th>
              <th className="num">耗时</th>
              <th className="num">费用</th>
              <th>结果</th>
            </tr>
          </thead>
          <tbody>
            {usage.calls.map((c, i) => (
              <tr key={i}>
                <td className="muted">{new Date(c.createdAt).toLocaleString("zh-CN", { hour12: false })}</td>
                <td>
                  {KIND[c.kind] ?? c.kind}
                  {c.subject && <span className="muted"> · {c.subject}</span>}
                </td>
                <td className="mono">{c.model}</td>
                <td>{c.provider ?? "—"}</td>
                <td className="num">{c.latencyMs === null ? "—" : `${(c.latencyMs / 1000).toFixed(1)}s`}</td>
                <td className="num">{c.cost === null ? "—" : `$${c.cost.toFixed(5)}`}</td>
                <td className={c.outcome === "ok" ? "" : "warn"} title={c.error ?? undefined}>
                  {c.outcome === "ok" ? "成功" : c.outcome === "refused" ? "被拒绝" : "失败"}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Page>
  );
}

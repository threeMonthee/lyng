import type { ReactNode } from "react";
import { go } from "./route";

const AVATAR_COLORS = ["#8e8e93", "#7d8b99", "#8f8a7a", "#85938a", "#9a8690", "#8a8fa6"];

export function Avatar({ name }: { name: string }) {
  const hue = [...name].reduce((sum, c) => sum + c.charCodeAt(0), 0) % AVATAR_COLORS.length;
  return (
    <span className="avatar" style={{ background: AVATAR_COLORS[hue] }} aria-hidden>
      {name.slice(0, 1)}
    </span>
  );
}

export function Header({ title, back, right }: { title: string; back?: string; right?: ReactNode }) {
  return (
    <header className="header">
      <div className="header-side">
        {back !== undefined && (
          <button className="link" onClick={() => go(back)}>
            ‹ 返回
          </button>
        )}
      </div>
      <h1>{title}</h1>
      <div className="header-side right">{right}</div>
    </header>
  );
}

export function Group({ title, children, footer }: { title?: string; children: ReactNode; footer?: string }) {
  return (
    <section className="group">
      {title && <h2>{title}</h2>}
      <div className="group-body">{children}</div>
      {footer && <p className="footer">{footer}</p>}
    </section>
  );
}

export function ErrorLine({ message, onRetry }: { message: string | null; onRetry?: () => void }) {
  if (!message) return null;
  return (
    <p className="error" role="alert">
      {message}
      {onRetry && (
        <>
          {" "}
          <button className="link" onClick={onRetry}>
            重试
          </button>
        </>
      )}
    </p>
  );
}

import { useEffect, useLayoutEffect, useRef, useState, type ReactNode, type TextareaHTMLAttributes } from "react";

/** 彼此分得开的几种低饱和色相。 */
const HUES = [8, 32, 140, 178, 208, 236, 280, 330];

/** 由姓名决定的色相：同一个人永远是同一种颜色。 */
export function hueOf(name: string): number {
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.codePointAt(0)!) >>> 0;
  return HUES[h % HUES.length];
}

export function Avatar({ name, size = 32 }: { name: string; size?: number }) {
  return (
    <span
      className="avatar"
      style={{ width: size, height: size, fontSize: size * 0.44, ["--h" as string]: hueOf(name) }}
      aria-hidden
    >
      {Array.from(name)[0]}
    </span>
  );
}

export function AvatarStack({ names, size = 22 }: { names: string[]; size?: number }) {
  return (
    <span className="avatar-stack">
      {names.slice(0, 3).map((n) => (
        <Avatar key={n} name={n} size={size} />
      ))}
    </span>
  );
}

export function Segmented<T extends string>({
  value,
  options,
  onChange,
  disabled,
}: {
  value: T;
  options: { value: T; label: ReactNode }[];
  onChange: (v: T) => void;
  disabled?: boolean;
}) {
  return (
    <div className="segmented" role="radiogroup">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={o.value === value}
          className={o.value === value ? "on" : ""}
          disabled={disabled}
          onClick={() => onChange(o.value)}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

/** 随内容长高的输入框。 */
export function AutoTextarea(props: TextareaHTMLAttributes<HTMLTextAreaElement> & { maxRows?: number }) {
  const { maxRows = 12, ...rest } = props;
  const ref = useRef<HTMLTextAreaElement>(null);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = "auto";
    const style = getComputedStyle(el);
    const line = parseFloat(style.lineHeight) || 24;
    const border = el.offsetHeight - el.clientHeight;
    const max = line * maxRows + parseFloat(style.paddingTop) + parseFloat(style.paddingBottom);
    el.style.height = `${Math.min(el.scrollHeight, max) + border}px`;
    el.style.overflowY = el.scrollHeight > max ? "auto" : "hidden";
  }, [props.value, maxRows]);
  return <textarea ref={ref} rows={1} {...rest} />;
}

export function ErrorNote({ message }: { message: string }) {
  return <p className="error-note">{message}</p>;
}

/** 已用时间，按秒走。用在要等很久的生成上，让人知道它还在动。 */
export function useElapsed(running: boolean): number {
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    if (!running) return;
    setSeconds(0);
    const started = Date.now();
    const timer = window.setInterval(() => setSeconds(Math.floor((Date.now() - started) / 1000)), 1000);
    return () => window.clearInterval(timer);
  }, [running]);
  return seconds;
}

/** 点外面就关的小菜单。 */
export function Popover({ open, onClose, children }: { open: boolean; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="popover" ref={ref}>
      {children}
    </div>
  );
}

export function Page({
  title,
  subtitle,
  actions,
  wide,
  children,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  wide?: boolean;
  children: ReactNode;
}) {
  return (
    <div className={`page${wide ? " wide" : ""}`}>
      <header className="page-head">
        <div>
          <h1>{title}</h1>
          {subtitle && <p className="page-sub">{subtitle}</p>}
        </div>
        {actions && <div className="page-actions">{actions}</div>}
      </header>
      {children}
    </div>
  );
}

export function formatTime(iso: string): string {
  const d = new Date(iso);
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  const hm = d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });
  if (sameDay) return hm;
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (d.toDateString() === yesterday.toDateString()) return "昨天";
  return d.toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" });
}

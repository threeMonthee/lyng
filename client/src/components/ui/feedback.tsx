import type { ReactNode } from 'react'
import { AlertCircle, RotateCw } from 'lucide-react'
import { cn } from '@/lib/cn'

export function Spinner({ className }: { className?: string }) {
  return (
    <svg className={cn('h-5 w-5 animate-spin text-current', className)} viewBox="0 0 24 24" fill="none" aria-hidden>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.2" strokeWidth="2.5" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
    </svg>
  )
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('animate-pulse rounded-lg bg-surface-2', className)} />
}

/** 整页加载中：几条骨架，比转圈更安静。 */
export function PageLoading({ rows = 4 }: { rows?: number }) {
  return (
    <div className="space-y-3 p-4 md:p-0" aria-busy="true" aria-label="加载中">
      <Skeleton className="h-7 w-40" />
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-16 w-full" />
      ))}
    </div>
  )
}

export function Empty({ icon, title, children, action }: { icon?: ReactNode; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      {icon ? <div className="mb-3 text-faint">{icon}</div> : null}
      <p className="text-[15px] font-medium text-text">{title}</p>
      {children ? <div className="mt-1.5 max-w-sm text-sm leading-relaxed text-muted">{children}</div> : null}
      {action ? <div className="mt-5">{action}</div> : null}
    </div>
  )
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      <AlertCircle className="mb-3 h-6 w-6 text-danger" />
      <p className="text-[15px] font-medium">没能打开</p>
      <p className="mt-1.5 max-w-sm text-sm text-muted">{message}</p>
      {onRetry ? (
        <button onClick={onRetry} className="mt-4 inline-flex items-center gap-1.5 text-sm font-medium text-accent">
          <RotateCw className="h-4 w-4" />
          再试一次
        </button>
      ) : null}
    </div>
  )
}

/** 一行提示：失败、注意或普通说明。 */
export function Notice({
  tone = 'info',
  children,
  action,
  className,
}: {
  tone?: 'info' | 'warn' | 'danger'
  children: ReactNode
  action?: ReactNode
  className?: string
}) {
  const tones = {
    info: 'bg-surface-2 text-muted',
    warn: 'bg-warn-soft text-warn',
    danger: 'bg-danger-soft text-danger',
  }
  return (
    <div className={cn('flex items-start gap-3 rounded-xl px-3.5 py-2.5 text-[13px] leading-relaxed', tones[tone], className)}>
      <div className="min-w-0 flex-1">{children}</div>
      {action ? <div className="shrink-0">{action}</div> : null}
    </div>
  )
}

/** 正在生成：三个点。 */
export function Typing({ label, className }: { label?: string; className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-2 text-[13px] text-muted', className)}>
      <span className="inline-flex gap-1" aria-hidden>
        <span className="typing-dot h-1.5 w-1.5 rounded-full bg-current" />
        <span className="typing-dot h-1.5 w-1.5 rounded-full bg-current" />
        <span className="typing-dot h-1.5 w-1.5 rounded-full bg-current" />
      </span>
      {label}
    </span>
  )
}

import type { ReactNode } from 'react'
import { Check } from 'lucide-react'
import { cn } from '@/lib/cn'

/** 分段选择（说 / 做 / 静候、日常 / 冒险）。 */
export function Segmented<T extends string>({
  value,
  onChange,
  options,
  className,
  size = 'md',
}: {
  value: T
  onChange: (v: T) => void
  options: { value: T; label: ReactNode }[]
  className?: string
  size?: 'sm' | 'md'
}) {
  return (
    <div role="radiogroup" className={cn('inline-flex rounded-xl bg-surface-2 p-0.5', className)}>
      {options.map((o) => (
        <button
          key={o.value}
          role="radio"
          aria-checked={value === o.value}
          onClick={() => onChange(o.value)}
          className={cn(
            'rounded-[10px] font-medium transition',
            size === 'sm' ? 'px-2.5 py-1 text-[12px]' : 'px-3.5 py-1.5 text-[13px]',
            value === o.value ? 'bg-surface text-text shadow-card' : 'text-muted hover:text-text',
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

/** 一个可选的卡片行（选人物、选身份）。 */
export function OptionRow({
  selected,
  onClick,
  children,
  hint,
  disabled,
  multiple,
}: {
  selected: boolean
  onClick: () => void
  children: ReactNode
  hint?: ReactNode
  disabled?: boolean
  multiple?: boolean
}) {
  return (
    <button
      type="button"
      role={multiple ? 'checkbox' : 'radio'}
      aria-checked={selected}
      disabled={disabled}
      onClick={onClick}
      className={cn(
        'flex w-full items-center gap-3 rounded-xl border px-3 py-2.5 text-left transition disabled:opacity-45',
        selected ? 'border-accent/50 bg-accent-soft' : 'border-border bg-surface hover:bg-surface-2',
      )}
    >
      <div className="min-w-0 flex-1">
        {children}
        {hint ? <div className="mt-0.5 text-[12px] text-muted">{hint}</div> : null}
      </div>
      <span
        className={cn(
          'flex h-5 w-5 shrink-0 items-center justify-center border',
          multiple ? 'rounded-md' : 'rounded-full',
          selected ? 'border-accent bg-accent text-accent-fg' : 'border-border',
        )}
      >
        {selected ? <Check className="h-3.5 w-3.5" strokeWidth={3} /> : null}
      </span>
    </button>
  )
}

export function Chip({
  selected,
  onClick,
  children,
  badge,
}: {
  selected?: boolean
  onClick?: () => void
  children: ReactNode
  badge?: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-3.5 py-1.5 text-[13px] transition',
        selected ? 'border-accent bg-accent text-accent-fg' : 'border-border bg-surface text-text hover:bg-surface-2',
      )}
    >
      {children}
      {badge}
    </button>
  )
}

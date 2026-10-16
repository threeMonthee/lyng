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
    <div role="radiogroup" className={cn('inline-flex rounded-[11px] bg-surface-2 p-[3px]', className)}>
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={value === o.value}
          onClick={() => onChange(o.value)}
          className={cn(
            'rounded-lg font-medium transition-[background-color,color,box-shadow] duration-150',
            size === 'sm' ? 'h-7 px-3 text-[12px]' : 'h-8 px-4 text-[13px]',
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
  leading,
}: {
  selected: boolean
  onClick: () => void
  children: ReactNode
  hint?: ReactNode
  disabled?: boolean
  multiple?: boolean
  leading?: ReactNode
}) {
  return (
    <button
      type="button"
      role={multiple ? 'checkbox' : 'radio'}
      aria-checked={selected}
      disabled={disabled}
      onClick={onClick}
      className={cn(
        'flex w-full items-center gap-3 rounded-[14px] px-3.5 py-3 text-left transition-[background-color,box-shadow] duration-150 disabled:opacity-45',
        selected
          ? 'bg-accent-soft shadow-[0_0_0_1px_color-mix(in_oklab,var(--accent)_50%,transparent)]'
          : 'bg-surface shadow-card hover:bg-surface-2/50 hover:shadow-raised',
      )}
    >
      {leading}
      <div className="min-w-0 flex-1">
        <div className="text-[15px] leading-snug">{children}</div>
        {hint ? <div className="mt-0.5 text-[12px] leading-relaxed text-muted">{hint}</div> : null}
      </div>
      <Indicator selected={selected} multiple={multiple} />
    </button>
  )
}

function Indicator({ selected, multiple }: { selected: boolean; multiple?: boolean }) {
  if (multiple) {
    return (
      <span
        className={cn(
          'flex h-5 w-5 shrink-0 items-center justify-center rounded-md transition-colors',
          selected ? 'bg-accent text-accent-fg' : 'shadow-[inset_0_0_0_1.5px_var(--border)]',
        )}
      >
        {selected ? <Check className="h-3.5 w-3.5 [stroke-width:3]" /> : null}
      </span>
    )
  }
  return (
    <span
      className={cn(
        'flex h-5 w-5 shrink-0 items-center justify-center rounded-full transition-colors',
        selected ? 'bg-accent' : 'shadow-[inset_0_0_0_1.5px_var(--border)]',
      )}
    >
      {selected ? <span className="h-2 w-2 rounded-full bg-accent-fg" /> : null}
    </span>
  )
}

export function Chip({
  selected,
  onClick,
  children,
  badge,
  size = 'md',
  leading,
  disabled,
  className,
}: {
  selected?: boolean
  onClick?: () => void
  children: ReactNode
  badge?: ReactNode
  size?: 'sm' | 'md'
  leading?: ReactNode
  disabled?: boolean
  className?: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-pressed={selected}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full text-left transition-[background-color,color,box-shadow] duration-150 disabled:opacity-40',
        size === 'sm' ? 'px-3 py-1 text-[12px]' : 'px-3.5 py-1.5 text-[13px]',
        leading && (size === 'sm' ? 'pl-1' : 'pl-1.5'),
        selected
          ? 'bg-accent text-accent-fg'
          : 'bg-surface text-text shadow-card hover:bg-surface-2',
        className,
      )}
    >
      {leading}
      {children}
      {badge}
    </button>
  )
}

/** 一句建议：点一下填进输入框。 */
export function Suggestion({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="rounded-full bg-surface-2/80 px-3 py-1.5 text-left text-[12px] leading-snug text-muted transition-colors hover:bg-surface-3 hover:text-text"
    >
      {children}
    </button>
  )
}

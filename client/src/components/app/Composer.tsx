import { ArrowUp } from 'lucide-react'
import { useRef, type KeyboardEvent, type ReactNode } from 'react'
import { cn } from '@/lib/cn'
import { Textarea } from '@/components/ui/field'

const coarse = typeof window !== 'undefined' && window.matchMedia?.('(pointer: coarse)').matches

/**
 * 底部输入框：一个圆角框里放输入和发送。电脑上回车发送（Shift+回车换行），
 * 手机上回车换行、点按钮发送。输入法选词时不发送。
 */
export function Composer({
  value,
  onChange,
  onSubmit,
  placeholder,
  disabled,
  canSubmit,
  leading,
  submitLabel = '发送',
  className,
}: {
  value: string
  onChange: (v: string) => void
  onSubmit: () => void
  placeholder: string
  disabled?: boolean
  canSubmit: boolean
  leading?: ReactNode
  submitLabel?: string
  className?: string
}) {
  const ref = useRef<HTMLTextAreaElement>(null)
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key !== 'Enter' || e.shiftKey || coarse || e.nativeEvent.isComposing || e.keyCode === 229) return
    e.preventDefault()
    if (canSubmit && !disabled) onSubmit()
  }
  const ready = canSubmit && !disabled
  return (
    <div
      className={cn(
        'flex items-end gap-1 rounded-[22px] bg-surface p-1 shadow-card transition-shadow duration-150',
        'focus-within:shadow-[0_0_0_1px_color-mix(in_oklab,var(--accent)_40%,transparent),0_0_0_3px_color-mix(in_oklab,var(--accent)_7%,transparent)]',
        disabled && 'opacity-70',
        className,
      )}
      onClick={(e) => {
        if (e.target === e.currentTarget) ref.current?.focus()
      }}
    >
      {leading}
      <Textarea
        ref={ref}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={onKeyDown}
        placeholder={placeholder}
        disabled={disabled}
        maxHeight={160}
        className="min-h-9 flex-1 border-0 bg-transparent px-2.5 py-[7px] leading-[22px] focus:ring-0 disabled:opacity-100"
        enterKeyHint="send"
      />
      <button
        type="button"
        aria-label={submitLabel}
        disabled={!ready}
        onClick={onSubmit}
        className={cn(
          'flex h-9 w-9 shrink-0 items-center justify-center rounded-full transition-[background-color,color,transform] duration-150 active:scale-95',
          ready ? 'bg-accent text-accent-fg hover:bg-accent/90' : 'bg-surface-2 text-faint',
        )}
      >
        <ArrowUp className="h-[18px] w-[18px] [stroke-width:2.4]" />
      </button>
    </div>
  )
}

/** 输入框左边的小圆按钮（私聊里的“等对方说”）。 */
export function ComposerAction({
  onClick,
  disabled,
  title,
  children,
}: {
  onClick: () => void
  disabled?: boolean
  title: string
  children: ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={title}
      aria-label={title}
      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-muted transition-colors hover:bg-surface-2 hover:text-text disabled:opacity-40 [&_svg]:h-[19px] [&_svg]:w-[19px]"
    >
      {children}
    </button>
  )
}

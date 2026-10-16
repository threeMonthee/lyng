import { ArrowUp } from 'lucide-react'
import { useRef, type KeyboardEvent, type ReactNode } from 'react'
import { Textarea } from '@/components/ui/field'
import { cn } from '@/lib/cn'

const coarse = typeof window !== 'undefined' && window.matchMedia?.('(pointer: coarse)').matches

/** 底部输入框：电脑上回车发送（Shift+回车换行），手机上回车换行、点按钮发送。输入法选词时不发送。 */
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
  return (
    <div className={cn('flex items-end gap-2', className)}>
      {leading}
      <Textarea
        ref={ref}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={onKeyDown}
        placeholder={placeholder}
        disabled={disabled}
        maxHeight={160}
        className="min-h-10 flex-1 rounded-[20px] bg-surface py-2"
        enterKeyHint="send"
      />
      <button
        type="button"
        aria-label={submitLabel}
        disabled={disabled || !canSubmit}
        onClick={onSubmit}
        className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-accent text-accent-fg transition disabled:opacity-35"
      >
        <ArrowUp className="h-5 w-5" strokeWidth={2.4} />
      </button>
    </div>
  )
}

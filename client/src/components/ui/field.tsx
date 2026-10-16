import { forwardRef, useEffect, useImperativeHandle, useRef, type InputHTMLAttributes, type TextareaHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input({ className, ...props }, ref) {
  return (
    <input
      ref={ref}
      className={cn(
        'h-10 w-full rounded-xl border border-border bg-surface px-3 text-[15px] outline-none transition placeholder:text-faint focus:border-accent/60 focus:ring-2 focus:ring-accent/15',
        className,
      )}
      {...props}
    />
  )
})

interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  /** 随内容长高，最多这么多像素。 */
  maxHeight?: number
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { className, maxHeight = 240, value, onChange, ...props },
  ref,
) {
  const inner = useRef<HTMLTextAreaElement>(null)
  useImperativeHandle(ref, () => inner.current!)
  useEffect(() => {
    const el = inner.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, maxHeight)}px`
  }, [value, maxHeight])
  return (
    <textarea
      ref={inner}
      value={value}
      onChange={onChange}
      rows={1}
      className={cn(
        'w-full resize-none rounded-xl border border-border bg-surface px-3 py-2.5 text-[15px] leading-relaxed outline-none transition placeholder:text-faint focus:border-accent/60 focus:ring-2 focus:ring-accent/15',
        className,
      )}
      {...props}
    />
  )
})

export function Label({ children, hint }: { children: React.ReactNode; hint?: React.ReactNode }) {
  return (
    <div className="mb-2">
      <div className="text-[13px] font-medium text-text">{children}</div>
      {hint ? <div className="mt-0.5 text-[12px] leading-relaxed text-muted">{hint}</div> : null}
    </div>
  )
}

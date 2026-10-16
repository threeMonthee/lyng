import { forwardRef, useEffect, useImperativeHandle, useRef, type InputHTMLAttributes, type TextareaHTMLAttributes } from 'react'
import { cn } from '@/lib/cn'

/** 手机上输入框字号不小于 16px，iOS 才不会在聚焦时放大页面。 */
const field =
  'w-full rounded-xl border border-border bg-surface text-base outline-none transition-[border-color,box-shadow] duration-150 placeholder:text-faint md:text-[15px] focus:border-accent/45 focus:ring-[3px] focus:ring-accent/8 disabled:opacity-60'

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input({ className, ...props }, ref) {
  return <input ref={ref} className={cn(field, 'h-10 px-3', className)} {...props} />
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
    el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden'
  }, [value, maxHeight])
  return (
    <textarea
      ref={inner}
      value={value}
      onChange={onChange}
      rows={1}
      className={cn(field, 'scrollbar-thin resize-none px-3 py-2.5 leading-relaxed', className)}
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

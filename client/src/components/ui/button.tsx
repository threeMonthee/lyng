import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { Link } from 'react-router'
import { cn } from '@/lib/cn'
import { Spinner } from './feedback'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'outline'
type Size = 'sm' | 'md' | 'lg' | 'icon'

const variants: Record<Variant, string> = {
  primary:
    'bg-accent text-accent-fg shadow-[inset_0_1px_0_rgb(255_255_255/0.08)] hover:bg-accent/90 disabled:bg-surface-3 disabled:text-faint disabled:opacity-100',
  secondary: 'bg-surface-2 text-text hover:bg-surface-3',
  outline: 'bg-surface text-text shadow-card hover:bg-surface-2',
  ghost: 'text-muted hover:bg-surface-2 hover:text-text',
  danger: 'bg-danger text-white hover:bg-danger/90 dark:text-bg',
}

const sizes: Record<Size, string> = {
  sm: 'h-8 px-3 text-[13px] rounded-[10px] gap-1.5 [&_svg]:h-3.5 [&_svg]:w-3.5',
  md: 'h-10 px-4 text-[14px] rounded-xl gap-2 [&_svg]:h-4 [&_svg]:w-4',
  lg: 'h-12 px-5 text-[15px] rounded-[14px] gap-2 [&_svg]:h-[18px] [&_svg]:w-[18px]',
  icon: 'h-9 w-9 rounded-full [&_svg]:h-5 [&_svg]:w-5',
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: Size
  loading?: boolean
}

export function buttonClass(variant: Variant = 'primary', size: Size = 'md', className?: string) {
  return cn(
    'inline-flex shrink-0 select-none items-center justify-center whitespace-nowrap font-medium outline-none',
    'transition-[background-color,color,box-shadow,transform,opacity] duration-150 active:scale-[0.98]',
    'focus-visible:ring-2 focus-visible:ring-accent/40 focus-visible:ring-offset-2 focus-visible:ring-offset-bg',
    'disabled:pointer-events-none disabled:opacity-40 disabled:shadow-none',
    variants[variant],
    sizes[size],
    className,
  )
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', loading, className, children, disabled, ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      className={buttonClass(variant, size, className)}
      disabled={disabled || loading}
      {...props}
    >
      {loading ? <Spinner className="h-4 w-4" /> : null}
      {children}
    </button>
  )
})

/** 看起来是按钮的链接；不可用时就是一个不可点的按钮，不会误跳转。 */
export function LinkButton({
  to,
  disabled,
  variant,
  size,
  className,
  children,
}: {
  to: string
  disabled?: boolean
  variant?: Variant
  size?: Size
  className?: string
  children: ReactNode
}) {
  if (disabled) {
    return (
      <button type="button" disabled className={buttonClass(variant, size, className)}>
        {children}
      </button>
    )
  }
  return (
    <Link to={to} className={buttonClass(variant, size, className)}>
      {children}
    </Link>
  )
}

/** 头部和工具栏里的圆形图标按钮。 */
export const IconButton = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement> & { active?: boolean }>(function IconButton(
  { className, active, children, ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      type="button"
      className={cn(
        'inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-muted outline-none transition-colors',
        'hover:bg-surface-2 hover:text-text focus-visible:ring-2 focus-visible:ring-accent/40 disabled:opacity-40',
        'data-[state=open]:bg-surface-2 data-[state=open]:text-text [&_svg]:h-5 [&_svg]:w-5',
        active && 'text-text',
        className,
      )}
      {...props}
    >
      {children}
    </button>
  )
})

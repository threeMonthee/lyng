import { ChevronLeft } from 'lucide-react'
import type { CSSProperties, ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { useScrolled } from '@/lib/hooks'
import { cn } from '@/lib/cn'

/**
 * 页面顶部：返回、标题、右侧操作。滚动时贴在顶部，滚动后才显出分隔线。
 * 标题与正文左边对齐；返回箭头放在左边的留白里。
 */
export function PageHeader({
  title,
  subtitle,
  back,
  actions,
  className,
  large,
  narrow,
  bordered,
  quietTitle,
}: {
  title: ReactNode
  subtitle?: ReactNode
  /** 返回到哪里；给 true 表示浏览器后退。 */
  back?: string | true
  actions?: ReactNode
  className?: string
  /** 一级页面用大标题。 */
  large?: boolean
  /** 和窄一些的正文（场景、私聊）对齐。 */
  narrow?: boolean
  /** 一直显示分隔线（正文在下面单独滚动的页面）。 */
  bordered?: boolean
  /** 正文开头已经有大标题：顶栏的标题等滚过去之后才出现。 */
  quietTitle?: boolean
}) {
  const navigate = useNavigate()
  const scrolled = useScrolled()
  const past = useScrolled(72)
  const hidden = quietTitle && !past
  const goBack = () => {
    if (back === true) {
      if (window.history.length > 1) navigate(-1)
      else navigate('/')
    } else if (back) navigate(back)
  }
  return (
    <header
      className={cn(
        'pt-safe sticky top-0 z-20 shrink-0 bg-bg transition-[box-shadow] duration-200',
        bordered || scrolled ? 'shadow-[0_1px_0_var(--border)]' : 'shadow-[0_1px_0_transparent]',
        className,
      )}
    >
      <div
        className={cn(
          'mx-auto flex w-full items-center gap-1 px-4 md:px-6',
          narrow ? 'max-w-2xl md:px-4' : 'max-w-3xl',
          large ? 'min-h-16 md:min-h-[72px]' : 'min-h-14',
        )}
      >
        {back ? (
          <button
            onClick={goBack}
            className="-ml-2.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-text transition-colors hover:bg-surface-2 xl:-ml-11 xl:mr-1"
            aria-label="返回"
          >
            <ChevronLeft className="h-[22px] w-[22px] [stroke-width:2]" />
          </button>
        ) : null}
        <div className={cn('min-w-0 flex-1 py-2 transition-[opacity,transform] duration-200', hidden && 'translate-y-1 opacity-0')} aria-hidden={hidden || undefined}>
          <h1
            className={cn(
              'truncate font-semibold leading-tight tracking-[0.01em]',
              large ? 'text-[26px] md:text-[24px]' : 'text-[17px]',
            )}
          >
            {title}
          </h1>
          {subtitle ? <div className="mt-0.5 truncate text-[12px] leading-snug text-muted">{subtitle}</div> : null}
        </div>
        {actions ? <div className="-mr-1.5 flex shrink-0 items-center gap-0.5">{actions}</div> : null}
      </div>
    </header>
  )
}

/** 一般页面的内容区：手机两侧留白 16px，电脑居中。 */
export function Page({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('fade-enter mx-auto w-full max-w-3xl px-4 pb-20 pt-3 md:px-6 md:pt-4', className)}>{children}</div>
}

export function Section({
  title,
  action,
  children,
  className,
}: {
  title: ReactNode
  action?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={cn('mt-9 first:mt-0', className)}>
      <div className="mb-2.5 flex min-h-6 items-center justify-between gap-3">
        <h2 className="text-[13px] font-medium text-muted">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  )
}

/** 分区右上角的小链接。 */
export function SectionLink({ children, onClick }: { children: ReactNode; onClick?: () => void }) {
  return (
    <span
      onClick={onClick}
      className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[13px] text-accent transition-colors hover:bg-accent-soft [&_svg]:h-3.5 [&_svg]:w-3.5"
    >
      {children}
    </span>
  )
}

export function Card({
  children,
  className,
  interactive,
  style,
}: {
  children: ReactNode
  className?: string
  interactive?: boolean
  style?: CSSProperties
}) {
  return (
    <div
      style={style}
      className={cn(
        'rounded-2xl bg-surface shadow-card',
        interactive && 'transition-[box-shadow,transform] duration-200 hover:shadow-raised',
        className,
      )}
    >
      {children}
    </div>
  )
}

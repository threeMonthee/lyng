import { ChevronLeft } from 'lucide-react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { cn } from '@/lib/cn'

/** 页面顶部：返回、标题、右侧操作。滚动时贴在顶部。 */
export function PageHeader({
  title,
  subtitle,
  back,
  actions,
  className,
  large,
  narrow,
}: {
  title: ReactNode
  subtitle?: ReactNode
  /** 返回到哪里；给 true 表示浏览器后退。 */
  back?: string | true
  actions?: ReactNode
  className?: string
  /** 一级页面在手机上用大标题。 */
  large?: boolean
  /** 和窄一些的正文（场景、私聊）对齐。 */
  narrow?: boolean
}) {
  const navigate = useNavigate()
  const goBack = () => {
    if (back === true) {
      if (window.history.length > 1) navigate(-1)
      else navigate('/')
    } else if (back) navigate(back)
  }
  return (
    <header
      className={cn(
        'pt-safe sticky top-0 z-20 shrink-0 border-b border-border/70 bg-bg/90 backdrop-blur-md',
        large && 'border-transparent md:border-border/70',
        className,
      )}
    >
      <div className={cn('mx-auto flex min-h-14 w-full items-center gap-1 px-2', narrow ? 'max-w-2xl md:px-2' : 'max-w-3xl md:px-6')}>
        {back ? (
          <button onClick={goBack} className="-ml-0.5 rounded-full p-2 text-text hover:bg-surface-2" aria-label="返回">
            <ChevronLeft className="h-6 w-6" />
          </button>
        ) : (
          <div className="w-2" />
        )}
        <div className="min-w-0 flex-1 py-2">
          <h1 className={cn('truncate font-semibold', large ? 'text-[22px] md:text-[18px]' : 'text-[17px]')}>{title}</h1>
          {subtitle ? <div className="truncate text-[12px] text-muted">{subtitle}</div> : null}
        </div>
        {actions ? <div className="flex shrink-0 items-center gap-1 pr-1">{actions}</div> : null}
      </div>
    </header>
  )
}

/** 一般页面的内容区：手机两侧留白 16px，电脑居中。 */
export function Page({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('mx-auto w-full max-w-3xl px-4 pb-16 pt-4 md:px-6 md:pt-6', className)}>{children}</div>
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
    <section className={cn('mt-8 first:mt-0', className)}>
      <div className="mb-3 flex items-center justify-between gap-3">
        <h2 className="text-[13px] font-medium tracking-wide text-muted">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  )
}

export function Card({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('rounded-2xl border border-border bg-surface shadow-card', className)}>{children}</div>
}

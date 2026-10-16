import * as T from '@radix-ui/react-tabs'
import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

export function Tabs({
  value,
  onValueChange,
  tabs,
  children,
  className,
}: {
  value: string
  onValueChange: (v: string) => void
  tabs: { value: string; label: ReactNode; count?: number }[]
  children: ReactNode
  className?: string
}) {
  return (
    <T.Root value={value} onValueChange={onValueChange} className={className}>
      <T.List className="-mx-4 flex gap-0.5 overflow-x-auto border-b border-border px-4 [scrollbar-width:none] md:mx-0 md:px-0">
        {tabs.map((t) => (
          <T.Trigger
            key={t.value}
            value={t.value}
            className={cn(
              'relative inline-flex h-11 shrink-0 items-center gap-1.5 px-3 text-[15px] text-muted transition-colors first:pl-0 hover:text-text',
              'data-[state=active]:font-medium data-[state=active]:text-text',
              'after:absolute after:inset-x-3 first:after:left-0 after:-bottom-px after:h-[2px] after:rounded-full after:bg-transparent after:transition-colors data-[state=active]:after:bg-text',
            )}
          >
            {t.label}
            {t.count ? (
              <span className="min-w-[18px] rounded-full bg-surface-2 px-1.5 text-center text-[11px] font-medium leading-[18px] text-muted">
                {t.count}
              </span>
            ) : null}
          </T.Trigger>
        ))}
      </T.List>
      {children}
    </T.Root>
  )
}

export const TabPanel = ({ value, children }: { value: string; children: ReactNode }) => (
  <T.Content value={value} className="fade-enter pt-5 outline-none">
    {children}
  </T.Content>
)

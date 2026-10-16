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
  tabs: { value: string; label: ReactNode }[]
  children: ReactNode
  className?: string
}) {
  return (
    <T.Root value={value} onValueChange={onValueChange} className={className}>
      <T.List className="scrollbar-thin -mx-4 flex gap-1 overflow-x-auto border-b border-border px-4 md:mx-0 md:px-0">
        {tabs.map((t) => (
          <T.Trigger
            key={t.value}
            value={t.value}
            className={cn(
              'relative shrink-0 px-3 pb-2.5 pt-1 text-[14px] text-muted transition hover:text-text',
              'data-[state=active]:font-medium data-[state=active]:text-text',
              'after:absolute after:inset-x-3 after:-bottom-px after:h-0.5 after:rounded-full data-[state=active]:after:bg-accent',
            )}
          >
            {t.label}
          </T.Trigger>
        ))}
      </T.List>
      {children}
    </T.Root>
  )
}

export const TabPanel = ({ value, children }: { value: string; children: ReactNode }) => (
  <T.Content value={value} className="pt-4 outline-none">
    {children}
  </T.Content>
)

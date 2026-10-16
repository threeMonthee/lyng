import * as M from '@radix-ui/react-dropdown-menu'
import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

export function Menu({ trigger, children }: { trigger: ReactNode; children: ReactNode }) {
  return (
    <M.Root modal={false}>
      <M.Trigger asChild>{trigger}</M.Trigger>
      <M.Portal>
        <M.Content
          align="end"
          sideOffset={6}
          className="z-50 min-w-48 rounded-xl border border-border bg-surface p-1 shadow-lg"
        >
          {children}
        </M.Content>
      </M.Portal>
    </M.Root>
  )
}

export function MenuItem({
  onSelect,
  children,
  icon,
  danger,
  disabled,
}: {
  onSelect: () => void
  children: ReactNode
  icon?: ReactNode
  danger?: boolean
  disabled?: boolean
}) {
  return (
    <M.Item
      disabled={disabled}
      onSelect={onSelect}
      className={cn(
        'flex cursor-default items-center gap-2.5 rounded-lg px-2.5 py-2 text-[14px] outline-none data-[disabled]:opacity-40 data-[highlighted]:bg-surface-2',
        danger && 'text-danger',
      )}
    >
      {icon ? <span className="text-muted [&>svg]:h-4 [&>svg]:w-4">{icon}</span> : null}
      {children}
    </M.Item>
  )
}

export const MenuSeparator = () => <M.Separator className="my-1 h-px bg-border" />

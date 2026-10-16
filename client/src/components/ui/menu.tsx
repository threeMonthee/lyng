import * as M from '@radix-ui/react-dropdown-menu'
import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

export function Menu({ trigger, children }: { trigger: ReactNode; children: ReactNode }) {
  return (
    <M.Root modal={false}>
      <M.Trigger asChild>{trigger}</M.Trigger>
      <M.Portal>
        <M.Content align="end" sideOffset={6} collisionPadding={12} className="menu z-50 min-w-52 rounded-[14px] bg-surface p-1.5 shadow-pop">
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
        'flex h-9 cursor-default select-none items-center gap-2.5 rounded-[9px] px-2.5 text-[14px] outline-none transition-colors data-[disabled]:opacity-40 data-[highlighted]:bg-surface-2',
        danger && 'text-danger',
      )}
    >
      {icon ? <span className={cn('flex text-muted [&>svg]:h-4 [&>svg]:w-4', danger && 'text-danger')}>{icon}</span> : null}
      {children}
    </M.Item>
  )
}

export const MenuSeparator = () => <M.Separator className="mx-2 my-1 h-px bg-border/80" />

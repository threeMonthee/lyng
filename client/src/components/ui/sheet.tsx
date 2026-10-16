import * as Dialog from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import type { ReactNode } from 'react'
import { cn } from '@/lib/cn'

/** 手机上从底部升起，电脑上居中的弹层。 */
export function Sheet({
  open,
  onOpenChange,
  title,
  description,
  children,
  footer,
  wide,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: ReactNode
  children?: ReactNode
  footer?: ReactNode
  wide?: boolean
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="overlay fixed inset-0 z-40 bg-[rgb(20_19_17/0.32)] dark:bg-black/55" />
        <Dialog.Content
          className={cn(
            'sheet fixed z-50 flex max-h-[90dvh] w-full flex-col bg-surface shadow-pop outline-none',
            'inset-x-0 bottom-0 rounded-t-[22px] pb-safe',
            'md:inset-auto md:left-1/2 md:top-1/2 md:max-h-[85dvh] md:-translate-x-1/2 md:-translate-y-1/2 md:rounded-[20px] md:pb-0',
            wide ? 'md:max-w-2xl' : 'md:max-w-md',
          )}
        >
          <div className="mx-auto mt-2 h-[5px] w-9 shrink-0 rounded-full bg-surface-3 md:hidden" aria-hidden />
          <div className="flex shrink-0 items-start gap-3 px-5 pb-3 pt-3 md:pt-5">
            <div className="min-w-0 flex-1">
              <Dialog.Title className="text-[17px] font-semibold leading-snug">{title}</Dialog.Title>
              {description ? (
                <Dialog.Description className="mt-1 text-[13px] leading-relaxed text-muted">{description}</Dialog.Description>
              ) : (
                <Dialog.Description className="sr-only">{title}</Dialog.Description>
              )}
            </div>
            <Dialog.Close
              className="-mr-1.5 -mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-surface-2 text-muted transition-colors hover:bg-surface-3 hover:text-text"
              aria-label="关闭"
            >
              <X className="h-4 w-4 [stroke-width:2.25]" />
            </Dialog.Close>
          </div>
          <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto px-5 pb-5 pt-1">{children}</div>
          {footer ? (
            <div className="flex shrink-0 justify-end gap-2 border-t border-border/70 px-5 py-3 [&>button]:min-w-20">{footer}</div>
          ) : null}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

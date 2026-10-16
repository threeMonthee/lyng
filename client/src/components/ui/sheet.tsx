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
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/30 backdrop-blur-[1px] data-[state=open]:animate-in" />
        <Dialog.Content
          className={cn(
            'fixed z-50 flex max-h-[88dvh] w-full flex-col bg-surface shadow-xl outline-none',
            'inset-x-0 bottom-0 rounded-t-2xl pb-safe',
            'md:inset-auto md:left-1/2 md:top-1/2 md:-translate-x-1/2 md:-translate-y-1/2 md:rounded-2xl md:pb-0',
            wide ? 'md:max-w-2xl' : 'md:max-w-md',
          )}
        >
          <div className="mx-auto mt-2 h-1 w-10 rounded-full bg-surface-3 md:hidden" aria-hidden />
          <div className="flex items-start gap-3 px-5 pb-2 pt-4">
            <div className="min-w-0 flex-1">
              <Dialog.Title className="text-[17px] font-semibold">{title}</Dialog.Title>
              {description ? (
                <Dialog.Description className="mt-1 text-[13px] leading-relaxed text-muted">{description}</Dialog.Description>
              ) : (
                <Dialog.Description className="sr-only">{title}</Dialog.Description>
              )}
            </div>
            <Dialog.Close className="-mr-1 rounded-full p-1.5 text-muted hover:bg-surface-2" aria-label="关闭">
              <X className="h-5 w-5" />
            </Dialog.Close>
          </div>
          <div className="scrollbar-thin min-h-0 flex-1 overflow-y-auto px-5 pb-5 pt-1">{children}</div>
          {footer ? <div className="flex justify-end gap-2 border-t border-border px-5 py-3">{footer}</div> : null}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

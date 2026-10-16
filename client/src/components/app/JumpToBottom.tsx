import { ArrowDown } from 'lucide-react'
import { cn } from '@/lib/cn'

/** 往上翻看时浮在底部的“回到最新”。 */
export function JumpToBottom({ show, onClick }: { show: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label="回到最新"
      tabIndex={show ? 0 : -1}
      className={cn(
        'absolute bottom-4 left-1/2 z-10 flex h-9 -translate-x-1/2 items-center gap-1.5 rounded-full bg-surface pl-3 pr-3.5 text-[13px] text-muted shadow-pop transition-[opacity,transform] duration-200 hover:text-text',
        show ? 'translate-y-0 opacity-100' : 'pointer-events-none translate-y-2 opacity-0',
      )}
    >
      <ArrowDown className="h-4 w-4" />
      回到最新
    </button>
  )
}

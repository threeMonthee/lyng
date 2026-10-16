import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { Line, Person } from '@/lib/types'
import { cn } from '@/lib/cn'
import { Avatar } from '@/components/ui/avatar'
import { Typing } from '@/components/ui/feedback'

/** 场上的一段：叙事、收尾，或同一个人在同一轮里连着的几句话与心声。 */
export type Block =
  | { type: 'narration'; key: string; line: Line }
  | { type: 'person'; key: string; speakerId: string; byUser: boolean; lines: Line[]; inner: Line | null }

export function toBlocks(lines: Line[]): Block[] {
  const blocks: Block[] = []
  for (const line of lines) {
    if (line.kind === 'narration' || line.kind === 'closing' || !line.speakerId) {
      blocks.push({ type: 'narration', key: `n${line.id}`, line })
      continue
    }
    const last = blocks[blocks.length - 1]
    const same =
      last?.type === 'person' &&
      last.speakerId === line.speakerId &&
      last.byUser === line.byUser &&
      last.lines.concat(last.inner ? [last.inner] : []).every((l) => l.round === line.round) &&
      !last.inner
    if (line.kind === 'inner') {
      if (same && last.type === 'person') last.inner = line
      else blocks.push({ type: 'person', key: `p${line.id}`, speakerId: line.speakerId, byUser: line.byUser, lines: [], inner: line })
      continue
    }
    if (same && last.type === 'person') last.lines.push(line)
    else blocks.push({ type: 'person', key: `p${line.id}`, speakerId: line.speakerId, byUser: line.byUser, lines: [line], inner: null })
  }
  return blocks
}

/** 一场开头：第几场和开场处境。 */
export function SceneOpening({ number, setup }: { number: number; setup: string }) {
  return (
    <div className="fade-enter mb-8 mt-3 text-center">
      <Ornament>第 {number} 场</Ornament>
      <p className="prose-life mx-auto mt-3 max-w-md text-[14px] leading-[1.8] text-muted">{setup}</p>
    </div>
  )
}

/** 两侧一道细线，中间几个字。 */
export function Ornament({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-center justify-center gap-3 text-[11px] tracking-[0.35em] text-faint">
      <span className="h-px w-8 bg-border" />
      <span className="pl-[0.35em]">{children}</span>
      <span className="h-px w-8 bg-border" />
    </div>
  )
}

export function Narration({ text, closing }: { text: string; closing?: boolean }) {
  return (
    <div className={cn('enter my-7', closing && 'mt-10')}>
      {closing ? (
        <div className="mb-4">
          <Ornament>收尾</Ornament>
        </div>
      ) : null}
      <p className="prose-life text-[15.5px] leading-[1.95] text-text/85">{text}</p>
    </div>
  )
}

export function InnerVoice({ text, className }: { text: string; className?: string }) {
  return (
    <div className={cn('fade-enter flex w-fit max-w-[32rem] items-baseline gap-2 rounded-[14px] bg-inner-soft px-3 py-1.5', className)}>
      <span className="shrink-0 text-[11px] font-medium text-inner/75">心声</span>
      <p className="prose-life text-[14px] leading-[1.75] text-inner">{text}</p>
    </div>
  )
}

const bubble = 'w-fit max-w-[32rem] whitespace-pre-wrap break-words rounded-[18px] px-3.5 py-2 text-[15px] leading-[1.65]'

/** 一个人在这一轮里的言行。byUser 的一段靠右（你做的），其余靠左。 */
export function PersonBlock({
  person,
  lines,
  inner,
  byUser,
  showInner,
  live,
  footer,
}: {
  person: Person
  lines: { kind: string; text: string }[]
  inner: string | null
  byUser: boolean
  showInner: boolean
  live?: boolean
  footer?: ReactNode
}) {
  if (byUser) {
    return (
      <div className="enter my-6 flex flex-col items-end gap-1.5 pl-12">
        <div className="text-[12px] text-faint">你 · {person.name}</div>
        {lines.map((l, i) =>
          l.kind === 'wait' ? (
            <div key={i} className="rounded-full bg-surface-2 px-3 py-1 text-[12px] text-muted">
              静候
            </div>
          ) : l.kind === 'do' ? (
            <p key={i} className="prose-life max-w-[32rem] text-right text-[15px] leading-[1.8] text-muted">
              {l.text}
            </p>
          ) : (
            <div key={i} className={cn(bubble, 'rounded-tr-[6px] bg-me text-me-fg')}>
              {l.text}
            </div>
          ),
        )}
      </div>
    )
  }
  const silent = lines.length > 0 && lines.every((l) => l.kind === 'wait')
  return (
    <div className={cn('my-6 flex gap-2.5 pr-8 md:pr-12', !live && 'enter')}>
      <Link to={`/characters/${person.id}`} className="shrink-0 transition-opacity hover:opacity-80">
        <Avatar name={person.name} size={30} />
      </Link>
      <div className="flex min-w-0 flex-1 flex-col items-start gap-1.5">
        <Link to={`/characters/${person.id}`} className="flex h-[30px] items-center text-[13px] font-medium text-text/75 hover:text-text">
          {person.name}
        </Link>
        {silent ? <p className="text-[13px] text-faint">没有作声</p> : null}
        {lines
          .filter((l) => l.kind !== 'wait')
          .map((l, i) =>
            l.kind === 'do' ? (
              <p key={i} className="prose-life max-w-[32rem] text-[15px] leading-[1.8] text-muted">
                {l.text}
              </p>
            ) : (
              <div key={i} className={cn(bubble, 'rounded-tl-[6px] bg-surface shadow-card', live && 'opacity-80')}>
                {l.text}
              </div>
            ),
          )}
        {inner && showInner ? <InnerVoice text={inner} className="mt-0.5" /> : null}
        {footer}
      </div>
    </div>
  )
}

/** 正在写的那一句：一个装着三个点的气泡。 */
export function TypingBubble({ className }: { className?: string }) {
  return (
    <div className={cn('fade-enter flex h-[38px] w-fit items-center rounded-[18px] rounded-tl-[6px] bg-surface px-4 shadow-card', className)}>
      <Typing />
    </div>
  )
}

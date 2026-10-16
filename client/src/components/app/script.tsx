import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { Line, Person } from '@/lib/types'
import { cn } from '@/lib/cn'
import { Avatar } from '@/components/ui/avatar'

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

export function Narration({ text, closing }: { text: string; closing?: boolean }) {
  return (
    <div className="mx-auto my-5 max-w-[34rem] px-2">
      {closing ? <div className="mb-2 text-center text-[11px] tracking-[0.3em] text-faint">— 收尾 —</div> : null}
      <p className="prose-life text-[15px] text-text/80">{text}</p>
    </div>
  )
}

export function InnerVoice({ text, className }: { text: string; className?: string }) {
  return (
    <div className={cn('mt-1.5 max-w-[30rem] border-l-2 border-inner/40 pl-2.5', className)}>
      <span className="mr-1.5 text-[11px] text-inner/80">心声</span>
      <span className="prose-life text-[13.5px] leading-relaxed text-inner">{text}</span>
    </div>
  )
}

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
      <div className="my-3 flex flex-col items-end pl-10">
        <div className="mb-1 text-[11px] text-faint">你 · {person.name}</div>
        {lines.map((l, i) =>
          l.kind === 'wait' ? (
            <div key={i} className="rounded-full bg-surface-2 px-3 py-1 text-[12px] text-muted">
              静候
            </div>
          ) : l.kind === 'do' ? (
            <p key={i} className="prose-life my-0.5 max-w-[30rem] text-right text-[14.5px] text-muted">
              {l.text}
            </p>
          ) : (
            <div key={i} className="my-0.5 max-w-[30rem] rounded-2xl rounded-tr-md bg-me px-3.5 py-2 text-[15px] leading-relaxed text-me-fg">
              {l.text}
            </div>
          ),
        )}
      </div>
    )
  }
  const silent = lines.length > 0 && lines.every((l) => l.kind === 'wait')
  return (
    <div className={cn('my-3 flex gap-2.5 pr-8', live && 'opacity-75')}>
      <Link to={`/characters/${person.id}`} className="shrink-0 pt-0.5">
        <Avatar name={person.name} size={32} />
      </Link>
      <div className="min-w-0 flex-1">
        <div className="mb-1 text-[12px] font-medium text-muted">{person.name}</div>
        {silent ? <p className="text-[13px] text-faint">没有作声</p> : null}
        {lines
          .filter((l) => l.kind !== 'wait')
          .map((l, i) =>
            l.kind === 'do' ? (
              <p key={i} className="prose-life my-0.5 max-w-[30rem] text-[14.5px] text-muted">
                {l.text}
              </p>
            ) : (
              <div key={i} className="my-0.5 w-fit max-w-[30rem] rounded-2xl rounded-tl-md border border-border bg-surface px-3.5 py-2 text-[15px] leading-relaxed">
                {l.text}
              </div>
            ),
          )}
        {inner && showInner ? <InnerVoice text={inner} /> : null}
        {footer}
      </div>
    </div>
  )
}

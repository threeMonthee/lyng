import { ChevronRight } from 'lucide-react'
import { Link } from 'react-router'
import type { ChatSummary, SceneSummary } from '@/lib/types'
import { shortTime } from '@/lib/format'
import { toneLabel } from '@/lib/labels'
import { cn } from '@/lib/cn'
import { Avatar, AvatarStack } from '@/components/ui/avatar'
import { Typing } from '@/components/ui/feedback'
import { Badge } from './Shell'

/** 一场戏现在的状态，一句话。 */
export function sceneState(s: SceneSummary): { text: string; tone: 'muted' | 'accent' | 'danger' } {
  if (s.status === 'closed') return { text: '已结束', tone: 'muted' }
  if (s.running) return { text: '正在进行', tone: 'accent' }
  if (s.failure) return { text: '有一步没成功', tone: 'danger' }
  if (s.pending) return { text: '还没开场', tone: 'accent' }
  return { text: s.possessedId ? '轮到你了' : '等你继续', tone: 'accent' }
}

export function identityText(s: SceneSummary): string {
  if (s.possessedId) return `你是${s.cast.find((p) => p.id === s.possessedId)?.name ?? '？'}`
  return `旁观，跟着${s.cast.find((p) => p.id === s.followId)?.name ?? '？'}`
}

/** 状态小标签：还要你或人物接着做的带一个点。 */
export function StatePill({ text, tone }: { text: string; tone: 'muted' | 'accent' | 'danger' }) {
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1.5 rounded-full px-2 py-0.5 text-[11.5px] font-medium leading-4',
        tone === 'danger' ? 'bg-danger-soft text-danger' : tone === 'accent' ? 'bg-accent-soft text-accent' : 'bg-surface-2 text-faint',
      )}
    >
      {tone !== 'muted' ? <span className="h-1.5 w-1.5 rounded-full bg-current" /> : null}
      {text}
    </span>
  )
}

export function SceneCard({ scene, showWorld }: { scene: SceneSummary; showWorld?: boolean }) {
  const state = sceneState(scene)
  const open = scene.status === 'open'
  return (
    <Link
      to={`/scenes/${scene.id}`}
      className="group block rounded-2xl bg-surface p-4 shadow-card transition-shadow duration-200 hover:shadow-raised"
    >
      <div className="flex items-center gap-2">
        <span className="min-w-0 truncate text-[13px] font-medium">
          {showWorld ? <span className="text-muted">{scene.worldName} · </span> : null}第{scene.number}场
        </span>
        <span className="shrink-0 text-[12px] text-faint">{toneLabel[scene.tone]}</span>
        {scene.scale === 'adult' ? <span className="shrink-0 rounded bg-surface-2 px-1 text-[11px] text-muted">成人</span> : null}
        <span className="ml-auto" />
        <StatePill text={state.text} tone={state.tone} />
      </div>
      <p className={cn('prose-life mt-2.5 line-clamp-2 text-[15px] leading-[1.75]', open ? 'text-text' : 'text-text/80')}>
        {scene.last ?? scene.setup}
      </p>
      <div className="mt-3 flex items-center gap-2">
        <AvatarStack names={scene.cast.map((p) => p.name)} size={20} />
        <span className="min-w-0 truncate text-[12px] text-muted">
          {scene.cast.map((p) => p.name).join('、')}
          {open ? <span className="text-faint"> · {identityText(scene)}</span> : null}
        </span>
        <span className="ml-auto shrink-0 text-[12px] tabular-nums text-faint">{shortTime(scene.closedAt ?? scene.createdAt)}</span>
      </div>
    </Link>
  )
}

export function sides(chat: ChatSummary) {
  const played = chat.playedId === chat.b.id ? chat.b : chat.a
  const other = played.id === chat.a.id ? chat.b : chat.a
  return { played, other }
}

/** 私聊列表里一行的分隔线从名字处开始。 */
export const CHAT_LIST_INSET = { '--inset': '74px' } as React.CSSProperties

/** perspective：在某个人物的主页上，从 TA 的角度写成“和谁的私聊”。 */
export function ChatRow({ chat, showWorld, perspective }: { chat: ChatSummary; showWorld?: boolean; perspective?: string }) {
  const { played, other } = sides(chat)
  const counterpart = perspective ? (chat.a.id === perspective ? chat.b : chat.a) : other
  const preview = chat.running ? null : chat.failure ? '上一次没有成功，点开重试' : chat.last?.text ?? '还没有消息'
  const unread = chat.unread > 0
  return (
    <Link to={`/chats/${chat.id}`} className="flex items-center gap-3 px-4 py-3 transition-colors hover:bg-surface-2/50">
      <div className="relative shrink-0">
        <Avatar name={counterpart.name} size={46} />
        {perspective ? null : (
          <span className="absolute -bottom-0.5 -right-1" title={`你是${played.name}`}>
            <Avatar name={played.name} size={20} ring />
          </span>
        )}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-2">
          <span className="truncate text-[15px] font-medium">{perspective ? `和${counterpart.name}的私聊` : other.name}</span>
          <span className="truncate text-[12px] text-faint">
            {perspective ? '' : `你是${played.name}`}
            {showWorld ? `${perspective ? '' : ' · '}${chat.worldName}` : ''}
          </span>
          <span className={cn('ml-auto shrink-0 text-[12px] tabular-nums', unread ? 'text-accent' : 'text-faint')}>{shortTime(chat.lastAt)}</span>
        </div>
        <div className="mt-0.5 flex items-center gap-2">
          <span className={cn('min-w-0 flex-1 truncate text-[13px]', chat.failure ? 'text-danger' : unread ? 'text-text' : 'text-muted')}>
            {preview === null ? <Typing label="正在输入" /> : preview}
          </span>
          {unread ? <Badge>{chat.unread}</Badge> : null}
        </div>
      </div>
    </Link>
  )
}

export function LinkRow({ to, children, hint }: { to: string; children: React.ReactNode; hint?: React.ReactNode }) {
  return (
    <Link to={to} className="group flex items-center gap-3 px-4 py-3 text-[15px] transition-colors hover:bg-surface-2/50">
      <div className="min-w-0 flex-1">{children}</div>
      {hint ? <span className="shrink-0 text-[12px] tabular-nums text-faint">{hint}</span> : null}
      <ChevronRight className="h-4 w-4 shrink-0 text-faint transition-transform group-hover:translate-x-0.5" />
    </Link>
  )
}

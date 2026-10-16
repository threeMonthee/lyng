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

export function SceneCard({ scene, showWorld }: { scene: SceneSummary; showWorld?: boolean }) {
  const state = sceneState(scene)
  return (
    <Link
      to={`/scenes/${scene.id}`}
      className="block rounded-2xl border border-border bg-surface p-4 shadow-card transition hover:border-accent/30"
    >
      <div className="flex items-center gap-2 text-[12px] text-muted">
        <span className="font-medium text-text">
          {showWorld ? `${scene.worldName} · ` : ''}第{scene.number}场
        </span>
        <span>·</span>
        <span>{toneLabel[scene.tone]}</span>
        {scene.scale === 'adult' ? <span className="rounded bg-surface-2 px-1">成人</span> : null}
        <span
          className={cn(
            'ml-auto',
            state.tone === 'danger' ? 'text-danger' : state.tone === 'accent' ? 'text-accent' : 'text-faint',
          )}
        >
          {state.text}
        </span>
      </div>
      <p className="prose-life mt-2 line-clamp-2 text-[14px] text-text">{scene.last ?? scene.setup}</p>
      <div className="mt-3 flex items-center gap-2">
        <AvatarStack names={scene.cast.map((p) => p.name)} />
        <span className="truncate text-[12px] text-muted">
          {scene.cast.map((p) => p.name).join('、')}
          {scene.status === 'open' ? ` · ${identityText(scene)}` : ''}
        </span>
        <span className="ml-auto shrink-0 text-[12px] text-faint">{shortTime(scene.closedAt ?? scene.createdAt)}</span>
      </div>
    </Link>
  )
}

export function sides(chat: ChatSummary) {
  const played = chat.playedId === chat.b.id ? chat.b : chat.a
  const other = played.id === chat.a.id ? chat.b : chat.a
  return { played, other }
}

/** perspective：在某个人物的主页上，从 TA 的角度写成“和谁的私聊”。 */
export function ChatRow({ chat, showWorld, perspective }: { chat: ChatSummary; showWorld?: boolean; perspective?: string }) {
  const { played, other } = sides(chat)
  const counterpart = perspective ? (chat.a.id === perspective ? chat.b : chat.a) : other
  const preview = chat.running ? null : chat.failure ? '上一次没有成功，点开重试' : chat.last?.text ?? '还没有消息'
  return (
    <Link to={`/chats/${chat.id}`} className="flex items-center gap-3 px-4 py-3 transition hover:bg-surface-2/60">
      <div className="relative">
        <Avatar name={counterpart.name} size={46} />
        {perspective ? null : (
          <span className="absolute -bottom-0.5 -right-0.5">
            <Avatar name={played.name} size={20} ring />
          </span>
        )}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-2">
          <span className="truncate text-[15px] font-medium">{perspective ? `和${counterpart.name}的私聊` : other.name}</span>
          <span className="truncate text-[12px] text-faint">
            你是{played.name}
            {showWorld ? ` · ${chat.worldName}` : ''}
          </span>
          <span className="ml-auto shrink-0 text-[12px] text-faint">{shortTime(chat.lastAt)}</span>
        </div>
        <div className="mt-0.5 flex items-center gap-2">
          <span className={cn('min-w-0 flex-1 truncate text-[13px]', chat.failure ? 'text-danger' : 'text-muted')}>
            {preview === null ? <Typing label="正在输入" /> : preview}
          </span>
          {chat.unread > 0 ? <Badge>{chat.unread}</Badge> : null}
        </div>
      </div>
    </Link>
  )
}

export function LinkRow({ to, children, hint }: { to: string; children: React.ReactNode; hint?: React.ReactNode }) {
  return (
    <Link to={to} className="flex items-center gap-3 px-4 py-3 text-[14px] transition hover:bg-surface-2/60">
      <div className="min-w-0 flex-1">{children}</div>
      {hint ? <span className="shrink-0 text-[12px] text-faint">{hint}</span> : null}
      <ChevronRight className="h-4 w-4 shrink-0 text-faint" />
    </Link>
  )
}

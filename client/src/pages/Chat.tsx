import { AlertCircle, Eye, EyeOff, Hourglass, MoreHorizontal, Repeat, RotateCw, ScrollText, UserRound } from 'lucide-react'
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, beacon } from '@/lib/api'
import { stream, type StreamEvent } from '@/lib/sse'
import { errorMessage } from '@/lib/query'
import { useShowInner, useStickToBottom } from '@/lib/hooks'
import { chatTime, needsTimeSeparator } from '@/lib/format'
import type { ChatPage, Message } from '@/lib/types'
import { cn } from '@/lib/cn'
import { PageHeader } from '@/components/app/PageHeader'
import { Composer, ComposerAction } from '@/components/app/Composer'
import { JumpToBottom } from '@/components/app/JumpToBottom'
import { InnerVoice } from '@/components/app/script'
import { sides } from '@/components/app/summaries'
import { Avatar } from '@/components/ui/avatar'
import { Button, IconButton } from '@/components/ui/button'
import { ErrorState, Notice, PageLoading, Typing } from '@/components/ui/feedback'
import { Menu, MenuItem, MenuSeparator } from '@/components/ui/menu'

/** 一次开口（同一次运行）或你连着发的几句。 */
interface Turn {
  key: string
  speakerId: string
  byUser: boolean
  opportunity: Message['opportunity']
  says: Message[]
  inner: Message | null
  at: string
}

export function toTurns(messages: Message[]): Turn[] {
  const turns: Turn[] = []
  for (const m of messages) {
    const last = turns[turns.length - 1]
    const same =
      last &&
      last.speakerId === m.speakerId &&
      last.byUser === m.byUser &&
      !last.inner &&
      (m.byUser || (last.says[0]?.runId ?? null) === m.runId)
    const turn: Turn = same
      ? last
      : { key: `t${m.id}`, speakerId: m.speakerId, byUser: m.byUser, opportunity: m.opportunity, says: [], inner: null, at: m.createdAt }
    if (!same) turns.push(turn)
    if (m.kind === 'inner') turn.inner = m
    else turn.says.push(m)
  }
  return turns
}

export function ChatPage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const navigate = useNavigate()
  const [streaming, setStreaming] = useState(false)
  const [typing, setTyping] = useState(false)
  const [drafts, setDrafts] = useState<string[]>([])
  const [notice, setNotice] = useState<string | null>(null)
  const [failedRun, setFailedRun] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [older, setOlder] = useState<Message[]>([])
  const [moreOlder, setMoreOlder] = useState<boolean | null>(null)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [showInner, setShowInner] = useShowInner()

  const query = useQuery({
    queryKey: ['chat', id],
    queryFn: () => api.get<ChatPage>(`/chats/${id}`),
    refetchInterval: (q) => (q.state.data?.chat.running && !streaming ? 1500 : false),
  })
  const data = query.data
  const chat = data?.chat

  // 打开时标记已读；离开页面时让双方把新的话整理进心里。
  useEffect(() => {
    if (chat && chat.unread > 0) {
      void api.post(`/chats/${id}/read`).then(() => {
        void client.invalidateQueries({ queryKey: ['home'] })
        void client.invalidateQueries({ queryKey: ['chats'] })
      })
    }
  }, [chat?.unread, chat, id, client])
  useEffect(() => {
    const leave = () => beacon(`/chats/${id}/leave`)
    window.addEventListener('pagehide', leave)
    return () => {
      window.removeEventListener('pagehide', leave)
      leave()
    }
  }, [id])
  useEffect(() => {
    setOlder([])
    setMoreOlder(null)
  }, [id])

  const messages = useMemo(() => [...older, ...(data?.messages ?? [])], [older, data?.messages])
  const turns = useMemo(() => toTurns(messages), [messages])
  const { ref: feedRef, toBottom, away } = useStickToBottom([messages.length, drafts.length, typing, notice])

  const append = useCallback(
    (list: Message[]) =>
      client.setQueryData<ChatPage>(['chat', id], (old) => {
        if (!old) return old
        const known = new Set(old.messages.map((m) => m.id))
        return { ...old, messages: [...old.messages, ...list.filter((m) => !known.has(m.id))] }
      }),
    [client, id],
  )

  const run = useCallback(
    async (path: string, body: unknown, onRejected?: () => void) => {
      setStreaming(true)
      setNotice(null)
      setFailedRun(null)
      const onEvent = (e: StreamEvent) => {
        switch (e.event) {
          case 'status':
            setTyping(true)
            break
          case 'draft':
            setDrafts((d) => [...d, e.data.text])
            break
          case 'discard':
            setDrafts([])
            break
          case 'saved':
            append((e.data as { messages?: Message[] }).messages ?? [])
            setDrafts([])
            setTyping(false)
            break
          case 'failed':
            setFailedRun(e.data.runId ?? null)
            break
          case 'error':
            setNotice(e.data.error)
            onRejected?.()
            break
        }
      }
      try {
        await stream(path, body, onEvent)
      } finally {
        setStreaming(false)
        setTyping(false)
        setDrafts([])
        await client.invalidateQueries({ queryKey: ['chat', id] })
        void client.invalidateQueries({ queryKey: ['chats'] })
        void client.invalidateQueries({ queryKey: ['home'] })
      }
    },
    [append, client, id],
  )

  const loadOlder = async () => {
    const first = messages[0]
    if (!first) return
    setLoadingOlder(true)
    try {
      const page = await api.get<ChatPage>(`/chats/${id}?before=${first.id}`)
      setOlder((o) => [...page.messages, ...o])
      setMoreOlder(page.more)
    } catch (e) {
      setNotice(errorMessage(e))
    } finally {
      setLoadingOlder(false)
    }
  }

  const switchSides = async (playedId: string) => {
    try {
      await api.post(`/chats/${id}/play`, { playedId })
      await client.invalidateQueries({ queryKey: ['chat', id] })
      void client.invalidateQueries({ queryKey: ['chats'] })
    } catch (e) {
      setNotice(errorMessage(e))
    }
  }


  if (query.isPending) {
    return (
      <div className="flex h-full flex-col">
        <PageHeader title="私聊" back="/messages" />
        <div className="mx-auto w-full max-w-2xl p-4">
          <PageLoading rows={5} />
        </div>
      </div>
    )
  }
  if (query.isError || !data || !chat) {
    return (
      <div className="flex h-full flex-col">
        <PageHeader title="私聊" back="/messages" />
        <ErrorState message={errorMessage(query.error)} onRetry={() => query.refetch()} />
      </div>
    )
  }

  const { played, other } = sides(chat)
  const busy = streaming || chat.running
  const stuck = !busy && chat.pending
  const blocked = data.busy.length > 0
  const hasMore = moreOlder ?? data.more

  const send = () => {
    const said = text
    if (!said.trim() || busy) return
    setText('')
    toBottom()
    void run(`/chats/${id}/send`, { text: said }, () => setText(said))
  }

  let previousAt: string | null = null

  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader
        narrow
        bordered
        back="/messages"
        title={
          <Link to={`/characters/${other.id}`} className="inline-flex max-w-full items-center gap-2 align-middle">
            <Avatar name={other.name} size={26} />
            <span className="truncate">{other.name}</span>
          </Link>
        }
        subtitle={`你是${played.name} · ${chat.worldName}`}
        actions={
          <>
            <IconButton
              onClick={() => setShowInner(!showInner)}
              className={showInner ? 'text-inner hover:text-inner' : undefined}
              aria-label={showInner ? '隐藏心声' : '显示心声'}
              title={showInner ? '隐藏心声' : '显示心声'}
            >
              {showInner ? <Eye /> : <EyeOff />}
            </IconButton>
            <Menu
              trigger={
                <IconButton aria-label="更多">
                  <MoreHorizontal />
                </IconButton>
              }
            >
              <MenuItem icon={<Repeat />} disabled={busy || chat.pending} onSelect={() => switchSides(other.id)}>
                换成附身{other.name}
              </MenuItem>
              <MenuSeparator />
              <MenuItem icon={<UserRound />} onSelect={() => navigate(`/characters/${other.id}`)}>
                {other.name}的主页
              </MenuItem>
              <MenuItem icon={<UserRound />} onSelect={() => navigate(`/characters/${played.id}`)}>
                {played.name}的主页
              </MenuItem>
              <MenuItem icon={<ScrollText />} onSelect={() => navigate(`/runs?conversationId=${id}`)}>
                幕后记录
              </MenuItem>
            </Menu>
          </>
        }
      />

      <div className="relative min-h-0 flex-1">
        <div ref={feedRef} className="scrollbar-thin h-full overflow-y-auto">
          <div className="mx-auto w-full max-w-2xl px-3 pb-5 pt-3 md:px-4">
            {hasMore ? (
              <div className="mb-2 flex justify-center">
                <Button size="sm" variant="ghost" loading={loadingOlder} onClick={loadOlder}>
                  更早的消息
                </Button>
              </div>
            ) : null}
            {messages.length === 0 && !busy ? (
              <div className="fade-enter flex flex-col items-center px-6 py-16 text-center">
                <Avatar name={other.name} size={64} />
                <p className="mt-4 text-[15px] font-medium">{other.name}</p>
                <p className="mt-1.5 max-w-xs text-[13px] leading-relaxed text-muted">
                  你是{played.name}。说点什么，或者点输入框左边的沙漏，等{other.name}先开口。
                </p>
              </div>
            ) : null}

            {turns.map((t) => {
              const mine = t.speakerId === played.id
              const separator = needsTimeSeparator(previousAt, t.at)
              previousAt = t.says[t.says.length - 1]?.createdAt ?? t.inner?.createdAt ?? t.at
              const speaker = t.speakerId === other.id ? other : played
              return (
                <Fragment key={t.key}>
                  {separator ? <div className="mb-3 mt-6 text-center text-[11px] tabular-nums text-faint">{chatTime(t.at)}</div> : null}
                  {t.opportunity === 'reach' && !t.byUser ? (
                    <div className="my-3 flex justify-center">
                      <span className="rounded-full bg-surface-2/80 px-2.5 py-0.5 text-[11px] text-muted">{speaker.name}主动发来</span>
                    </div>
                  ) : null}
                  <div className={cn('enter my-2.5 flex gap-2', mine ? 'flex-row-reverse pl-14' : 'pr-14')}>
                    {!mine ? (
                      <Link to={`/characters/${speaker.id}`} className="mt-1 shrink-0">
                        <Avatar name={speaker.name} size={30} />
                      </Link>
                    ) : null}
                    <div className={cn('flex min-w-0 flex-col gap-[3px]', mine ? 'items-end' : 'items-start')}>
                      {t.says.map((m, i) => (
                        <div key={m.id} className={cn(bubble, mine ? 'bg-me text-me-fg' : 'bg-surface shadow-card', corners(mine, i))}>
                          {m.text}
                        </div>
                      ))}
                      {t.says.length === 0 && !t.byUser ? <div className="py-1.5 text-[13px] text-faint">没有回复</div> : null}
                      {t.inner && showInner ? <InnerVoice text={t.inner.text} className="mt-1" /> : null}
                    </div>
                  </div>
                </Fragment>
              )
            })}

            {drafts.length > 0 || typing || (!streaming && chat.running) ? (
              <div className="my-2.5 flex gap-2 pr-14">
                <Avatar name={other.name} size={30} className="mt-1" />
                <div className="flex flex-col items-start gap-[3px]">
                  {drafts.map((d, i) => (
                    <div key={i} className={cn(bubble, 'bg-surface opacity-80 shadow-card', corners(false, i))}>
                      {d}
                    </div>
                  ))}
                  <div
                    className={cn(
                      'fade-enter flex h-[38px] items-center bg-surface px-4 shadow-card',
                      corners(false, drafts.length),
                    )}
                  >
                    <Typing />
                  </div>
                </div>
              </div>
            ) : null}

            {stuck ? (
              <div className="enter my-5 rounded-2xl bg-danger-soft/70 p-4 shadow-[inset_0_0_0_1px_color-mix(in_oklab,var(--danger)_18%,transparent)]">
                <p className="flex items-center gap-2 text-[14px] font-medium text-danger">
                  <AlertCircle className="h-4 w-4" />
                  {chat.failure ? `${other.name}这一次没有回成` : `上一次${other.name}没有回完`}
                </p>
                {chat.failure ? <p className="mt-1 pl-6 text-[13px] leading-relaxed text-muted">{chat.failure}</p> : null}
                <div className="mt-3 flex gap-2 pl-6">
                  <Button size="sm" onClick={() => run(`/chats/${id}/retry`, {})}>
                    <RotateCw />
                    重试
                  </Button>
                  <Link to={failedRun ? `/runs/${failedRun}` : `/runs?conversationId=${id}`}>
                    <Button size="sm" variant="ghost">
                      看幕后
                    </Button>
                  </Link>
                </div>
              </div>
            ) : null}

            {notice ? (
              <Notice tone="danger" className="my-3">
                {notice}
              </Notice>
            ) : null}
          </div>
        </div>
        <JumpToBottom show={away} onClick={() => toBottom(true)} />
      </div>

      <div className="pb-safe shrink-0 bg-bg shadow-[0_-1px_0_var(--border)]">
        <div className="mx-auto w-full max-w-2xl px-3 pb-3 pt-2.5 md:px-4">
          {blocked ? (
            <Notice tone="warn" className="mb-2.5">
              {data.busy.map((s) => (
                <span key={s.id}>
                  {s.cast
                    .filter((p) => p.id === played.id || p.id === other.id)
                    .map((p) => p.name)
                    .join('和')}
                  正在
                  <Link to={`/scenes/${s.id}`} className="mx-0.5 font-medium underline underline-offset-2">
                    第{s.number}场
                  </Link>
                  里，那一场结束后才能接着私聊。
                </span>
              ))}
            </Notice>
          ) : null}
          <Composer
            value={text}
            onChange={setText}
            onSubmit={send}
            canSubmit={!!text.trim()}
            disabled={busy || stuck || blocked}
            placeholder={`以${played.name}的身份发消息`}
            leading={
              <ComposerAction
                onClick={() => {
                  toBottom()
                  void run(`/chats/${id}/continue`, {})
                }}
                disabled={busy || stuck || blocked}
                title={`不说话，等${other.name}接着说`}
              >
                <Hourglass />
              </ComposerAction>
            }
          />
        </div>
      </div>
    </div>
  )
}

const bubble = 'max-w-full whitespace-pre-wrap break-words rounded-[20px] px-3.5 py-2 text-[15px] leading-[1.6]'

/** 连着的几条气泡：靠近说话人那一侧的圆角收紧，读起来是一组。 */
export function corners(mine: boolean, index: number): string {
  if (mine) return cn('rounded-br-[6px]', index > 0 && 'rounded-tr-[6px]')
  return cn('rounded-bl-[6px]', index > 0 && 'rounded-tl-[6px]')
}

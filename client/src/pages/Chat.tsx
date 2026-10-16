import { Eye, EyeOff, Hourglass, MoreHorizontal, Repeat, RotateCw, ScrollText, UserRound } from 'lucide-react'
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
import { Composer } from '@/components/app/Composer'
import { InnerVoice } from '@/components/app/script'
import { sides } from '@/components/app/summaries'
import { Avatar } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
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
  const { ref: feedRef, toBottom } = useStickToBottom([messages.length, drafts.length, typing, notice])

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
        back="/messages"
        title={
          <Link to={`/characters/${other.id}`} className="inline-flex items-center gap-2">
            <Avatar name={other.name} size={26} />
            {other.name}
          </Link>
        }
        subtitle={`你是${played.name} · ${chat.worldName}`}
        actions={
          <>
            <button
              onClick={() => setShowInner(!showInner)}
              className={cn('rounded-full p-2 hover:bg-surface-2', showInner ? 'text-inner' : 'text-faint')}
              aria-label={showInner ? '隐藏心声' : '显示心声'}
              title={showInner ? '隐藏心声' : '显示心声'}
            >
              {showInner ? <Eye className="h-5 w-5" /> : <EyeOff className="h-5 w-5" />}
            </button>
            <Menu
              trigger={
                <button className="rounded-full p-2 text-text hover:bg-surface-2" aria-label="更多">
                  <MoreHorizontal className="h-5 w-5" />
                </button>
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

      <div ref={feedRef} className="scrollbar-thin min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto w-full max-w-2xl px-3 pb-4 pt-3 md:px-4">
          {hasMore ? (
            <div className="mb-2 flex justify-center">
              <Button size="sm" variant="ghost" loading={loadingOlder} onClick={loadOlder}>
                更早的消息
              </Button>
            </div>
          ) : null}
          {messages.length === 0 && !busy ? (
            <div className="py-16 text-center text-[13px] leading-relaxed text-muted">
              你是{played.name}，在和{other.name}私聊。
              <br />
              说点什么，或者点输入框左边的沙漏，等{other.name}先开口。
            </div>
          ) : null}

          {turns.map((t) => {
            const mine = t.speakerId === played.id
            const separator = needsTimeSeparator(previousAt, t.at)
            previousAt = t.says[t.says.length - 1]?.createdAt ?? t.inner?.createdAt ?? t.at
            const speaker = t.speakerId === other.id ? other : played
            return (
              <Fragment key={t.key}>
                {separator ? <div className="my-4 text-center text-[11px] text-faint">{chatTime(t.at)}</div> : null}
                {t.opportunity === 'reach' && !t.byUser ? (
                  <div className="my-2 text-center text-[11px] text-faint">{speaker.name}主动发来</div>
                ) : null}
                <div className={cn('my-1.5 flex gap-2', mine ? 'flex-row-reverse pl-12' : 'pr-12')}>
                  {!mine ? <Avatar name={speaker.name} size={32} className="mt-0.5" /> : null}
                  <div className={cn('flex min-w-0 flex-col gap-1', mine ? 'items-end' : 'items-start')}>
                    {t.says.map((m) => (
                      <div
                        key={m.id}
                        className={cn(
                          'max-w-full whitespace-pre-wrap break-words rounded-[18px] px-3.5 py-2 text-[15px] leading-relaxed',
                          mine ? 'rounded-br-md bg-me text-me-fg' : 'rounded-bl-md border border-border bg-surface',
                        )}
                      >
                        {m.text}
                      </div>
                    ))}
                    {t.says.length === 0 && !t.byUser ? <div className="py-1 text-[13px] text-faint">没有回复</div> : null}
                    {t.inner && showInner ? <InnerVoice text={t.inner.text} className={mine ? 'text-right' : ''} /> : null}
                  </div>
                </div>
              </Fragment>
            )
          })}

          {drafts.length > 0 || typing || (!streaming && chat.running) ? (
            <div className="my-1.5 flex gap-2 pr-12">
              <Avatar name={other.name} size={32} className="mt-0.5" />
              <div className="flex flex-col items-start gap-1 opacity-75">
                {drafts.map((d, i) => (
                  <div key={i} className="rounded-[18px] rounded-bl-md border border-border bg-surface px-3.5 py-2 text-[15px] leading-relaxed">
                    {d}
                  </div>
                ))}
                <div className="rounded-[18px] rounded-bl-md border border-border bg-surface px-3.5 py-2.5">
                  <Typing />
                </div>
              </div>
            </div>
          ) : null}

          {stuck ? (
            <div className="my-4 rounded-2xl border border-danger/25 bg-danger-soft/60 p-4">
              <p className="text-[14px] font-medium text-danger">
                {chat.failure ? `${other.name}这一次没有回成` : `上一次${other.name}没有回完`}
              </p>
              {chat.failure ? <p className="mt-1 text-[13px] leading-relaxed text-muted">{chat.failure}</p> : null}
              <div className="mt-3 flex gap-2">
                <Button size="sm" onClick={() => run(`/chats/${id}/retry`, {})}>
                  <RotateCw className="h-3.5 w-3.5" />
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

      <div className="pb-safe shrink-0 border-t border-border bg-bg/95 backdrop-blur">
        <div className="mx-auto w-full max-w-2xl px-3 py-2.5">
          {blocked ? (
            <Notice tone="warn" className="mb-2">
              {data.busy.map((s) => (
                <span key={s.id}>
                  {s.cast
                    .filter((p) => p.id === played.id || p.id === other.id)
                    .map((p) => p.name)
                    .join('和')}
                  正在
                  <Link to={`/scenes/${s.id}`} className="mx-0.5 underline">
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
              <button
                type="button"
                onClick={() => {
                  toBottom()
                  void run(`/chats/${id}/continue`, {})
                }}
                disabled={busy || stuck || blocked}
                title={`不说话，等${other.name}接着说`}
                aria-label={`等${other.name}接着说`}
                className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-muted transition hover:bg-surface-2 disabled:opacity-35"
              >
                <Hourglass className="h-5 w-5" />
              </button>
            }
          />
        </div>
      </div>
    </div>
  )
}

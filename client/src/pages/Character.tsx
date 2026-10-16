import { Clapperboard, History, MessageCircle } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { errorMessage, useCharacter } from '@/lib/query'
import { concernStatusLabel, profileSections } from '@/lib/labels'
import { fullTime, shortTime } from '@/lib/format'
import type { CharacterPage, ConcernItem, Memories, Self, Source, Trace, View } from '@/lib/types'
import { cn } from '@/lib/cn'
import { Card, Page, PageHeader } from '@/components/app/PageHeader'
import { RunList } from '@/components/app/RunList'
import { NewChatSheet } from '@/components/app/NewChat'
import { ChatRow } from '@/components/app/summaries'
import { Avatar } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Empty, ErrorState, PageLoading } from '@/components/ui/feedback'
import { Sheet } from '@/components/ui/sheet'
import { TabPanel, Tabs } from '@/components/ui/tabs'

const TABS = [
  { value: 'about', label: '关于' },
  { value: 'concerns', label: '牵挂' },
  { value: 'memories', label: '记忆' },
  { value: 'views', label: '看法' },
  { value: 'experiences', label: '经历' },
  { value: 'runs', label: '幕后' },
]

export function CharacterPageView() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') ?? 'about'
  const character = useCharacter(id)
  const [chatOpen, setChatOpen] = useState(false)

  if (character.isPending) {
    return (
      <>
        <PageHeader title="人物" back />
        <Page>
          <PageLoading />
        </Page>
      </>
    )
  }
  if (character.isError) {
    return (
      <>
        <PageHeader title="人物" back />
        <ErrorState message={errorMessage(character.error)} onRetry={() => character.refetch()} />
      </>
    )
  }
  const c = character.data
  const active = c.concerns.filter((x) => x.status === 'active').length
  return (
    <>
      <PageHeader title={c.name} subtitle={c.worldName} back={`/worlds/${c.worldId}`} />
      <Page>
        <div className="flex items-center gap-4">
          <Avatar name={c.name} size={64} />
          <div className="min-w-0 flex-1">
            <div className="font-serif text-[24px] font-semibold leading-tight">{c.name}</div>
            <div className="mt-1 text-[13px] text-muted">
              <Link to={`/worlds/${c.worldId}`} className="hover:text-text">
                {c.worldName}
              </Link>
              {' · '}
              {c.memories} 段记忆 · {active} 件放不下的事
            </div>
            {c.openScene ? (
              <Link to={`/scenes/${c.openScene.id}`} className="mt-1 inline-block text-[13px] text-accent">
                正在第{c.openScene.number}场里 →
              </Link>
            ) : null}
          </div>
        </div>
        <div className="mt-4 flex gap-2">
          <Button variant="outline" className="flex-1" disabled={c.others.length === 0} onClick={() => setChatOpen(true)}>
            <MessageCircle className="h-4 w-4" />
            私聊
          </Button>
          <Link to={`/worlds/${c.worldId}/new-scene`} className="flex-1">
            <Button variant="outline" className="w-full" disabled={!!c.openScene}>
              <Clapperboard className="h-4 w-4" />
              开一场戏
            </Button>
          </Link>
        </div>

        <Tabs
          className="mt-6"
          value={tab}
          onValueChange={(v) => setParams(v === 'about' ? {} : { tab: v }, { replace: true })}
          tabs={TABS.map((t) => (t.value === 'concerns' && active > 0 ? { ...t, label: `牵挂 ${active}` } : t))}
        >
          <TabPanel value="about">
            <About c={c} />
          </TabPanel>
          <TabPanel value="concerns">
            <Concerns items={c.concerns} />
          </TabPanel>
          <TabPanel value="memories">
            <MemoriesTab id={c.id} />
          </TabPanel>
          <TabPanel value="views">
            <Views c={c} />
          </TabPanel>
          <TabPanel value="experiences">
            <Experiences c={c} />
          </TabPanel>
          <TabPanel value="runs">
            <p className="mb-3 text-[13px] leading-relaxed text-muted">
              {c.name}每一次开口、每一次整理内心的完整记录：看到了什么、想了什么、最后决定了什么。
            </p>
            <RunList filter={{ characterId: c.id }} />
          </TabPanel>
        </Tabs>
      </Page>
      <NewChatSheet open={chatOpen} onOpenChange={setChatOpen} worldId={c.worldId} otherId={c.id} />
    </>
  )
}

function RunLink({ runId }: { runId: string | null }) {
  if (!runId) return null
  return (
    <Link to={`/runs/${runId}`} className="text-[12px] text-faint hover:text-accent">
      幕后
    </Link>
  )
}

function About({ c }: { c: CharacterPage }) {
  const [history, setHistory] = useState(false)
  return (
    <div className="space-y-4">
      <Card className="p-5">
        <div className="flex items-center justify-between">
          <div className="text-[13px] font-medium">如今的自己</div>
          {c.selfVersions > 1 ? (
            <button onClick={() => setHistory(true)} className="inline-flex items-center gap-1 text-[12px] text-muted hover:text-text">
              <History className="h-3.5 w-3.5" />
              {c.selfVersions} 个版本
            </button>
          ) : null}
        </div>
        {c.self ? (
          <>
            <p className="prose-life mt-2 text-[15px]">{c.self.text}</p>
            <div className="mt-2 flex gap-3 text-[12px] text-faint">
              <span>{shortTime(c.self.createdAt)}写下</span>
              <RunLink runId={c.self.runId} />
            </div>
          </>
        ) : (
          <p className="mt-2 text-[13px] leading-relaxed text-muted">
            还没有。经历过一些事之后，{c.name}会在整理内心时写下自己如今是怎样的人。
          </p>
        )}
      </Card>
      {c.chats.length > 0 ? (
        <Card className="divide-y divide-border overflow-hidden">
          {c.chats.map((x) => (
            <ChatRow key={x.id} chat={x} perspective={c.id} />
          ))}
        </Card>
      ) : null}
      <Card className="p-5">
        <div className="mb-3 flex items-center justify-between">
          <div className="text-[13px] font-medium">出生档案</div>
          <span className="text-[12px] text-faint">{fullTime(c.createdAt)} 诞生</span>
        </div>
        <div className="space-y-4">
          {profileSections(c.profile).map((s, i) => (
            <div key={i}>
              {s.title ? <div className="mb-0.5 text-[12px] font-medium text-muted">{s.title}</div> : null}
              <p className="prose-life text-[14.5px]">{s.body}</p>
            </div>
          ))}
        </div>
      </Card>
      <HistorySheet
        open={history}
        onOpenChange={setHistory}
        title={`${c.name}如今的自己 · 历次版本`}
        queryKey={['self', c.id]}
        path={`/characters/${c.id}/self`}
      />
    </div>
  )
}

function HistorySheet({
  open,
  onOpenChange,
  title,
  queryKey,
  path,
}: {
  open: boolean
  onOpenChange: (v: boolean) => void
  title: string
  queryKey: string[]
  path: string
}) {
  const history = useQuery({ queryKey, queryFn: () => api.get<(Self | View)[]>(path), enabled: open })
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title={title} description="最新的在最上面。旧的版本不会被抹去。" wide>
      {history.isPending ? (
        <PageLoading rows={3} />
      ) : history.isError ? (
        <ErrorState message={errorMessage(history.error)} />
      ) : (
        <ol className="space-y-4">
          {[...history.data].reverse().map((h, i) => (
            <li key={i} className={cn('border-l-2 pl-3', i === 0 ? 'border-accent' : 'border-border')}>
              <div className="flex gap-3 text-[12px] text-faint">
                <span>{h.createdAt ? fullTime(h.createdAt) : ''}</span>
                {h.runId ? <RunLink runId={h.runId} /> : <span>诞生时</span>}
              </div>
              <p className={cn('prose-life mt-1 text-[14.5px]', i > 0 && 'text-muted')}>{h.text}</p>
            </li>
          ))}
        </ol>
      )}
    </Sheet>
  )
}

function Concerns({ items }: { items: ConcernItem[] }) {
  if (items.length === 0) return <Empty title="没有放不下的事">心里有了挂念，会出现在这里。</Empty>
  const groups: ConcernItem['status'][] = ['active', 'background', 'resolved']
  return (
    <div className="space-y-6">
      {groups.map((g) => {
        const list = items.filter((x) => x.status === g)
        if (list.length === 0) return null
        return (
          <div key={g}>
            <div className="mb-2 text-[12px] text-muted">
              {concernStatusLabel[g]} · {list.length}
            </div>
            <div className="space-y-2">
              {list.map((x) => (
                <Card key={x.id} className={cn('p-4', g !== 'active' && 'opacity-75')}>
                  {x.aboutName ? (
                    <span className="mb-1.5 inline-flex items-center gap-1.5 text-[12px] text-muted">
                      <Avatar name={x.aboutName} size={18} />
                      关于{x.aboutName}
                    </span>
                  ) : null}
                  <p className="prose-life text-[14.5px]">{x.text}</p>
                  {x.resolution ? <p className="mt-1.5 text-[13px] text-muted">了结：{x.resolution}</p> : null}
                  <div className="mt-2 flex gap-3 text-[12px] text-faint">
                    <span>{x.runId ? `${shortTime(x.updatedAt)}` : '诞生时就有'}</span>
                    <RunLink runId={x.runId} />
                  </div>
                </Card>
              ))}
            </div>
          </div>
        )
      })}
    </div>
  )
}

const MEMORY_PAGE = 20

function MemoriesTab({ id }: { id: string }) {
  const memories = useInfiniteQuery({
    queryKey: ['memories', id],
    queryFn: ({ pageParam }) => api.get<Memories>(`/characters/${id}/memories?offset=${pageParam}&limit=${MEMORY_PAGE}`),
    initialPageParam: 0,
    getNextPageParam: (last, all) => {
      const loaded = all.reduce((n, p) => n + p.items.length, 0)
      return loaded < last.total ? loaded : undefined
    },
  })
  if (memories.isPending) return <PageLoading rows={3} />
  if (memories.isError) return <ErrorState message={errorMessage(memories.error)} onRetry={() => memories.refetch()} />
  const items = memories.data.pages.flatMap((p) => p.items)
  if (items.length === 0) {
    return <Empty title="还没有记住什么">一场戏结束、一段私聊告一段落后，TA 会自己决定记住哪些事、那些事意味着什么。</Empty>
  }
  return (
    <div className="space-y-3">
      {items.map((m) => (
        <Card key={m.id} className="p-4">
          <p className="prose-life text-[15px]">{m.event}</p>
          {m.meaning ? <p className="prose-life mt-1.5 text-[13.5px] text-muted">{m.meaning}</p> : null}
          <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-faint">
            {m.people.length > 0 ? (
              <span className="inline-flex items-center gap-1">
                {m.people.map((p) => (
                  <Avatar key={p.id} name={p.name} size={18} />
                ))}
                <span className="ml-0.5">{m.people.map((p) => p.name).join('、')}</span>
              </span>
            ) : null}
            {m.sourceId && m.sourceLabel ? (
              <Link to={m.sourceKind === 'scene' ? `/scenes/${m.sourceId}` : `/chats/${m.sourceId}`} className="hover:text-accent">
                {m.sourceLabel}
              </Link>
            ) : m.sourceKind === 'birth' ? (
              <span>诞生时</span>
            ) : null}
            <span>{shortTime(m.createdAt)}</span>
            <RunLink runId={m.runId} />
          </div>
        </Card>
      ))}
      {memories.hasNextPage ? (
        <div className="flex justify-center">
          <Button variant="ghost" size="sm" loading={memories.isFetchingNextPage} onClick={() => memories.fetchNextPage()}>
            更早的记忆
          </Button>
        </div>
      ) : null}
    </div>
  )
}

function Views({ c }: { c: CharacterPage }) {
  const [history, setHistory] = useState<{ id: string; name: string } | null>(null)
  if (c.views.length === 0) return <Empty title="还没有对谁的看法" />
  return (
    <div className="space-y-3">
      {c.views.map((v) => (
        <Card key={v.otherId} className="p-4">
          <div className="flex items-center gap-2">
            <Link to={`/characters/${v.otherId}`} className="inline-flex items-center gap-2 text-[14px] font-medium">
              <Avatar name={v.otherName} size={24} />
              {v.otherName}
            </Link>
            {v.versions > 1 ? (
              <button
                onClick={() => setHistory({ id: v.otherId, name: v.otherName })}
                className="ml-auto inline-flex items-center gap-1 text-[12px] text-muted hover:text-text"
              >
                <History className="h-3.5 w-3.5" />
                变过 {v.versions - 1} 次
              </button>
            ) : null}
          </div>
          <p className="prose-life mt-2 text-[14.5px]">{v.text}</p>
          <div className="mt-2 flex gap-3 text-[12px] text-faint">
            <span>{v.runId ? shortTime(v.createdAt) : '诞生时'}</span>
            <RunLink runId={v.runId} />
          </div>
        </Card>
      ))}
      <HistorySheet
        open={history !== null}
        onOpenChange={(o) => !o && setHistory(null)}
        title={`${c.name}怎么看${history?.name ?? ''} · 历次版本`}
        queryKey={['view', c.id, history?.id ?? '']}
        path={`/characters/${c.id}/views/${history?.id}`}
      />
    </div>
  )
}

function Experiences({ c }: { c: CharacterPage }) {
  const sources = useQuery({ queryKey: ['experiences', c.id], queryFn: () => api.get<Source[]>(`/characters/${c.id}/experiences`) })
  const [open, setOpen] = useState<Source | null>(null)
  if (sources.isPending) return <PageLoading rows={3} />
  if (sources.isError) return <ErrorState message={errorMessage(sources.error)} onRetry={() => sources.refetch()} />
  if (sources.data.length === 0) {
    return <Empty title="还没有经历">在戏里、在私聊里亲历的每一刻，都会按 TA 自己看到的样子留在这里。</Empty>
  }
  return (
    <>
      <p className="mb-3 text-[13px] leading-relaxed text-muted">
        这是{c.name}亲眼看到、亲耳听到的原样经历，不含别人的心声。整理内心和回想时，TA 依据的就是这些。
      </p>
      <Card className="divide-y divide-border overflow-hidden">
        {sources.data.map((s) => (
          <button key={s.sourceId} onClick={() => setOpen(s)} className="flex w-full items-center gap-3 px-4 py-3 text-left transition hover:bg-surface-2/60">
            <div className="min-w-0 flex-1">
              <div className="truncate text-[14px]">{s.sourceLabel}</div>
              <div className="mt-0.5 text-[12px] text-muted">
                {s.sourceKind === 'scene' ? '一场戏' : '私聊'} · {s.lines} 句
              </div>
            </div>
            <span className="shrink-0 text-[12px] text-faint">{shortTime(s.lastAt)}</span>
          </button>
        ))}
      </Card>
      <TraceSheet characterId={c.id} source={open} onClose={() => setOpen(null)} />
    </>
  )
}

function TraceSheet({ characterId, source, onClose }: { characterId: string; source: Source | null; onClose: () => void }) {
  const traces = useInfiniteQuery({
    queryKey: ['experience', characterId, source?.sourceId],
    queryFn: ({ pageParam }) => api.get<Trace[]>(`/characters/${characterId}/experiences/${source!.sourceId}?after=${pageParam}`),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.length < 200 ? undefined : last[last.length - 1].id),
    enabled: source !== null,
  })
  return (
    <Sheet open={source !== null} onOpenChange={(o) => !o && onClose()} title={source?.sourceLabel ?? ''} description="TA 看到的原样" wide>
      {traces.isPending ? (
        <PageLoading rows={4} />
      ) : traces.isError ? (
        <ErrorState message={errorMessage(traces.error)} />
      ) : (
        <div className="space-y-2">
          {traces.data.pages.flat().map((t) => (
            <p key={t.id} className="prose-life text-[14px]">
              {t.text}
            </p>
          ))}
          {traces.hasNextPage ? (
            <Button variant="ghost" size="sm" loading={traces.isFetchingNextPage} onClick={() => traces.fetchNextPage()}>
              接着看
            </Button>
          ) : null}
          {source ? (
            <div className="pt-3">
              <Link to={source.sourceKind === 'scene' ? `/scenes/${source.sourceId}` : `/chats/${source.sourceId}`} className="text-[13px] text-accent">
                打开{source.sourceKind === 'scene' ? '这一场' : '这段私聊'}
              </Link>
            </div>
          ) : null}
        </div>
      )}
    </Sheet>
  )
}

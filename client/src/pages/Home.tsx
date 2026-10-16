import { ChevronRight, Feather, Sparkles } from 'lucide-react'
import { Link } from 'react-router'
import { useHome, errorMessage } from '@/lib/query'
import { shortTime } from '@/lib/format'
import { Card, Page, PageHeader, Section, SectionLink } from '@/components/app/PageHeader'
import { CHAT_LIST_INSET, ChatRow, LinkRow, SceneCard } from '@/components/app/summaries'
import { Avatar, AvatarStack } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Empty, ErrorState, Notice, PageLoading } from '@/components/ui/feedback'

const draftStatus: Record<string, string> = {
  asking: '等你回答几个问题',
  writing: '正在写',
  ready: '写好了，等你过目',
  failed: '没写成，可以重写',
}

export function HomePage() {
  const home = useHome()
  return (
    <>
      <PageHeader
        large
        title={
          <>
            <span className="font-serif md:hidden">此间有灵</span>
            <span className="hidden md:inline">首页</span>
          </>
        }
      />
      <Page>
        {home.isPending ? (
          <PageLoading />
        ) : home.isError ? (
          <ErrorState message={errorMessage(home.error)} onRetry={() => home.refetch()} />
        ) : (
          <HomeBody />
        )}
      </Page>
    </>
  )
}

function HomeBody() {
  const { data } = useHome()
  if (!data) return null
  if (data.worlds.length === 0 && data.drafts.length === 0) {
    return (
      <Empty
        icon={<Feather className="h-8 w-8" />}
        title="这里还没有任何人"
        action={
          <Link to="/create">
            <Button size="lg">
              <Sparkles />
              一念生
            </Button>
          </Link>
        }
      >
        用一句话写下你想进入的世界、想遇见的人。他们会有自己的过去、心事和记忆，会记得你们之间发生的事。
      </Empty>
    )
  }
  return (
    <div>
      {data.fake ? (
        <Notice tone="warn" className="mb-7">
          替身模式：人物的话由程序按固定规则拼出来，不调用模型，只用来看界面和流程。
        </Notice>
      ) : null}

      {data.drafts.length > 0 ? (
        <Section title="没写完的一念">
          <Card className="list overflow-hidden">
            {data.drafts.map((d) => (
              <LinkRow key={d.id} to={`/create/${d.id}`} hint={shortTime(d.updatedAt)}>
                <div className="truncate font-serif">{d.request}</div>
                <div className="mt-0.5 text-[12px] text-muted">
                  {d.worldName ? `给「${d.worldName}」添人物 · ` : ''}
                  {draftStatus[d.status] ?? d.status}
                </div>
              </LinkRow>
            ))}
          </Card>
        </Section>
      ) : null}

      {data.openScenes.length > 0 ? (
        <Section title="进行中的戏">
          <div className="grid gap-3 md:grid-cols-2">
            {data.openScenes.map((s) => (
              <SceneCard key={s.id} scene={s} showWorld />
            ))}
          </div>
        </Section>
      ) : null}

      {data.chats.length > 0 ? (
        <Section
          title="私聊"
          action={
            <Link to="/messages">
              <SectionLink>
                全部
                <ChevronRight />
              </SectionLink>
            </Link>
          }
        >
          <Card className="list overflow-hidden" style={CHAT_LIST_INSET}>
            {data.chats.map((c) => (
              <ChatRow key={c.id} chat={c} showWorld={data.worlds.length > 1} />
            ))}
          </Card>
        </Section>
      ) : null}

      <Section title="世界">
        <div className="grid gap-3 sm:grid-cols-2">
          {data.worlds.map((w) => (
            <Link
              key={w.id}
              to={`/worlds/${w.id}`}
              className="group flex flex-col rounded-2xl bg-surface p-4 shadow-card transition-shadow duration-200 hover:shadow-raised"
            >
              <div className="flex items-center gap-3">
                <Avatar name={w.name} size={42} square />
                <div className="min-w-0 flex-1">
                  <div className="truncate font-serif text-[18px] font-semibold leading-snug">{w.name}</div>
                  <div className="mt-0.5 text-[12px] text-muted">
                    {w.characters.length} 个人物 · {w.scenes > 0 ? `${w.scenes} 场戏` : '还没有戏'}
                    {w.busy ? <span className="text-accent"> · 有一场正在进行</span> : null}
                  </div>
                </div>
                <ChevronRight className="h-4 w-4 shrink-0 text-faint transition-transform group-hover:translate-x-0.5" />
              </div>
              <div className="mt-3.5 flex items-center gap-2 border-t border-border/70 pt-3">
                <AvatarStack names={w.characters.map((c) => c.name)} size={20} />
                <span className="truncate text-[13px] text-muted">{w.characters.map((c) => c.name).join('、')}</span>
              </div>
            </Link>
          ))}
          <Link
            to="/create"
            className="group flex min-h-[118px] flex-col items-center justify-center gap-2 rounded-2xl border border-dashed border-border p-4 text-muted transition-colors hover:border-accent/40 hover:bg-accent-soft/40 hover:text-accent"
          >
            <span className="flex h-9 w-9 items-center justify-center rounded-full bg-surface-2 transition-colors group-hover:bg-accent-soft">
              <Sparkles className="h-[18px] w-[18px]" />
            </span>
            <span className="text-[14px]">一念生，再造一个世界</span>
          </Link>
        </div>
      </Section>

      {data.recentScenes.length > 0 ? (
        <Section title="最近结束的戏">
          <div className="grid gap-3 md:grid-cols-2">
            {data.recentScenes.map((s) => (
              <SceneCard key={s.id} scene={s} showWorld />
            ))}
          </div>
        </Section>
      ) : null}
    </div>
  )
}

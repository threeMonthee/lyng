import { Clapperboard, Lock, MessageCircle, MoreHorizontal, UserPlus } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { errorMessage, useWorld } from '@/lib/query'
import { profileSections } from '@/lib/labels'
import { Card, Page, PageHeader, Section } from '@/components/app/PageHeader'
import { ChatRow, SceneCard } from '@/components/app/summaries'
import { NewChatSheet } from '@/components/app/NewChat'
import { Avatar } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { ErrorState, PageLoading } from '@/components/ui/feedback'
import { Menu, MenuItem } from '@/components/ui/menu'

/** 档案里最能代表此刻的一句，用在人物卡片上。 */
export function blurb(profile: string): string {
  const sections = profileSections(profile)
  const now = sections.find((s) => s.title && /此刻|现在|当下/.test(s.title))
  const first = now ?? sections.find((s) => s.title && s.body) ?? sections[0]
  return (first?.body ?? '').replace(/\s+/g, ' ').slice(0, 80)
}

export function WorldPage() {
  const { id = '' } = useParams()
  const world = useWorld(id)
  const navigate = useNavigate()
  const [chatWith, setChatWith] = useState<string | null>(null)
  const [reveal, setReveal] = useState(false)

  if (world.isPending) {
    return (
      <>
        <PageHeader title="世界" back="/" />
        <Page>
          <PageLoading />
        </Page>
      </>
    )
  }
  if (world.isError) {
    return (
      <>
        <PageHeader title="世界" back="/" />
        <ErrorState message={errorMessage(world.error)} onRetry={() => world.refetch()} />
      </>
    )
  }
  const w = world.data
  const open = w.scenes.filter((s) => s.status === 'open')
  const closed = w.scenes.filter((s) => s.status === 'closed')
  const free = w.characters.filter((c) => !c.openScene)

  return (
    <>
      <PageHeader
        back="/"
        title={<span className="font-serif">{w.name}</span>}
        subtitle={`${w.characters.length} 个人物 · ${w.scenes.length} 场戏`}
        actions={
          <Menu
            trigger={
              <button className="rounded-full p-2 hover:bg-surface-2" aria-label="更多">
                <MoreHorizontal className="h-5 w-5" />
              </button>
            }
          >
            <MenuItem icon={<UserPlus />} onSelect={() => navigate(`/create?world=${id}`)}>
              添人物
            </MenuItem>
            <MenuItem icon={<MessageCircle />} onSelect={() => setChatWith('')}>
              新的私聊
            </MenuItem>
          </Menu>
        }
      />
      <Page>
        <div className="flex gap-2">
          <Link to={`/worlds/${id}/new-scene`} className="flex-1">
            <Button className="w-full" size="lg" disabled={free.length === 0}>
              <Clapperboard className="h-4 w-4" />
              开一场戏
            </Button>
          </Link>
          <Button size="lg" variant="outline" onClick={() => setChatWith('')}>
            <MessageCircle className="h-4 w-4" />
            私聊
          </Button>
        </div>
        {free.length === 0 && w.characters.length > 0 ? (
          <p className="mt-2 text-[12px] text-muted">每个人都在进行中的戏里，先结束那一场。</p>
        ) : null}

        {open.length > 0 ? (
          <Section title="进行中" className="mt-8">
            <div className="grid gap-3">
              {open.map((s) => (
                <SceneCard key={s.id} scene={s} />
              ))}
            </div>
          </Section>
        ) : null}

        <Section
          title="人物"
          className="mt-8"
          action={
            <Link to={`/create?world=${id}`} className="text-[13px] text-accent">
              添人物
            </Link>
          }
        >
          <div className="grid gap-3 sm:grid-cols-2">
            {w.characters.map((c) => (
              <Card key={c.id} className="flex flex-col p-4">
                <Link to={`/characters/${c.id}`} className="flex items-center gap-3">
                  <Avatar name={c.name} size={44} />
                  <div className="min-w-0 flex-1">
                    <div className="font-serif text-[17px] font-semibold">{c.name}</div>
                    {c.openScene ? (
                      <div className="text-[12px] text-accent">在第{c.openScene.number}场里</div>
                    ) : (
                      <div className="text-[12px] text-faint">此刻有空</div>
                    )}
                  </div>
                </Link>
                <p className="prose-life mt-3 line-clamp-3 flex-1 text-[13.5px] text-muted">{blurb(c.profile)}</p>
                <div className="mt-3 flex gap-2">
                  <Link to={`/characters/${c.id}`} className="flex-1">
                    <Button size="sm" variant="secondary" className="w-full">
                      主页
                    </Button>
                  </Link>
                  <Button size="sm" variant="outline" className="flex-1" onClick={() => setChatWith(c.id)}>
                    私聊
                  </Button>
                </div>
              </Card>
            ))}
          </div>
        </Section>

        {w.chats.length > 0 ? (
          <Section title="私聊" className="mt-8">
            <Card className="divide-y divide-border overflow-hidden">
              {w.chats.map((c) => (
                <ChatRow key={c.id} chat={c} />
              ))}
            </Card>
          </Section>
        ) : null}

        {closed.length > 0 ? (
          <Section title="演过的戏" className="mt-8">
            <div className="grid gap-3">
              {closed.map((s) => (
                <SceneCard key={s.id} scene={s} />
              ))}
            </div>
          </Section>
        ) : null}

        <Section title="这个世界" className="mt-8">
          <Card className="p-5">
            <p className="prose-life text-[14.5px]">{w.publicText}</p>
          </Card>
          <Card className="mt-3 p-5">
            <div className="flex items-center gap-2 text-[13px] font-medium">
              <Lock className="h-4 w-4 text-muted" />
              只有叙事者知道
            </div>
            {reveal ? (
              <p className="prose-life mt-3 text-[14.5px]">{w.narratorText}</p>
            ) : (
              <div className="mt-2 text-[13px] leading-relaxed text-muted">
                人物们不知道这些。看了之后，你会比他们知道得多。
                <button onClick={() => setReveal(true)} className="ml-1 text-accent">
                  仍然要看
                </button>
              </div>
            )}
          </Card>
        </Section>
      </Page>
      <NewChatSheet open={chatWith !== null} onOpenChange={(v) => !v && setChatWith(null)} worldId={id} otherId={chatWith || undefined} />
    </>
  )
}

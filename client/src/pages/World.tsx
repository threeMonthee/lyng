import { ChevronDown, Clapperboard, Lock, MessageCircle, MoreHorizontal, UserPlus } from 'lucide-react'
import { useLayoutEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { errorMessage, useWorld } from '@/lib/query'
import { profileSections } from '@/lib/labels'
import { cn } from '@/lib/cn'
import { Card, Page, PageHeader, Section, SectionLink } from '@/components/app/PageHeader'
import { CHAT_LIST_INSET, ChatRow, SceneCard } from '@/components/app/summaries'
import { NewChatSheet } from '@/components/app/NewChat'
import { Avatar } from '@/components/ui/avatar'
import { Button, IconButton, LinkButton } from '@/components/ui/button'
import { ErrorState, PageLoading } from '@/components/ui/feedback'
import { Menu, MenuItem } from '@/components/ui/menu'

/** 档案里最能代表此刻的一句，用在人物卡片上。 */
export function blurb(profile: string): string {
  const sections = profileSections(profile)
  const now = sections.find((s) => s.title && /此刻|现在|当下/.test(s.title))
  const first = now ?? sections.find((s) => s.title && s.body) ?? sections[0]
  return (first?.body ?? '').replace(/\s+/g, ' ').slice(0, 80)
}

/** 世界的公开设定：先露三行，放不下才给“展开全文”。 */
function WorldIntro({ text }: { text: string }) {
  const ref = useRef<HTMLParagraphElement>(null)
  const [more, setMore] = useState(false)
  const [overflow, setOverflow] = useState(false)
  useLayoutEffect(() => {
    const el = ref.current
    if (el && !more) setOverflow(el.scrollHeight > el.clientHeight + 1)
  }, [text, more])
  return (
    <div>
      <p ref={ref} className={cn('prose-life text-[15px] leading-[1.9] text-text/85', !more && 'line-clamp-3')}>
        {text}
      </p>
      {overflow ? (
        <button
          onClick={() => setMore(!more)}
          className="mt-1 inline-flex items-center gap-0.5 text-[13px] text-muted transition-colors hover:text-text"
        >
          {more ? '收起' : '展开全文'}
          <ChevronDown className={cn('h-3.5 w-3.5 transition-transform', more && 'rotate-180')} />
        </button>
      ) : null}
    </div>
  )
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
              <IconButton aria-label="更多">
                <MoreHorizontal />
              </IconButton>
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
        <WorldIntro text={w.publicText} />

        <div className="mt-5 flex gap-2">
          <LinkButton to={`/worlds/${id}/new-scene`} className="flex-1" size="lg" disabled={free.length === 0}>
            <Clapperboard />
            开一场戏
          </LinkButton>
          <Button size="lg" variant="outline" className="px-6" onClick={() => setChatWith('')}>
            <MessageCircle />
            私聊
          </Button>
        </div>
        {free.length === 0 && w.characters.length > 0 ? (
          <p className="mt-2 text-[12px] text-muted">每个人都在进行中的戏里，先结束那一场。</p>
        ) : null}

        {open.length > 0 ? (
          <Section title="进行中" className="mt-9">
            <div className="grid gap-3">
              {open.map((s) => (
                <SceneCard key={s.id} scene={s} />
              ))}
            </div>
          </Section>
        ) : null}

        <Section
          title="人物"
          action={
            <Link to={`/create?world=${id}`}>
              <SectionLink>
                <UserPlus />
                添人物
              </SectionLink>
            </Link>
          }
        >
          <div className="grid gap-3 sm:grid-cols-2">
            {w.characters.map((c) => (
              <div
                key={c.id}
                className="group relative flex flex-col rounded-2xl bg-surface p-4 shadow-card transition-shadow duration-200 hover:shadow-raised"
              >
                <Link to={`/characters/${c.id}`} className="absolute inset-0 rounded-2xl" aria-label={`${c.name}的主页`} />
                <div className="flex items-center gap-3">
                  <Avatar name={c.name} size={44} />
                  <div className="min-w-0 flex-1">
                    <div className="truncate font-serif text-[17px] font-semibold leading-snug">{c.name}</div>
                    {c.openScene ? (
                      <div className="mt-0.5 inline-flex items-center gap-1.5 text-[12px] text-accent">
                        <span className="h-1.5 w-1.5 rounded-full bg-accent" />在第{c.openScene.number}场里
                      </div>
                    ) : (
                      <div className="mt-0.5 text-[12px] text-faint">此刻有空</div>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => setChatWith(c.id)}
                    title={`和${c.name}私聊`}
                    aria-label={`和${c.name}私聊`}
                    className="relative z-10 flex h-9 w-9 items-center justify-center rounded-full bg-surface-2 text-muted transition-colors hover:bg-accent-soft hover:text-accent"
                  >
                    <MessageCircle className="h-[18px] w-[18px]" />
                  </button>
                </div>
                <p className="prose-life mt-3 line-clamp-3 text-[14px] leading-[1.8] text-muted">{blurb(c.profile)}</p>
              </div>
            ))}
          </div>
        </Section>

        {w.chats.length > 0 ? (
          <Section title="私聊">
            <Card className="list overflow-hidden" style={CHAT_LIST_INSET}>
              {w.chats.map((c) => (
                <ChatRow key={c.id} chat={c} />
              ))}
            </Card>
          </Section>
        ) : null}

        {closed.length > 0 ? (
          <Section title="演过的戏">
            <div className="grid gap-3">
              {closed.map((s) => (
                <SceneCard key={s.id} scene={s} />
              ))}
            </div>
          </Section>
        ) : null}

        <Section title="只有叙事者知道">
          <Card className="p-5">
            {reveal ? (
              <p className="prose-life fade-enter text-[15px] leading-[1.85]">{w.narratorText}</p>
            ) : (
              <div className="flex items-start gap-3">
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-surface-2 text-muted">
                  <Lock className="h-4 w-4" />
                </span>
                <div className="min-w-0 flex-1 text-[13px] leading-relaxed text-muted">
                  叙事者用来推动故事的底牌，人物们不知道。看了之后，你会比他们知道得多。
                  <div>
                    <button onClick={() => setReveal(true)} className="mt-1 font-medium text-accent hover:underline">
                      仍然要看
                    </button>
                  </div>
                </div>
              </div>
            )}
          </Card>
        </Section>
      </Page>
      <NewChatSheet open={chatWith !== null} onOpenChange={(v) => !v && setChatWith(null)} worldId={id} otherId={chatWith || undefined} />
    </>
  )
}

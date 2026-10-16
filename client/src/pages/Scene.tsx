import { AlertCircle, ChevronRight, Clapperboard, Eye, EyeOff, Flag, Globe, Hourglass, MoreHorizontal, RotateCw, ScrollText, Users } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { stream, type StreamEvent } from '@/lib/sse'
import { errorMessage } from '@/lib/query'
import { useShowInner, useStickToBottom } from '@/lib/hooks'
import { toneLabel } from '@/lib/labels'
import type { Line, Person, ScenePage } from '@/lib/types'
import { PageHeader } from '@/components/app/PageHeader'
import { Composer } from '@/components/app/Composer'
import { JumpToBottom } from '@/components/app/JumpToBottom'
import { Narration, Ornament, PersonBlock, SceneOpening, toBlocks, TypingBubble } from '@/components/app/script'
import { identityText } from '@/components/app/summaries'
import { Avatar } from '@/components/ui/avatar'
import { Button, IconButton } from '@/components/ui/button'
import { Chip, OptionRow, Segmented } from '@/components/ui/choice'
import { ErrorState, Notice, PageLoading, Typing } from '@/components/ui/feedback'
import { Menu, MenuItem, MenuSeparator } from '@/components/ui/menu'
import { Sheet } from '@/components/ui/sheet'

type Status = { step: string; speakerId?: string | null } | null
type DraftBeat = { speakerId: string; kind: string; text: string }
type Action = 'say' | 'do' | 'wait'

export function ScenePage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const navigate = useNavigate()
  const [streaming, setStreaming] = useState(false)
  const [status, setStatus] = useState<Status>(null)
  const [drafts, setDrafts] = useState<DraftBeat[]>([])
  const [notice, setNotice] = useState<{ tone: 'warn' | 'danger'; text: string } | null>(null)
  const [failedRun, setFailedRun] = useState<string | null>(null)
  const [action, setAction] = useState<Action>('say')
  const [text, setText] = useState('')
  const [showInner, setShowInner] = useShowInner()
  const [identityOpen, setIdentityOpen] = useState(false)
  const [endOpen, setEndOpen] = useState(false)

  const query = useQuery({
    queryKey: ['scene', id],
    queryFn: () => api.get<ScenePage>(`/scenes/${id}`),
    refetchInterval: (q) => (q.state.data?.scene.running && !streaming ? 1500 : false),
  })
  const data = query.data
  const scene = data?.scene

  const names = useMemo(() => {
    const map = new Map<string, Person>()
    scene?.cast.forEach((p) => map.set(p.id, p))
    return map
  }, [scene?.cast])
  const person = (pid: string): Person => names.get(pid) ?? { id: pid, name: '？' }

  const append = useCallback(
    (lines: Line[]) =>
      client.setQueryData<ScenePage>(['scene', id], (old) => {
        if (!old) return old
        const known = new Set(old.lines.map((l) => l.id))
        return { ...old, lines: [...old.lines, ...lines.filter((l) => !known.has(l.id))] }
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
            setStatus(e.data)
            break
          case 'draft':
            setDrafts((d) => [...d, e.data])
            break
          case 'discard':
            setDrafts([])
            break
          case 'saved':
            append((e.data as { lines?: Line[] }).lines ?? [])
            setDrafts([])
            break
          case 'notice':
            setNotice({ tone: 'warn', text: e.data.message })
            onRejected?.()
            break
          case 'failed':
            setFailedRun(e.data.runId ?? null)
            break
          case 'error':
            setNotice({ tone: 'danger', text: e.data.error })
            onRejected?.()
            break
        }
      }
      try {
        await stream(path, body, onEvent)
      } finally {
        setStreaming(false)
        setStatus(null)
        setDrafts([])
        await client.invalidateQueries({ queryKey: ['scene', id] })
        void client.invalidateQueries({ queryKey: ['home'] })
      }
    },
    [append, client, id],
  )

  // 刚建好的一场（或中断过的一步）：自动接着做。失败的一步等你点重试。
  const resumed = useRef<string | null>(null)
  useEffect(() => {
    if (!scene || streaming || resumed.current === id) return
    if (scene.status === 'open' && data.pending.length > 0 && !scene.failure && !scene.running) {
      resumed.current = id
      void run(`/scenes/${id}/resume`, {})
    }
  }, [scene, data?.pending.length, streaming, id, run])

  const { ref: feedRef, toBottom, away } = useStickToBottom([data?.lines.length, drafts.length, status?.step, status?.speakerId, notice])

  if (query.isPending) {
    return (
      <div className="flex h-full flex-col">
        <PageHeader title="一场戏" back="/" />
        <div className="mx-auto w-full max-w-2xl p-4">
          <PageLoading rows={5} />
        </div>
      </div>
    )
  }
  if (query.isError || !data || !scene) {
    return (
      <div className="flex h-full flex-col">
        <PageHeader title="一场戏" back="/" />
        <ErrorState message={errorMessage(query.error)} onRetry={() => query.refetch()} />
      </div>
    )
  }

  const open = scene.status === 'open'
  const busy = streaming || scene.running
  const failed = open && !busy && !!scene.failure
  const possessed = scene.possessedId ? person(scene.possessedId) : null
  const follow = scene.followId ? person(scene.followId) : null
  const blocks = toBlocks(data.lines)
  const failedStep = data.pending[0]

  const submit = () => {
    if (busy) return
    const said = text
    if (action !== 'wait' && !said.trim()) return
    setText('')
    toBottom()
    void run(`/scenes/${id}/act`, { action, text: action === 'wait' ? null : said }, () => setText(said))
  }

  const draftBlock = (() => {
    if (!status || status.step !== 'character' || !status.speakerId) return null
    const mine = drafts.filter((d) => d.speakerId === status.speakerId)
    return (
      <PersonBlock
        person={person(status.speakerId)}
        lines={mine}
        inner={null}
        byUser={false}
        showInner={false}
        live
        footer={<TypingBubble />}
      />
    )
  })()

  const stepLabel: Record<string, string> = {
    opening: '叙事者正在开场',
    narrator: '叙事者在看',
    closing: '叙事者正在收尾',
    consent: '看看对方愿不愿意',
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader
        narrow
        bordered
        back={`/worlds/${scene.worldId}`}
        title={`第${scene.number}场 · ${scene.worldName}`}
        subtitle={`${toneLabel[scene.tone]} · ${scene.cast.map((p) => p.name).join('、')}${open ? ` · ${identityText(scene)}` : ' · 已结束'}`}
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
              <MenuItem icon={<Users />} disabled={!open || busy} onSelect={() => setIdentityOpen(true)}>
                换身份
              </MenuItem>
              <MenuItem icon={<Flag />} disabled={!open || busy} onSelect={() => setEndOpen(true)}>
                结束这一场
              </MenuItem>
              <MenuSeparator />
              <MenuItem icon={<ScrollText />} onSelect={() => navigate(`/runs?sceneId=${id}`)}>
                幕后记录
              </MenuItem>
              <MenuItem icon={<Globe />} onSelect={() => navigate(`/worlds/${scene.worldId}`)}>
                回到{scene.worldName}
              </MenuItem>
            </Menu>
          </>
        }
      />

      <div className="relative min-h-0 flex-1">
        <div ref={feedRef} className="scrollbar-thin h-full overflow-y-auto">
          <div className="mx-auto w-full max-w-2xl px-4 pb-8 pt-4">
            {data.previousClosing ? (
              <details className="group mb-6 rounded-2xl bg-surface-2/60 px-4 py-3 text-[13px] text-muted">
                <summary className="flex list-none items-center gap-1.5 select-none [&::-webkit-details-marker]:hidden">
                  <ChevronRight className="h-3.5 w-3.5 transition-transform group-open:rotate-90" />
                  上一场的结尾
                </summary>
                <p className="prose-life mt-2 text-[14px] leading-[1.85] text-text/75">{data.previousClosing}</p>
              </details>
            ) : null}
            <SceneOpening number={scene.number} setup={scene.setup} />

            {blocks.map((b) =>
              b.type === 'narration' ? (
                <Narration key={b.key} text={b.line.text} closing={b.line.kind === 'closing'} />
              ) : (
                <PersonBlock
                  key={b.key}
                  person={person(b.speakerId)}
                  lines={b.lines}
                  inner={b.inner?.text ?? null}
                  byUser={b.byUser}
                  showInner={showInner}
                />
              ),
            )}

            {draftBlock}
            {status && status.step !== 'character' ? <Progress label={stepLabel[status.step] ?? '稍等'} /> : null}
            {!streaming && scene.running ? <Progress label="这一场正在进行" /> : null}

            {failed ? (
              <div className="enter my-6 rounded-2xl bg-danger-soft/70 p-4 shadow-[inset_0_0_0_1px_color-mix(in_oklab,var(--danger)_18%,transparent)]">
                <p className="flex items-center gap-2 text-[14px] font-medium text-danger">
                  <AlertCircle className="h-4 w-4" />
                  {failedStep === 'opening'
                    ? '开场没有写成'
                    : failedStep === 'narrator'
                      ? '叙事者这一步没有成功'
                      : failedStep === 'closing'
                        ? '收尾没有写成'
                        : `${person(failedStep ?? '').name}这一步没有成功`}
                </p>
                <p className="mt-1 pl-6 text-[13px] leading-relaxed text-muted">{scene.failure}</p>
                <div className="mt-3 flex flex-wrap gap-2 pl-6">
                  <Button size="sm" onClick={() => run(`/scenes/${id}/resume`, {})}>
                    <RotateCw />
                    重试
                  </Button>
                  {failedStep === 'closing' ? (
                    <Button size="sm" variant="outline" onClick={() => run(`/scenes/${id}/end`, { closing: false })}>
                      不要收尾，直接结束
                    </Button>
                  ) : null}
                  <Link to={failedRun ? `/runs/${failedRun}` : `/runs?sceneId=${id}`}>
                    <Button size="sm" variant="ghost">
                      看幕后
                    </Button>
                  </Link>
                </div>
              </div>
            ) : null}

            {notice ? (
              <Notice tone={notice.tone} className="my-5">
                {notice.text}
              </Notice>
            ) : null}

            {!open ? (
              <div className="enter mx-auto mb-4 mt-12 max-w-md text-center">
                <Ornament>终</Ornament>
                <p className="mt-5 text-[16px] font-medium">这一场结束了</p>
                <p className="mx-auto mt-2 max-w-sm text-[13px] leading-relaxed text-muted">
                  在场的人会把这场经历按各自看到的整理进心里。去人物主页，看看他们记住了什么。
                </p>
                <div className="mt-5 flex justify-center gap-2">
                  <Link to={`/worlds/${scene.worldId}`}>
                    <Button variant="outline">回到{scene.worldName}</Button>
                  </Link>
                  <Link to={`/worlds/${scene.worldId}/new-scene`}>
                    <Button>
                      <Clapperboard />
                      再开一场
                    </Button>
                  </Link>
                </div>
              </div>
            ) : null}
          </div>
        </div>
        <JumpToBottom show={away} onClick={() => toBottom(true)} />
      </div>

      {open ? (
        <div className="pb-safe shrink-0 bg-bg shadow-[0_-1px_0_var(--border)]">
          <div className="mx-auto w-full max-w-2xl px-3 pb-3 pt-2.5 md:px-4">
            {possessed ? (
              <>
                <div className="mb-2 flex items-center gap-2 px-1">
                  <Avatar name={possessed.name} size={20} />
                  <span className="min-w-0 truncate text-[12px] text-muted">
                    你是<span className="font-medium text-text">{possessed.name}</span>
                  </span>
                  <Segmented
                    size="sm"
                    className="ml-auto"
                    value={action}
                    onChange={setAction}
                    options={[
                      { value: 'say', label: '说' },
                      { value: 'do', label: '做' },
                      { value: 'wait', label: '静候' },
                    ]}
                  />
                </div>
                {action === 'wait' ? (
                  <Button className="h-11 w-full rounded-[22px]" variant="outline" disabled={busy || failed} onClick={submit}>
                    <Hourglass />
                    静候，看看他们怎么做
                  </Button>
                ) : (
                  <Composer
                    value={text}
                    onChange={setText}
                    onSubmit={submit}
                    canSubmit={!!text.trim()}
                    disabled={busy || failed}
                    placeholder={action === 'say' ? `以${possessed.name}的身份说…` : `${possessed.name}做什么…`}
                  />
                )}
              </>
            ) : (
              <div className="flex items-center gap-3 py-0.5 pl-1">
                <div className="flex min-w-0 flex-1 items-center gap-2 text-[12px] text-muted">
                  {follow ? <Avatar name={follow.name} size={20} /> : null}
                  <span className="truncate">
                    旁观中，镜头跟着<span className="font-medium text-text">{follow?.name}</span>
                  </span>
                </div>
                <Button className="rounded-full px-5" disabled={busy || failed} onClick={() => run(`/scenes/${id}/act`, { action: 'continue' })}>
                  继续
                  <ChevronRight className="-mr-1" />
                </Button>
              </div>
            )}
          </div>
        </div>
      ) : null}

      <IdentitySheet
        open={identityOpen}
        onOpenChange={setIdentityOpen}
        cast={scene.cast}
        possessedId={scene.possessedId}
        followId={scene.followId}
        onDone={() => client.invalidateQueries({ queryKey: ['scene', id] })}
        sceneId={id}
      />
      <Sheet
        open={endOpen}
        onOpenChange={setEndOpen}
        title="结束这一场"
        description="结束后不能再继续。在场的每个人都会把这一场整理进心里。"
      >
        <div className="space-y-2">
          <Button
            size="lg"
            className="w-full"
            onClick={() => {
              setEndOpen(false)
              toBottom()
              void run(`/scenes/${id}/end`, { closing: true })
            }}
          >
            让叙事者收个尾，再结束
          </Button>
          <Button
            size="lg"
            className="w-full"
            variant="secondary"
            onClick={() => {
              setEndOpen(false)
              void run(`/scenes/${id}/end`, { closing: false })
            }}
          >
            直接结束
          </Button>
        </div>
      </Sheet>
    </div>
  )
}

/** 叙事者或某一步正在进行：居中的一枚小标签。 */
function Progress({ label }: { label: string }) {
  return (
    <div className="fade-enter my-6 flex justify-center">
      <span className="rounded-full bg-surface-2/80 px-3.5 py-1.5">
        <Typing label={label} />
      </span>
    </div>
  )
}

function IdentitySheet({
  open,
  onOpenChange,
  cast,
  possessedId,
  followId,
  sceneId,
  onDone,
}: {
  open: boolean
  onOpenChange: (v: boolean) => void
  cast: Person[]
  possessedId: string | null
  followId: string | null
  sceneId: string
  onDone: () => void
}) {
  const [choice, setChoice] = useState<string>(possessedId ?? 'watch')
  const [follow, setFollow] = useState<string>(followId ?? cast[0]?.id ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (open) {
      setChoice(possessedId ?? 'watch')
      setFollow(followId ?? cast[0]?.id ?? '')
      setError(null)
    }
  }, [open, possessedId, followId, cast])
  const save = async () => {
    setSaving(true)
    setError(null)
    try {
      await api.post(`/scenes/${sceneId}/identity`, choice === 'watch' ? { possessedId: null, followId: follow } : { possessedId: choice, followId: null })
      onDone()
      onOpenChange(false)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setSaving(false)
    }
  }
  return (
    <Sheet
      open={open}
      onOpenChange={onOpenChange}
      title="换身份"
      description="只换你在这一场里的身份，已经发生的不会改。"
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button loading={saving} onClick={save}>
            确定
          </Button>
        </>
      }
    >
      <div className="space-y-2">
        {cast.map((p) => (
          <OptionRow
            key={p.id}
            selected={choice === p.id}
            onClick={() => setChoice(p.id)}
            disabled={cast.length < 2}
            leading={<Avatar name={p.name} size={28} />}
          >
            附身{p.name}
          </OptionRow>
        ))}
        <OptionRow selected={choice === 'watch'} onClick={() => setChoice('watch')} hint="不扮演任何人，看他们自己发展">
          旁观
        </OptionRow>
        {choice === 'watch' ? (
          <div className="fade-enter px-1 pt-2">
            <div className="mb-2 text-[12px] text-muted">镜头跟着谁</div>
            <div className="flex flex-wrap gap-2">
              {cast.map((p) => (
                <Chip key={p.id} selected={follow === p.id} onClick={() => setFollow(p.id)} leading={<Avatar name={p.name} size={22} />}>
                  {p.name}
                </Chip>
              ))}
            </div>
          </div>
        ) : null}
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Sheet>
  )
}

import { Check, Lock, PenLine, RotateCw, Sparkles, Trash2, Wand2 } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { stream, type StreamEvent } from '@/lib/sse'
import { errorMessage } from '@/lib/query'
import { profileSections } from '@/lib/labels'
import type { Answer, Born, Draft, DraftCharacter, DraftPage, Question, Revision } from '@/lib/types'
import { cn } from '@/lib/cn'
import { Card, Page, PageHeader, Section } from '@/components/app/PageHeader'
import { Avatar } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/choice'
import { ErrorState, Notice, PageLoading, Spinner, Typing } from '@/components/ui/feedback'
import { Input, Label, Textarea } from '@/components/ui/field'
import { Sheet } from '@/components/ui/sheet'

type Progress = { names: string[]; checking: boolean; notice: string | null; error: string | null }

export function DraftPageView() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const [writing, setWriting] = useState(false)
  const [progress, setProgress] = useState<Progress>({ names: [], checking: false, notice: null, error: null })
  const query = useQuery({
    queryKey: ['draft', id],
    queryFn: () => api.get<DraftPage>(`/drafts/${id}`),
    refetchInterval: (q) => (q.state.data?.status === 'writing' && !writing ? 2000 : false),
  })
  const page = query.data

  const write = useCallback(
    async (answers: Answer[]) => {
      setWriting(true)
      setProgress({ names: [], checking: false, notice: null, error: null })
      client.setQueryData<DraftPage>(['draft', id], (old) => (old ? { ...old, status: 'writing', failure: null } : old))
      const onEvent = (e: StreamEvent) => {
        if (e.event === 'status') {
          const name = e.data.name
          if (e.data.step === 'checking') setProgress((p) => ({ ...p, checking: true }))
          else if (name) setProgress((p) => (p.names.includes(name) ? p : { ...p, names: [...p.names, name] }))
        } else if (e.event === 'discard') {
          setProgress((p) => ({ ...p, names: [] }))
        } else if (e.event === 'saved') {
          const draft = (e.data as { draft?: DraftPage }).draft
          if (draft) client.setQueryData(['draft', id], draft)
        } else if (e.event === 'notice') {
          setProgress((p) => ({ ...p, notice: e.data.message }))
        } else if (e.event === 'error') {
          setProgress((p) => ({ ...p, error: e.data.error }))
        }
      }
      try {
        await stream(`/drafts/${id}/write`, { answers }, onEvent)
      } finally {
        setWriting(false)
        await client.invalidateQueries({ queryKey: ['draft', id] })
        void client.invalidateQueries({ queryKey: ['home'] })
      }
    },
    [client, id],
  )

  // 没有要问的：直接写。
  const autoWrote = useRef<string | null>(null)
  useEffect(() => {
    if (page && page.status === 'asking' && page.questions && page.questions.length === 0 && !page.failure && !writing) {
      if (autoWrote.current === id) return
      autoWrote.current = id
      void write([])
    }
  }, [page, writing, id, write])

  const title = page?.worldName ? `给「${page.worldName}」添人物` : '一念生'
  if (query.isPending) {
    return (
      <>
        <PageHeader title="一念生" back="/" />
        <Page>
          <PageLoading />
        </Page>
      </>
    )
  }
  if (query.isError || !page) {
    return (
      <>
        <PageHeader title="一念生" back="/" />
        <ErrorState message={errorMessage(query.error)} onRetry={() => query.refetch()} />
      </>
    )
  }

  return (
    <>
      <PageHeader title={title} back={page.worldId ? `/worlds/${page.worldId}` : '/'} actions={<DeleteDraft page={page} />} />
      <Page>
        <div className="mb-6 rounded-2xl bg-surface-2/70 px-4 py-3">
          <div className="text-[11px] text-faint">你的一念</div>
          <p className="mt-0.5 font-serif text-[16px] leading-relaxed">{page.request}</p>
        </div>
        {page.status === 'asking' ? (
          <Asking page={page} onWrite={write} writing={writing} />
        ) : page.status === 'writing' || writing ? (
          <Writing progress={progress} live={writing} />
        ) : page.status === 'failed' ? (
          <div>
            <Notice tone="danger" action={<Button size="sm" onClick={() => write(page.answers)}>重写</Button>}>
              没写成：{page.failure}
            </Notice>
            {progress.error ? <Notice tone="danger" className="mt-2">{progress.error}</Notice> : null}
          </div>
        ) : page.status === 'born' ? (
          <Empty born={page} />
        ) : page.content ? (
          <Review page={page} content={page.content} notice={progress.notice} />
        ) : null}
      </Page>
    </>
  )
}

function Empty({ born }: { born: DraftPage }) {
  return (
    <div className="py-10 text-center">
      <p className="text-[15px] font-medium">他们已经诞生了</p>
      {born.worldId ? (
        <Link to={`/worlds/${born.worldId}`} className="mt-3 inline-block text-accent">
          去看看
        </Link>
      ) : null}
    </div>
  )
}

function Asking({ page, onWrite, writing }: { page: DraftPage; onWrite: (a: Answer[]) => void; writing: boolean }) {
  const client = useQueryClient()
  const [asking, setAsking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const questions = page.questions
  const [picked, setPicked] = useState<Record<number, string>>({})
  const [custom, setCustom] = useState<Record<number, string>>({})
  useEffect(() => {
    if (questions) setPicked(Object.fromEntries(questions.map((q, i) => [i, q.recommended])))
  }, [questions])

  const askAgain = async () => {
    setAsking(true)
    setError(null)
    try {
      client.setQueryData(['draft', page.id], await api.post<DraftPage>(`/drafts/${page.id}/ask`))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setAsking(false)
    }
  }

  if (!questions) {
    return (
      <div className="space-y-3">
        {page.failure ? (
          <Notice tone="danger">没能想好要问你什么：{page.failure}</Notice>
        ) : (
          <Typing label="正在想有没有要先问你的" />
        )}
        {error ? <Notice tone="danger">{error}</Notice> : null}
        <div className="flex gap-2">
          <Button variant="outline" loading={asking} onClick={askAgain}>
            <RotateCw className="h-4 w-4" />
            再问一次
          </Button>
          <Button variant="ghost" disabled={writing} onClick={() => onWrite([])}>
            跳过，直接写
          </Button>
        </div>
      </div>
    )
  }
  if (questions.length === 0) return <Writing progress={{ names: [], checking: false, notice: null, error: null }} live />

  const answers: Answer[] = questions.map((q: Question, i) => ({ question: q.question, answer: (custom[i]?.trim() || picked[i]) ?? '' }))
  return (
    <div>
      <p className="mb-5 text-[14px] text-muted">动笔之前，有几件事想先问你。不确定就用推荐的。</p>
      <div className="space-y-6">
        {questions.map((q, i) => (
          <div key={i}>
            <div className="mb-2.5 text-[15px] font-medium">{q.question}</div>
            <div className="flex flex-wrap gap-2">
              {q.options.map((o) => (
                <Chip
                  key={o}
                  selected={!custom[i]?.trim() && picked[i] === o}
                  onClick={() => {
                    setPicked((p) => ({ ...p, [i]: o }))
                    setCustom((c) => ({ ...c, [i]: '' }))
                  }}
                  badge={o === q.recommended ? <span className="text-[10px] opacity-70">推荐</span> : null}
                >
                  {o}
                </Chip>
              ))}
            </div>
            <Input
              className="mt-2 h-9 text-[14px]"
              placeholder="或者自己写"
              value={custom[i] ?? ''}
              onChange={(e) => setCustom((c) => ({ ...c, [i]: e.target.value }))}
            />
          </div>
        ))}
      </div>
      <div className="mt-8 flex gap-2">
        <Button size="lg" className="flex-1" disabled={writing} onClick={() => onWrite(answers)}>
          <Sparkles className="h-4 w-4" />
          就这样，开始写
        </Button>
        <Button size="lg" variant="ghost" disabled={writing} onClick={() => onWrite([])}>
          跳过
        </Button>
      </div>
    </div>
  )
}

function Writing({ progress, live }: { progress: Progress; live: boolean }) {
  return (
    <div className="py-6">
      <div className="mb-5 flex items-center gap-3">
        <Spinner className="text-accent" />
        <div>
          <div className="text-[15px] font-medium">{progress.checking ? '正在检查前后是否一致' : '正在写'}</div>
          <div className="text-[12px] text-muted">
            {live ? '写完会自动检查一遍。可以离开这一页，回来时会接着显示。' : '还在写，写完这里会自动更新。'}
          </div>
        </div>
      </div>
      {progress.names.length > 0 ? (
        <ol className="space-y-2 border-l border-border pl-4">
          {progress.names.map((n, i) => {
            const done = i < progress.names.length - 1 || progress.checking
            return (
              <li key={n} className="flex items-center gap-2 text-[14px]">
                {done ? <Check className="h-4 w-4 text-accent" /> : <Spinner className="h-4 w-4 text-muted" />}
                <span className={done ? 'text-text' : 'text-muted'}>{n === '世界' ? '世界' : n}</span>
              </li>
            )
          })}
        </ol>
      ) : null}
    </div>
  )
}

type Target = { kind: 'world' } | { kind: 'character'; index: number }

function Review({ page, content, notice }: { page: DraftPage; content: Draft; notice: string | null }) {
  const client = useQueryClient()
  const navigate = useNavigate()
  const [editing, setEditing] = useState<Target | null>(null)
  const [revising, setRevising] = useState<{ target: string; label: string } | null>(null)
  const [checking, setChecking] = useState(false)
  const [birthing, setBirthing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [revealNarrator, setRevealNarrator] = useState(true)

  const save = async (next: Draft) => {
    const updated = await api.put<DraftPage>(`/drafts/${page.id}`, next)
    client.setQueryData(['draft', page.id], updated)
  }
  const check = async () => {
    setChecking(true)
    setError(null)
    try {
      client.setQueryData(['draft', page.id], await api.post<DraftPage>(`/drafts/${page.id}/check`))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setChecking(false)
    }
  }
  const birth = async () => {
    setBirthing(true)
    setError(null)
    try {
      const born = await api.post<Born>(`/drafts/${page.id}/birth`)
      void client.invalidateQueries({ queryKey: ['home'] })
      void client.invalidateQueries({ queryKey: ['world', born.worldId] })
      navigate(`/worlds/${born.worldId}`, { replace: true })
    } catch (e) {
      setError(errorMessage(e))
      setBirthing(false)
    }
  }

  return (
    <div className="pb-24">
      <Section title="一致性">
        {page.conflicts === null ? (
          <Notice action={<Button size="sm" variant="outline" loading={checking} onClick={check}>检查</Button>}>
            {notice ?? '还没检查过前后是否矛盾。'}
          </Notice>
        ) : page.conflicts.length === 0 ? (
          <Notice action={<Button size="sm" variant="ghost" loading={checking} onClick={check}>再查一次</Button>}>
            <span className="inline-flex items-center gap-1.5">
              <Check className="h-4 w-4 text-accent" />
              没发现前后矛盾的地方
            </span>
          </Notice>
        ) : (
          <div className="space-y-2">
            {page.conflicts.map((c, i) => (
              <Notice key={i} tone="warn">
                <div>{c.problem}</div>
                {c.suggestion ? <div className="mt-1 text-text/70">建议：{c.suggestion}</div> : null}
              </Notice>
            ))}
            <Button size="sm" variant="outline" loading={checking} onClick={check}>
              改好了，再查一次
            </Button>
          </div>
        )}
      </Section>

      {content.world ? (
        <Section
          title="世界"
          className="mt-8"
          action={
            <Button size="sm" variant="ghost" onClick={() => setEditing({ kind: 'world' })}>
              <PenLine className="h-3.5 w-3.5" />
              手改
            </Button>
          }
        >
          <Card className="p-5">
            <div className="font-serif text-[20px] font-semibold">{content.world.name}</div>
            <p className="prose-life mt-3 text-[14.5px]">{content.world.publicText}</p>
            <ReviseLink onClick={() => setRevising({ target: 'world.public', label: '世界·公开' })} />
          </Card>
          <Card className="mt-3 p-5">
            <button className="flex w-full items-center gap-2 text-left text-[13px] font-medium" onClick={() => setRevealNarrator(!revealNarrator)}>
              <Lock className="h-4 w-4 text-muted" />
              只有叙事者知道
              <span className="ml-auto text-[12px] font-normal text-faint">{revealNarrator ? '收起' : '展开'}</span>
            </button>
            {revealNarrator ? (
              <>
                <p className="prose-life mt-3 text-[14.5px]">{content.world.narratorText}</p>
                <ReviseLink onClick={() => setRevising({ target: 'world.narrator', label: '只有叙事者知道' })} />
              </>
            ) : null}
          </Card>
        </Section>
      ) : null}

      <Section title={content.world ? '人物' : '新的人物'} className="mt-8">
        <div className="space-y-4">
          {content.characters.map((c, i) => (
            <CharacterDraftCard
              key={i}
              character={c}
              onEdit={() => setEditing({ kind: 'character', index: i })}
              onRevise={() => setRevising({ target: `character.${i}`, label: `${c.name}的档案` })}
            />
          ))}
        </div>
      </Section>

      {error ? (
        <Notice tone="danger" className="mt-6">
          {error}
        </Notice>
      ) : null}

      <div className="pb-safe fixed inset-x-0 bottom-0 z-10 border-t border-border bg-bg/95 backdrop-blur md:left-60">
        <div className="mx-auto flex max-w-3xl items-center gap-3 px-4 py-3 md:px-6">
          <p className="hidden flex-1 text-[12px] text-muted sm:block">诞生之后，出身就定下了；之后的变化来自他们自己的经历。</p>
          <Button size="lg" className="flex-1 sm:flex-none" loading={birthing} onClick={birth}>
            <Sparkles className="h-4 w-4" />
            让{content.characters.length > 1 ? '他们' : ' TA '}诞生
          </Button>
        </div>
      </div>

      <EditSheet target={editing} content={content} onClose={() => setEditing(null)} onSave={save} />
      <ReviseSheet draftId={page.id} target={revising} content={content} onClose={() => setRevising(null)} onSave={save} />
    </div>
  )
}

function ReviseLink({ onClick }: { onClick: () => void }) {
  return (
    <button onClick={onClick} className="mt-3 inline-flex items-center gap-1.5 text-[13px] text-accent">
      <Wand2 className="h-3.5 w-3.5" />
      让 AI 改这一段
    </button>
  )
}

function CharacterDraftCard({ character, onEdit, onRevise }: { character: DraftCharacter; onEdit: () => void; onRevise: () => void }) {
  const [expanded, setExpanded] = useState(false)
  const sections = profileSections(character.profile)
  const shown = expanded ? sections : sections.slice(0, 3)
  return (
    <Card className="p-5">
      <div className="flex items-center gap-3">
        <Avatar name={character.name} size={44} />
        <div className="flex-1 font-serif text-[19px] font-semibold">{character.name}</div>
        <Button size="sm" variant="ghost" onClick={onEdit}>
          <PenLine className="h-3.5 w-3.5" />
          手改
        </Button>
      </div>
      <div className="mt-4 space-y-3">
        {shown.map((s, i) => (
          <div key={i}>
            {s.title ? <div className="text-[12px] font-medium text-muted">{s.title}</div> : null}
            <p className="prose-life text-[14.5px]">{s.body}</p>
          </div>
        ))}
      </div>
      {sections.length > 3 ? (
        <button onClick={() => setExpanded(!expanded)} className="mt-2 text-[13px] text-muted hover:text-text">
          {expanded ? '收起' : `展开全部 ${sections.length} 段`}
        </button>
      ) : null}
      <div>
        <ReviseLink onClick={onRevise} />
      </div>
      {character.views.length > 0 || character.concerns.length > 0 ? (
        <div className="mt-4 space-y-2 border-t border-border pt-4">
          {character.views.map((v, i) => (
            <div key={`v${i}`} className="text-[13.5px] leading-relaxed">
              <span className="mr-1.5 rounded bg-surface-2 px-1.5 py-0.5 text-[11px] text-muted">看{v.name}</span>
              {v.text}
            </div>
          ))}
          {character.concerns.map((c, i) => (
            <div key={`c${i}`} className="text-[13.5px] leading-relaxed">
              <span className="mr-1.5 rounded bg-warn-soft px-1.5 py-0.5 text-[11px] text-warn">牵挂{c.about ? ` · ${c.about}` : ''}</span>
              {c.text}
            </div>
          ))}
        </div>
      ) : null}
    </Card>
  )
}

function EditSheet({
  target,
  content,
  onClose,
  onSave,
}: {
  target: Target | null
  content: Draft
  onClose: () => void
  onSave: (d: Draft) => Promise<void>
}) {
  const [draft, setDraft] = useState<Draft>(content)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (target) {
      setDraft(structuredClone(content))
      setError(null)
    }
  }, [target, content])
  const save = async () => {
    setSaving(true)
    setError(null)
    try {
      await onSave({
        ...draft,
        characters: draft.characters.map((x) => ({
          ...x,
          views: x.views.filter((v) => v.text.trim()),
          concerns: x.concerns.filter((v) => v.text.trim()),
        })),
      })
      onClose()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setSaving(false)
    }
  }
  const index = target?.kind === 'character' ? target.index : -1
  const c = index >= 0 ? draft.characters[index] : null
  const setC = (patch: Partial<DraftCharacter>) =>
    setDraft((d) => ({ ...d, characters: d.characters.map((x, i) => (i === index ? { ...x, ...patch } : x)) }))
  return (
    <Sheet
      open={target !== null}
      onOpenChange={(v) => !v && onClose()}
      wide
      title={target?.kind === 'world' ? '改世界' : `改${c?.name ?? ''}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button loading={saving} onClick={save}>
            保存
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        {target?.kind === 'world' && draft.world ? (
          <>
            <div>
              <Label>世界名</Label>
              <Input value={draft.world.name} onChange={(e) => setDraft({ ...draft, world: { ...draft.world!, name: e.target.value } })} />
            </div>
            <div>
              <Label hint="所有人物都知道的">公开设定</Label>
              <Textarea
                className="min-h-48"
                maxHeight={420}
                value={draft.world.publicText}
                onChange={(e) => setDraft({ ...draft, world: { ...draft.world!, publicText: e.target.value } })}
              />
            </div>
            <div>
              <Label hint="人物们不知道，叙事者用来推动故事">只有叙事者知道</Label>
              <Textarea
                className="min-h-32"
                maxHeight={360}
                value={draft.world.narratorText}
                onChange={(e) => setDraft({ ...draft, world: { ...draft.world!, narratorText: e.target.value } })}
              />
            </div>
          </>
        ) : null}
        {c ? (
          <>
            <div>
              <Label>名字</Label>
              <Input value={c.name} onChange={(e) => setC({ name: e.target.value })} />
            </div>
            <div>
              <Label hint="用【小标题】分段">出生档案</Label>
              <Textarea className="min-h-64 font-serif" maxHeight={520} value={c.profile} onChange={(e) => setC({ profile: e.target.value })} />
            </div>
            <div>
              <Label hint="诞生时 TA 对别人的看法；清空一条即删除">看法</Label>
              <div className="space-y-2">
                {c.views.map((v, i) => (
                  <div key={i} className="flex items-start gap-2">
                    <span className="mt-2.5 w-16 shrink-0 truncate text-[13px] text-muted">看{v.name}</span>
                    <Textarea
                      value={v.text}
                      onChange={(e) => setC({ views: c.views.map((x, j) => (j === i ? { ...x, text: e.target.value } : x)) })}
                    />
                  </div>
                ))}
                {c.views.length === 0 ? <p className="text-[13px] text-faint">没有</p> : null}
              </div>
            </div>
            <div>
              <Label hint="诞生时就放不下的事；清空一条即删除">牵挂</Label>
              <div className="space-y-2">
                {c.concerns.map((x, i) => (
                  <div key={i} className="flex items-start gap-2">
                    <span className="mt-2.5 w-16 shrink-0 truncate text-[13px] text-muted">{x.about ?? '自己'}</span>
                    <Textarea
                      value={x.text}
                      onChange={(e) => setC({ concerns: c.concerns.map((y, j) => (j === i ? { ...y, text: e.target.value } : y)) })}
                    />
                  </div>
                ))}
                {c.concerns.length === 0 ? <p className="text-[13px] text-faint">没有</p> : null}
              </div>
            </div>
          </>
        ) : null}
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Sheet>
  )
}

const REVISE_HINTS = ['更克制一些，少用形容词', '加一段过去留下的伤', '多一点烟火气和小习惯', '说话方式更有辨识度']

function ReviseSheet({
  draftId,
  target,
  content,
  onClose,
  onSave,
}: {
  draftId: string
  target: { target: string; label: string } | null
  content: Draft
  onClose: () => void
  onSave: (d: Draft) => Promise<void>
}) {
  const [instruction, setInstruction] = useState('')
  const [result, setResult] = useState<Revision | null>(null)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (target) {
      setInstruction('')
      setResult(null)
      setError(null)
    }
  }, [target])
  const revise = async () => {
    if (!target) return
    setWorking(true)
    setError(null)
    try {
      setResult(await api.post<Revision>(`/drafts/${draftId}/revise`, { target: target.target, instruction }))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setWorking(false)
    }
  }
  const adopt = async () => {
    if (!result || !target) return
    const next = structuredClone(content)
    const m = target.target.match(/^character\.(\d+)$/)
    if (target.target === 'world.public' && next.world) next.world.publicText = result.text
    else if (target.target === 'world.narrator' && next.world) next.world.narratorText = result.text
    else if (m) next.characters[Number(m[1])].profile = result.text
    setWorking(true)
    setError(null)
    try {
      await onSave(next)
      onClose()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setWorking(false)
    }
  }
  return (
    <Sheet
      open={target !== null}
      onOpenChange={(v) => !v && onClose()}
      wide
      title={`让 AI 改：${target?.label ?? ''}`}
      description="只改这一段，其余不动。改好后由你决定用不用。"
      footer={
        result ? (
          <>
            <Button variant="ghost" onClick={() => setResult(null)}>
              不用这一版
            </Button>
            <Button loading={working} onClick={adopt}>
              采用
            </Button>
          </>
        ) : (
          <>
            <Button variant="ghost" onClick={onClose}>
              取消
            </Button>
            <Button disabled={!instruction.trim()} loading={working} onClick={revise}>
              <Wand2 className="h-4 w-4" />
              改
            </Button>
          </>
        )
      }
    >
      {result ? (
        <div>
          <div className="mb-2 flex items-center justify-between text-[12px] text-muted">
            <span>改好的一版</span>
            <Link to={`/runs/${result.runId}`} className="text-accent">
              幕后
            </Link>
          </div>
          <div className={cn('prose-life max-h-[50dvh] overflow-y-auto rounded-xl bg-surface-2/70 p-4 text-[14.5px]')}>{result.text}</div>
        </div>
      ) : (
        <div className="space-y-3">
          <Textarea
            autoFocus
            className="min-h-24"
            placeholder="想怎么改？"
            value={instruction}
            onChange={(e) => setInstruction(e.target.value)}
          />
          <div className="flex flex-wrap gap-1.5">
            {REVISE_HINTS.map((h) => (
              <button key={h} onClick={() => setInstruction(h)} className="rounded-full bg-surface-2 px-2.5 py-1 text-[12px] text-muted hover:text-text">
                {h}
              </button>
            ))}
          </div>
          {working ? <Typing label="正在改" /> : null}
        </div>
      )}
      {error ? (
        <Notice tone="danger" className="mt-3">
          {error}
        </Notice>
      ) : null}
    </Sheet>
  )
}

function DeleteDraft({ page }: { page: DraftPage }) {
  const navigate = useNavigate()
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  if (page.status === 'born') return null
  const remove = async () => {
    try {
      await api.delete(`/drafts/${page.id}`)
      void client.invalidateQueries({ queryKey: ['home'] })
      navigate('/', { replace: true })
    } catch (e) {
      setError(errorMessage(e))
    }
  }
  return (
    <>
      <button onClick={() => setOpen(true)} className="rounded-full p-2 text-muted hover:bg-surface-2" aria-label="删除草稿">
        <Trash2 className="h-[18px] w-[18px]" />
      </button>
      <Sheet
        open={open}
        onOpenChange={setOpen}
        title="删除这份草稿？"
        description="还没诞生的人物会一起消失，不能恢复。"
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              取消
            </Button>
            <Button variant="danger" onClick={remove}>
              删除
            </Button>
          </>
        }
      >
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </Sheet>
    </>
  )
}

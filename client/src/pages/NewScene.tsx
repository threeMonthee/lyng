import { Clapperboard } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { errorMessage, useSettings, useWorld } from '@/lib/query'
import { scaleLabel } from '@/lib/labels'
import { Page, PageHeader, Section } from '@/components/app/PageHeader'
import { Avatar } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Chip, OptionRow, Segmented, Suggestion } from '@/components/ui/choice'
import { ErrorState, Notice, PageLoading } from '@/components/ui/feedback'
import { Textarea } from '@/components/ui/field'

const SETUPS = ['傍晚，雨刚停，两人在檐下躲雨时遇见。', '深夜，有人敲门，带来一个坏消息。', '集市上，一场意外让几个人不得不同路。']

export function NewScenePage() {
  const { id = '' } = useParams()
  const world = useWorld(id)
  const settings = useSettings()
  const navigate = useNavigate()
  const client = useQueryClient()
  const [cast, setCast] = useState<string[]>([])
  const [identity, setIdentity] = useState<string>('')
  const [follow, setFollow] = useState<string>('')
  const [tone, setTone] = useState<'daily' | 'adventure'>('daily')
  const [setup, setSetup] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const people = useMemo(() => world.data?.characters ?? [], [world.data])
  const chosen = people.filter((c) => cast.includes(c.id))

  if (world.isPending) {
    return (
      <>
        <PageHeader title="开一场戏" back={`/worlds/${id}`} />
        <Page>
          <PageLoading />
        </Page>
      </>
    )
  }
  if (world.isError) {
    return (
      <>
        <PageHeader title="开一场戏" back={`/worlds/${id}`} />
        <ErrorState message={errorMessage(world.error)} onRetry={() => world.refetch()} />
      </>
    )
  }

  const toggle = (cid: string) => {
    const next = cast.includes(cid) ? cast.filter((x) => x !== cid) : [...cast, cid]
    setCast(next)
    if (!next.includes(identity)) setIdentity('')
    if (!next.includes(follow)) setFollow('')
  }
  const watching = identity === 'watch'
  const valid =
    cast.length > 0 &&
    !!identity &&
    (watching ? !!follow : cast.length >= 2) &&
    !!setup.trim()

  const create = async () => {
    setSaving(true)
    setError(null)
    try {
      const { id: sceneId } = await api.post<{ id: string }>('/scenes', {
        worldId: id,
        cast,
        possessedId: watching ? null : identity,
        followId: watching ? follow : null,
        tone,
        setup,
      })
      void client.invalidateQueries({ queryKey: ['home'] })
      void client.invalidateQueries({ queryKey: ['world', id] })
      navigate(`/scenes/${sceneId}`, { replace: true })
    } catch (e) {
      setError(errorMessage(e))
      setSaving(false)
    }
  }

  return (
    <>
      <PageHeader title="开一场戏" subtitle={world.data.name} back={`/worlds/${id}`} />
      <Page>
        <Section title="谁在场">
          <div className="grid gap-2 sm:grid-cols-2">
            {people.map((c) => (
              <OptionRow
                key={c.id}
                multiple
                selected={cast.includes(c.id)}
                disabled={!!c.openScene}
                onClick={() => toggle(c.id)}
                leading={<Avatar name={c.name} size={32} />}
                hint={c.openScene ? `在第${c.openScene.number}场里，先结束那一场` : undefined}
              >
                {c.name}
              </OptionRow>
            ))}
          </div>
        </Section>

        <Section title="你是谁">
          {chosen.length === 0 ? (
            <p className="rounded-[14px] border border-dashed border-border px-4 py-3.5 text-[13px] text-faint">先选在场的人物。</p>
          ) : (
            <div className="space-y-2">
              <p className="mb-3 text-[12px] leading-relaxed text-muted">
                {chosen.length < 2 ? '只有一个人在场时只能旁观。' : '附身一个人：你替 TA 说话、行动，其他人由他们自己决定。'}
              </p>
              {chosen.map((c) => (
                <OptionRow
                  key={c.id}
                  selected={identity === c.id}
                  disabled={chosen.length < 2}
                  onClick={() => setIdentity(c.id)}
                  leading={<Avatar name={c.name} size={28} />}
                >
                  附身{c.name}
                </OptionRow>
              ))}
              <OptionRow selected={watching} onClick={() => setIdentity('watch')} hint="不扮演任何人，看他们自己发展；你点“继续”推进">
                旁观
              </OptionRow>
              {watching ? (
                <div className="fade-enter px-1 pt-3">
                  <div className="mb-2 text-[12px] text-muted">镜头跟着谁</div>
                  <div className="flex flex-wrap gap-2">
                    {chosen.map((c) => (
                      <Chip key={c.id} selected={follow === c.id} onClick={() => setFollow(c.id)} leading={<Avatar name={c.name} size={22} />}>
                        {c.name}
                      </Chip>
                    ))}
                  </div>
                </div>
              ) : null}
            </div>
          )}
        </Section>

        <Section title="基调">
          <Segmented
            value={tone}
            onChange={setTone}
            options={[
              { value: 'daily', label: '日常相处' },
              { value: 'adventure', label: '冒险' },
            ]}
          />
          <p className="mt-2 text-[12px] leading-relaxed text-muted">
            {tone === 'daily' ? '事件轻，重心在人物之间。' : '张力更足，世界的动静多一些。'}
          </p>
        </Section>

        <Section title="开场处境">
          <Textarea
            value={setup}
            onChange={(e) => setSetup(e.target.value)}
            placeholder="在哪里、什么时候、发生了什么。一两句就够。"
            maxHeight={200}
            className="min-h-28 font-serif leading-[1.8]"
          />
          <div className="mt-2.5 flex flex-wrap gap-1.5">
            {SETUPS.map((s) => (
              <Suggestion key={s} onClick={() => setSetup(s)}>
                {s}
              </Suggestion>
            ))}
          </div>
        </Section>

        <div className="mt-10 space-y-3">
          {error ? <Notice tone="danger">{error}</Notice> : null}
          <Button size="lg" className="w-full" disabled={!valid} loading={saving} onClick={create}>
            <Clapperboard />
            开场
          </Button>
          <p className="text-center text-[12px] text-faint">
            内容尺度：{scaleLabel[settings.data?.scale ?? 'mild']}，
            <Link to="/settings" className="text-muted underline decoration-border underline-offset-2 hover:text-text">
              在设置里改
            </Link>
            ，只影响之后开的戏
          </p>
        </div>
      </Page>
    </>
  )
}

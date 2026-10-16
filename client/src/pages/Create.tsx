import { Sparkles } from 'lucide-react'
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { errorMessage, useHome } from '@/lib/query'
import type { DraftPage } from '@/lib/types'
import { Page, PageHeader, Section } from '@/components/app/PageHeader'
import { Button } from '@/components/ui/button'
import { OptionRow } from '@/components/ui/choice'
import { Notice } from '@/components/ui/feedback'
import { Textarea } from '@/components/ui/field'

const EXAMPLES = [
  '雨夜的旧书店，店主和一个总在打烊前出现的客人',
  '大学天文社，社长和刚转学来的学妹，各有心事',
  '边关驿站，老驿丞、过路的女镖师和一个来历不明的少年',
]

/** 一念生的第一步：一句话。 */
export function CreatePage() {
  const [params] = useSearchParams()
  const home = useHome()
  const navigate = useNavigate()
  const client = useQueryClient()
  const [request, setRequest] = useState('')
  const [world, setWorld] = useState<string | null>(params.get('world'))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const worlds = home.data?.worlds ?? []
  const target = worlds.find((w) => w.id === world)

  const start = async () => {
    setSaving(true)
    setError(null)
    try {
      const page = await api.post<DraftPage>('/drafts', { worldId: world, request })
      client.setQueryData(['draft', page.id], page)
      void client.invalidateQueries({ queryKey: ['home'] })
      navigate(`/create/${page.id}`, { replace: true })
    } catch (e) {
      setError(errorMessage(e))
      setSaving(false)
    }
  }

  return (
    <>
      <PageHeader title="一念生" large back={params.get('world') ? `/worlds/${params.get('world')}` : undefined} />
      <Page>
        <div className="rounded-2xl border border-border bg-surface p-4 shadow-card">
          <Textarea
            autoFocus
            value={request}
            onChange={(e) => setRequest(e.target.value)}
            placeholder={target ? `想在「${target.name}」里遇见谁？` : '一句话，写下你想进入的世界、想遇见的人'}
            className="min-h-28 border-0 bg-transparent px-1 font-serif text-[17px] leading-relaxed focus:ring-0"
            maxHeight={260}
          />
          <div className="mt-2 flex items-center justify-between gap-3">
            <span className="text-[12px] text-faint">
              {target ? `会给「${target.name}」添新的人物` : '会写出一个世界和里面的几个人'}
            </span>
            <Button disabled={!request.trim()} loading={saving} onClick={start}>
              <Sparkles className="h-4 w-4" />
              开始
            </Button>
          </div>
        </div>
        {error ? (
          <Notice tone="danger" className="mt-3">
            {error}
          </Notice>
        ) : null}
        {saving ? <p className="mt-3 text-center text-[13px] text-muted">正在想有没有要先问你的…</p> : null}

        {!target ? (
          <div className="mt-4 flex flex-wrap gap-1.5">
            {EXAMPLES.map((e) => (
              <button
                key={e}
                onClick={() => setRequest(e)}
                className="rounded-full bg-surface-2 px-3 py-1.5 text-left text-[13px] text-muted hover:text-text"
              >
                {e}
              </button>
            ))}
          </div>
        ) : null}

        {worlds.length > 0 ? (
          <Section title="放在哪里" className="mt-10">
            <div className="space-y-2">
              <OptionRow selected={world === null} onClick={() => setWorld(null)} hint="连世界一起写">
                一个新世界
              </OptionRow>
              {worlds.map((w) => (
                <OptionRow
                  key={w.id}
                  selected={world === w.id}
                  onClick={() => setWorld(w.id)}
                  hint={`已有：${w.characters.map((c) => c.name).join('、')}`}
                >
                  给「{w.name}」添人物
                </OptionRow>
              ))}
            </div>
          </Section>
        ) : null}

        <p className="mt-10 text-[12px] leading-relaxed text-faint">
          写好后你可以逐段过目、手改或让 AI 改某一段。诞生之后，他们的出身就定下了，之后的变化来自他们自己的经历。
        </p>
      </Page>
    </>
  )
}

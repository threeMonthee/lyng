import { ChevronDown } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { errorMessage } from '@/lib/query'
import { fullTime, ms, usd } from '@/lib/format'
import type { RunPage } from '@/lib/types'
import { cn } from '@/lib/cn'
import { Card, Page, PageHeader, Section } from '@/components/app/PageHeader'
import { RunStatus } from '@/components/app/RunList'
import { ErrorState, Notice, PageLoading } from '@/components/ui/feedback'

const ROLE: Record<string, string> = { system: '设定', user: '输入', assistant: '模型', tool: '工具结果' }
const OUTCOME: Record<string, string> = { ok: '完成', tool: '调用工具', error: '出错', refused: '拒绝' }

export function RunPageView() {
  const { id = '' } = useParams()
  const run = useQuery({
    queryKey: ['run', id],
    queryFn: () => api.get<RunPage>(`/runs/${id}`),
    refetchInterval: (q) => (q.state.data?.run.status === 'running' ? 1500 : false),
  })
  if (run.isPending) {
    return (
      <>
        <PageHeader title="幕后" back />
        <Page>
          <PageLoading />
        </Page>
      </>
    )
  }
  if (run.isError) {
    return (
      <>
        <PageHeader title="幕后" back />
        <ErrorState message={errorMessage(run.error)} onRetry={() => run.refetch()} />
      </>
    )
  }
  const { run: r, request, output, reasoning, tools, outcome, calls } = run.data
  const duration = r.finishedAt ? new Date(r.finishedAt).getTime() - new Date(r.startedAt).getTime() : null
  return (
    <>
      <PageHeader title={r.title} subtitle={r.purposeLabel} back />
      <Page>
        <Card className="p-4">
          <div className="flex items-center gap-2 text-[14px]">
            <RunStatus status={r.status} />
            <span className="font-medium">{r.status === 'ok' ? '成功' : r.status === 'failed' ? '失败' : '进行中'}</span>
            {r.attempts > 1 ? <span className="text-muted">· 试了 {r.attempts} 次</span> : null}
          </div>
          <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-[13px] sm:grid-cols-4">
            <Fact label="开始">{fullTime(r.startedAt)}</Fact>
            <Fact label="用时">{ms(duration)}</Fact>
            <Fact label="花费">{usd(r.cost)}</Fact>
            <Fact label="模型">{request?.model ?? '—'}</Fact>
          </dl>
          <div className="mt-3 flex flex-wrap gap-3 text-[13px]">
            {r.characterId ? <Link className="text-accent" to={`/characters/${r.characterId}`}>人物主页</Link> : null}
            {r.sceneId ? <Link className="text-accent" to={`/scenes/${r.sceneId}`}>这一场</Link> : null}
            {r.conversationId ? <Link className="text-accent" to={`/chats/${r.conversationId}`}>这段私聊</Link> : null}
            {r.draftId ? <Link className="text-accent" to={`/create/${r.draftId}`}>这份草稿</Link> : null}
          </div>
        </Card>
        {r.failure ? (
          <Notice tone="danger" className="mt-3">
            {r.failure}
          </Notice>
        ) : null}

        {outcome && Object.keys(outcome).length > 0 ? (
          <Section title="最后的结果" className="mt-8">
            <Card className="divide-y divide-border">
              {Object.entries(outcome).map(([k, v]) => (
                <div key={k} className="px-4 py-3">
                  <div className="text-[12px] text-muted">{k}</div>
                  <Value value={v} />
                </div>
              ))}
            </Card>
          </Section>
        ) : null}

        {reasoning ? (
          <Section title="思考" className="mt-8">
            <Fold text={reasoning} />
          </Section>
        ) : null}

        {output ? (
          <Section title="模型写下的原文" className="mt-8">
            <Fold text={output} open />
          </Section>
        ) : null}

        {tools && tools.length > 0 ? (
          <Section title="工具" className="mt-8">
            <div className="space-y-2">
              {tools.map((t, i) => (
                <Card key={i} className="p-4 text-[13px]">
                  <div className="font-medium">
                    {t.name === 'recall' ? '回想' : t.name}
                    <span className="ml-2 font-mono text-[12px] font-normal text-muted">{t.arguments}</span>
                  </div>
                  <pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap rounded-lg bg-surface-2/70 p-3 font-sans text-[13px] leading-relaxed">
                    {t.result}
                  </pre>
                </Card>
              ))}
            </div>
          </Section>
        ) : null}

        {calls.length > 0 ? (
          <Section title="每一次调用" className="mt-8">
            <Card className="divide-y divide-border">
              {calls.map((c, i) => (
                <div key={i} className="px-4 py-3 text-[13px]">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">
                      第 {c.attempt} 次{c.step > 0 ? ` · 第 ${c.step + 1} 步` : ''}
                    </span>
                    <span className={cn(c.outcome === 'error' || c.outcome === 'refused' ? 'text-danger' : 'text-muted')}>
                      {OUTCOME[c.outcome] ?? c.outcome}
                    </span>
                    <span className="ml-auto text-faint">{usd(c.cost)}</span>
                  </div>
                  <div className="mt-1 text-[12px] text-muted">
                    {c.provider ? `${c.provider} · ` : ''}首字 {ms(c.firstTokenMs)} · 共 {ms(c.latencyMs)} · 输入 {c.promptTokens ?? '—'} · 输出{' '}
                    {c.completionTokens ?? '—'}
                    {c.reasoningTokens ? `（思考 ${c.reasoningTokens}）` : ''}
                    {c.finishReason ? ` · ${c.finishReason}` : ''}
                  </div>
                  {c.error ? <div className="mt-1 text-[12px] text-danger">{c.error}</div> : null}
                </div>
              ))}
            </Card>
          </Section>
        ) : null}

        {request ? (
          <Section title="发给模型的全部内容" className="mt-8">
            <div className="space-y-2">
              {request.messages.map((m, i) => (
                <Fold key={i} label={ROLE[m.role] ?? m.role} text={m.content ?? ''} open={i === request.messages.length - 1} />
              ))}
              {request.tools.length > 0 ? <p className="text-[12px] text-muted">可用工具：{request.tools.join('、')}</p> : null}
            </div>
          </Section>
        ) : null}
      </Page>
    </>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="text-[12px] text-faint">{label}</dt>
      <dd className="truncate">{children}</dd>
    </div>
  )
}

function Value({ value }: { value: unknown }) {
  if (value === null || value === undefined || value === '') return <p className="text-[14px] text-faint">（无）</p>
  if (typeof value === 'string') return <p className="prose-life text-[14.5px]">{value}</p>
  if (Array.isArray(value)) {
    if (value.length === 0) return <p className="text-[14px] text-faint">（无）</p>
    return (
      <ul className="mt-1 space-y-1">
        {value.map((v, i) => (
          <li key={i} className="text-[14px]">
            {typeof v === 'string' ? <span className="prose-life">{v}</span> : <Value value={v} />}
          </li>
        ))}
      </ul>
    )
  }
  if (typeof value === 'object') {
    return (
      <div className="mt-1 space-y-1">
        {Object.entries(value as Record<string, unknown>).map(([k, v]) => (
          <div key={k} className="text-[14px]">
            <span className="text-muted">{k}：</span>
            {typeof v === 'string' ? v : JSON.stringify(v)}
          </div>
        ))}
      </div>
    )
  }
  return <p className="text-[14px]">{String(value)}</p>
}

function Fold({ text, label, open: initial }: { text: string; label?: string; open?: boolean }) {
  const [open, setOpen] = useState(!!initial)
  const long = text.length > 280
  return (
    <Card className="overflow-hidden">
      {label ? (
        <button onClick={() => setOpen(!open)} className="flex w-full items-center gap-2 px-4 py-2.5 text-left text-[13px]">
          <span className="font-medium">{label}</span>
          <span className="truncate text-faint">{open ? '' : text.slice(0, 60)}</span>
          <span className="ml-auto shrink-0 text-[12px] text-faint">{text.length} 字</span>
          <ChevronDown className={cn('h-4 w-4 shrink-0 text-faint transition', open && 'rotate-180')} />
        </button>
      ) : null}
      {open || !label ? (
        <div className={cn('relative', label && 'border-t border-border')}>
          <pre
            className={cn(
              'whitespace-pre-wrap break-words px-4 py-3 font-sans text-[13px] leading-relaxed',
              !label && long && !open && 'max-h-40 overflow-hidden',
            )}
          >
            {text}
          </pre>
          {!label && long ? (
            <button onClick={() => setOpen(!open)} className="w-full border-t border-border py-2 text-[12px] text-muted hover:text-text">
              {open ? '收起' : '展开全部'}
            </button>
          ) : null}
        </div>
      ) : null}
    </Card>
  )
}

import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { ChevronRight } from 'lucide-react'
import { api } from '@/lib/api'
import { errorMessage, useHome, useSettings, useUsage } from '@/lib/query'
import { useTheme, type Theme } from '@/lib/hooks'
import { ms, usd } from '@/lib/format'
import { purposeLabel } from '@/lib/labels'
import type { Settings } from '@/lib/types'
import { Card, Page, PageHeader, Section } from '@/components/app/PageHeader'
import { Button } from '@/components/ui/button'
import { Segmented } from '@/components/ui/choice'
import { ErrorState, Notice, PageLoading } from '@/components/ui/feedback'
import { Input } from '@/components/ui/field'

export function SettingsPage() {
  const settings = useSettings()
  const usage = useUsage()
  const home = useHome()
  const client = useQueryClient()
  const [theme, setTheme] = useTheme()
  const [budget, setBudget] = useState('')
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (settings.data) setBudget(settings.data.budgetUsd === null ? '' : String(settings.data.budgetUsd))
  }, [settings.data])

  const update = async (patch: Partial<Settings>, message: string) => {
    if (!settings.data) return
    setSaving(true)
    setError(null)
    setSaved(null)
    try {
      const next = await api.put<Settings>('/settings', {
        scale: patch.scale ?? settings.data.scale,
        budgetUsd: 'budgetUsd' in patch ? patch.budgetUsd : settings.data.budgetUsd,
      })
      client.setQueryData(['settings'], next)
      void client.invalidateQueries({ queryKey: ['usage'] })
      setSaved(message)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setSaving(false)
    }
  }

  const saveBudget = () => {
    const value = budget.trim() === '' ? null : Number(budget)
    if (value !== null && (!Number.isFinite(value) || value < 0)) {
      setError('费用上限要是一个不小于 0 的数')
      return
    }
    void update({ budgetUsd: value }, value === null ? '已取消费用上限' : `费用上限改为 ${usd(value)}`)
  }

  return (
    <>
      <PageHeader title="设置" large />
      <Page>
        {settings.isPending ? (
          <PageLoading rows={3} />
        ) : settings.isError ? (
          <ErrorState message={errorMessage(settings.error)} onRetry={() => settings.refetch()} />
        ) : (
          <>
            <Section title="内容尺度">
              <Card className="p-4">
                <Segmented
                  value={settings.data.scale}
                  onChange={(scale) => update({ scale }, scale === 'adult' ? '已改为成人' : '已改为含蓄')}
                  options={[
                    { value: 'mild', label: '含蓄' },
                    { value: 'adult', label: '成人' },
                  ]}
                />
                <p className="mt-3 text-[13px] leading-relaxed text-muted">
                  {settings.data.scale === 'mild'
                    ? '亲密的场面点到为止。'
                    : '允许直白的亲密描写。人物仍然按自己的意愿行事，可以拒绝；对方拒绝过之后，你附身做出的亲近动作不会发生。'}
                  一场戏的尺度在开场时定下，改这里只影响之后开的戏和私聊。
                </p>
              </Card>
            </Section>

            <Section title="费用">
              <Card className="p-4">
                {usage.data ? <Spend spent={usage.data.spent} budget={usage.data.budget} /> : null}
                <div className="mt-4 flex items-center gap-2">
                  <span className="text-[14px] text-muted">上限 $</span>
                  <Input
                    inputMode="decimal"
                    className="w-32"
                    placeholder="不设上限"
                    value={budget}
                    onChange={(e) => setBudget(e.target.value)}
                  />
                  <Button variant="outline" loading={saving} onClick={saveBudget}>
                    保存
                  </Button>
                </div>
                <p className="mt-2 text-[12px] leading-relaxed text-muted">
                  累计花费到达上限后，不再调用模型；正在进行的戏会停在那一步，提高上限后可以重试。
                </p>
              </Card>
            </Section>

            {saved ? <Notice className="mt-4">{saved}</Notice> : null}
            {error ? (
              <Notice tone="danger" className="mt-4">
                {error}
              </Notice>
            ) : null}

            <Section title="用量" className="mt-8">
              {usage.isPending ? (
                <PageLoading rows={2} />
              ) : usage.isError ? (
                <ErrorState message={errorMessage(usage.error)} />
              ) : (
                <Card className="overflow-hidden">
                  {usage.data.purposes.length === 0 ? (
                    <p className="p-4 text-[13px] text-muted">还没有调用过模型。</p>
                  ) : (
                    <table className="w-full text-[13px]">
                      <thead className="text-left text-[12px] text-faint">
                        <tr className="border-b border-border">
                          <th className="px-4 py-2 font-normal">用途</th>
                          <th className="px-2 py-2 text-right font-normal">次数</th>
                          <th className="px-2 py-2 text-right font-normal">失败</th>
                          <th className="hidden px-2 py-2 text-right font-normal sm:table-cell">中位用时</th>
                          <th className="px-4 py-2 text-right font-normal">花费</th>
                        </tr>
                      </thead>
                      <tbody>
                        {usage.data.purposes.map((p) => (
                          <tr key={p.purpose} className="border-b border-border last:border-0">
                            <td className="px-4 py-2">{purposeLabel[p.purpose] ?? p.purpose}</td>
                            <td className="px-2 py-2 text-right tabular-nums">{p.calls}</td>
                            <td className="px-2 py-2 text-right tabular-nums">{p.failures || '—'}</td>
                            <td className="hidden px-2 py-2 text-right tabular-nums sm:table-cell">{ms(p.medianMs)}</td>
                            <td className="px-4 py-2 text-right tabular-nums">{usd(p.cost)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                  {usage.data.days.length > 0 ? <Days days={usage.data.days} /> : null}
                </Card>
              )}
            </Section>

            <Section title="外观" className="mt-8">
              <Card className="p-4">
                <Segmented<Theme>
                  value={theme}
                  onChange={setTheme}
                  options={[
                    { value: 'system', label: '跟随系统' },
                    { value: 'light', label: '浅色' },
                    { value: 'dark', label: '深色' },
                  ]}
                />
              </Card>
            </Section>

            <Section title="更多" className="mt-8">
              <Card className="overflow-hidden">
                <Link to="/runs" className="flex items-center px-4 py-3 text-[14px] hover:bg-surface-2/60">
                  <span className="flex-1">幕后：全部运行记录</span>
                  <ChevronRight className="h-4 w-4 text-faint" />
                </Link>
              </Card>
              <p className="mt-4 text-[12px] leading-relaxed text-faint">
                所有数据只保存在这台电脑的数据库里。
                {home.data?.fake ? ' 当前是替身模式，不会调用任何模型，也不产生费用。' : ''}
              </p>
            </Section>
          </>
        )}
      </Page>
    </>
  )
}

function Spend({ spent, budget }: { spent: number; budget: number | null }) {
  const ratio = budget ? Math.min(1, spent / budget) : 0
  return (
    <div>
      <div className="flex items-baseline gap-2">
        <span className="text-[24px] font-semibold tabular-nums">{usd(spent)}</span>
        <span className="text-[13px] text-muted">{budget !== null ? `/ 上限 ${usd(budget)}` : '已花费，未设上限'}</span>
      </div>
      {budget !== null ? (
        <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-surface-2">
          <div className={ratio >= 0.9 ? 'h-full bg-danger' : 'h-full bg-accent'} style={{ width: `${ratio * 100}%` }} />
        </div>
      ) : null}
    </div>
  )
}

function Days({ days }: { days: { day: string; cost: number; calls: number }[] }) {
  const max = Math.max(...days.map((d) => d.calls), 1)
  return (
    <div className="border-t border-border p-4">
      <div className="mb-2 text-[12px] text-faint">最近两周每天的调用次数</div>
      <div className="flex h-20 items-end gap-1">
        {days.map((d) => (
          <div key={d.day} className="flex flex-1 flex-col items-center justify-end" title={`${d.day}：${d.calls} 次，${usd(d.cost)}`}>
            <div className="w-full rounded-sm bg-accent/70" style={{ height: `${Math.max(4, (d.calls / max) * 100)}%` }} />
          </div>
        ))}
      </div>
      <div className="mt-1 flex justify-between text-[11px] text-faint">
        <span>{days[0].day.slice(5)}</span>
        <span>{days[days.length - 1].day.slice(5)}</span>
      </div>
    </div>
  )
}

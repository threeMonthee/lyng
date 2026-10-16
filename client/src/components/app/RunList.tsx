import { CircleAlert, CircleCheck, LoaderCircle } from 'lucide-react'
import { Link } from 'react-router'
import { useInfiniteQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { errorMessage } from '@/lib/query'
import { shortTime, usd } from '@/lib/format'
import type { RunItem } from '@/lib/types'
import { Card } from './PageHeader'
import { Button } from '@/components/ui/button'
import { Empty, ErrorState, PageLoading } from '@/components/ui/feedback'

const PAGE = 30

export function RunStatus({ status }: { status: RunItem['status'] }) {
  if (status === 'ok') return <CircleCheck className="h-4 w-4 text-accent" aria-label="成功" />
  if (status === 'failed') return <CircleAlert className="h-4 w-4 text-danger" aria-label="失败" />
  return <LoaderCircle className="h-4 w-4 animate-spin text-muted" aria-label="进行中" />
}

/** 每一次模型调用的记录。filter 是 characterId / sceneId / conversationId 之一。 */
export function RunList({ filter }: { filter: Record<string, string> }) {
  const search = new URLSearchParams(filter).toString()
  const runs = useInfiniteQuery({
    queryKey: ['runs', filter],
    queryFn: ({ pageParam }) => api.get<RunItem[]>(`/runs?${search}${search ? '&' : ''}offset=${pageParam}&limit=${PAGE}`),
    initialPageParam: 0,
    getNextPageParam: (last, all) => (last.length < PAGE ? undefined : all.length * PAGE),
    refetchInterval: (q) => (q.state.data?.pages[0]?.some((r) => r.status === 'running') ? 2000 : false),
  })
  if (runs.isPending) return <PageLoading />
  if (runs.isError) return <ErrorState message={errorMessage(runs.error)} onRetry={() => runs.refetch()} />
  const items = runs.data.pages.flat()
  if (items.length === 0) {
    return <Empty title="还没有记录">人物每一次开口、叙事者每一次落笔、每一次整理内心，都会在这里留下完整的记录。</Empty>
  }
  return (
    <>
      <Card className="divide-y divide-border overflow-hidden">
        {items.map((r) => (
          <Link key={r.id} to={`/runs/${r.id}`} className="flex items-center gap-3 px-4 py-3 transition hover:bg-surface-2/60">
            <RunStatus status={r.status} />
            <div className="min-w-0 flex-1">
              <div className="truncate text-[14px]">{r.title}</div>
              <div className="mt-0.5 truncate text-[12px] text-muted">
                {r.purposeLabel}
                {r.attempts > 1 ? ` · 第 ${r.attempts} 次才成` : ''}
                {r.status === 'failed' && r.failure ? ` · ${r.failure}` : ''}
              </div>
            </div>
            <div className="shrink-0 text-right text-[12px] text-faint">
              <div>{shortTime(r.startedAt)}</div>
              <div>{usd(r.cost)}</div>
            </div>
          </Link>
        ))}
      </Card>
      {runs.hasNextPage ? (
        <div className="mt-3 flex justify-center">
          <Button variant="ghost" size="sm" loading={runs.isFetchingNextPage} onClick={() => runs.fetchNextPage()}>
            更早的记录
          </Button>
        </div>
      ) : null}
    </>
  )
}

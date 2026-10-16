import { useSearchParams } from 'react-router'
import { Page, PageHeader } from '@/components/app/PageHeader'
import { RunList } from '@/components/app/RunList'

/** 幕后：全部记录，或某一场 / 某段私聊的记录。 */
export function RunsPage() {
  const [params] = useSearchParams()
  const filter: Record<string, string> = {}
  for (const key of ['characterId', 'sceneId', 'conversationId']) {
    const value = params.get(key)
    if (value) filter[key] = value
  }
  const scoped = Object.keys(filter).length > 0
  return (
    <>
      <PageHeader
        title="幕后"
        subtitle={params.get('sceneId') ? '这一场戏' : params.get('conversationId') ? '这段私聊' : undefined}
        back={scoped ? true : undefined}
        large={!scoped}
      />
      <Page>
        {!scoped ? (
          <p className="mb-4 text-[13px] leading-relaxed text-muted">
            每一次调用模型的完整记录：发给模型的全部内容、模型的思考、工具调用、最后的结果，以及耗时和花费。
          </p>
        ) : null}
        <RunList filter={filter} />
      </Page>
    </>
  )
}

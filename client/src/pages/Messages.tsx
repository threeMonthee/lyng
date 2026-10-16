import { MessageCircle, Plus } from 'lucide-react'
import { useState } from 'react'
import { useChats, errorMessage, useHome } from '@/lib/query'
import { Card, Page, PageHeader } from '@/components/app/PageHeader'
import { CHAT_LIST_INSET, ChatRow } from '@/components/app/summaries'
import { NewChatSheet } from '@/components/app/NewChat'
import { Button } from '@/components/ui/button'
import { Empty, ErrorState, PageLoading } from '@/components/ui/feedback'

export function MessagesPage() {
  const chats = useChats()
  const home = useHome()
  const [open, setOpen] = useState(false)
  const many = (home.data?.worlds.length ?? 0) > 1
  return (
    <>
      <PageHeader
        title="消息"
        large
        actions={
          <Button size="sm" variant="secondary" className="mr-1.5 rounded-full pl-2.5" onClick={() => setOpen(true)}>
            <Plus />
            新的私聊
          </Button>
        }
      />
      <Page className="px-0 md:px-6">
        {chats.isPending ? (
          <PageLoading />
        ) : chats.isError ? (
          <ErrorState message={errorMessage(chats.error)} onRetry={() => chats.refetch()} />
        ) : chats.data.length === 0 ? (
          <Empty
            icon={<MessageCircle className="h-8 w-8" />}
            title="还没有私聊"
            action={<Button onClick={() => setOpen(true)}>开始一段私聊</Button>}
          >
            附身一个人物，给另一个人物发消息。他们不在一起的时候，也会记得彼此说过的话；有心事的人，可能会在你回来时主动找你。
          </Empty>
        ) : (
          <Card
            className="list overflow-hidden rounded-none bg-transparent shadow-none md:rounded-2xl md:bg-surface md:shadow-card"
            style={CHAT_LIST_INSET}
          >
            {chats.data.map((c) => (
              <ChatRow key={c.id} chat={c} showWorld={many} />
            ))}
          </Card>
        )}
      </Page>
      <NewChatSheet open={open} onOpenChange={setOpen} />
    </>
  )
}

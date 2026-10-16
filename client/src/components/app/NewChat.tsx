import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { errorMessage, useHome } from '@/lib/query'
import type { ChatSummary, Person } from '@/lib/types'
import { Avatar } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/choice'
import { Notice } from '@/components/ui/feedback'
import { Label } from '@/components/ui/field'
import { Sheet } from '@/components/ui/sheet'

/** 开一段私聊：选你附身谁、和谁聊。可以预先定好世界或对方。 */
export function NewChatSheet({
  open,
  onOpenChange,
  worldId,
  otherId,
}: {
  open: boolean
  onOpenChange: (v: boolean) => void
  worldId?: string
  otherId?: string
}) {
  const home = useHome()
  const navigate = useNavigate()
  const client = useQueryClient()
  const worlds = home.data?.worlds ?? []
  const [world, setWorld] = useState<string | undefined>(worldId)
  const [played, setPlayed] = useState<string | null>(null)
  const [other, setOther] = useState<string | null>(otherId ?? null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setWorld(worldId ?? (worlds.length === 1 ? worlds[0].id : undefined))
      setPlayed(null)
      setOther(otherId ?? null)
      setError(null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  const people: Person[] = worlds.find((w) => w.id === world)?.characters ?? []
  const start = async () => {
    if (!played || !other) return
    setSaving(true)
    setError(null)
    try {
      const chat = await api.post<ChatSummary>('/chats', { playedId: played, otherId: other })
      void client.invalidateQueries({ queryKey: ['chats'] })
      onOpenChange(false)
      navigate(`/chats/${chat.id}`)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setSaving(false)
    }
  }

  const pick = (list: Person[], value: string | null, onPick: (id: string) => void, disabled?: string | null) => (
    <div className="flex flex-wrap gap-2">
      {list.map((p) => (
        <Chip
          key={p.id}
          disabled={p.id === disabled}
          selected={value === p.id}
          onClick={() => onPick(p.id)}
          leading={<Avatar name={p.name} size={26} />}
          className="py-1 text-[14px]"
        >
          {p.name}
        </Chip>
      ))}
    </div>
  )

  return (
    <Sheet
      open={open}
      onOpenChange={onOpenChange}
      title="新的私聊"
      description="你附身其中一个人物，和另一个人物用文字私聊。说过的话会成为他们共同的经历。"
      footer={
        <>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button disabled={!played || !other || played === other} loading={saving} onClick={start}>
            开始私聊
          </Button>
        </>
      }
    >
      <div className="space-y-5">
        {!worldId && worlds.length > 1 ? (
          <div>
            <Label>哪个世界</Label>
            <div className="flex flex-wrap gap-2">
              {worlds.map((w) => (
                <Chip
                  key={w.id}
                  selected={world === w.id}
                  onClick={() => {
                    setWorld(w.id)
                    setPlayed(null)
                    setOther(null)
                  }}
                  leading={<Avatar name={w.name} size={26} square />}
                  className="py-1 text-[14px]"
                >
                  {w.name}
                </Chip>
              ))}
            </div>
          </div>
        ) : null}
        {world ? (
          <>
            <div>
              <Label hint="你说的每句话都以 TA 的身份发出">你附身</Label>
              {pick(people, played, (pid) => {
                setPlayed(pid)
                if (other === pid) setOther(null)
              }, otherId)}
            </div>
            <div>
              <Label>和谁私聊</Label>
              {pick(people, other, setOther, played)}
            </div>
          </>
        ) : worlds.length === 0 ? (
          <Notice>还没有任何世界。先去一念生创造几个人物。</Notice>
        ) : null}
        {error ? <Notice tone="danger">{error}</Notice> : null}
      </div>
    </Sheet>
  )
}

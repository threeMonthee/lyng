import { describe, expect, it } from 'vitest'
import type { Message } from '@/lib/types'
import { toTurns } from './Chat'

let next = 1
const msg = (patch: Partial<Message>): Message => ({
  id: next++,
  speakerId: 'a',
  kind: 'say',
  text: '嗯',
  byUser: false,
  opportunity: 'reply',
  unread: false,
  runId: 'r1',
  createdAt: '2026-10-16T00:00:00Z',
  ...patch,
})

describe('toTurns', () => {
  it('你连着发的几句是一段；对方同一次开口的几句和心声是一段', () => {
    const turns = toTurns([
      msg({ speakerId: 'me', byUser: true, runId: null, opportunity: 'user' }),
      msg({ speakerId: 'me', byUser: true, runId: null, opportunity: 'user' }),
      msg({ speakerId: 'a', runId: 'r1' }),
      msg({ speakerId: 'a', runId: 'r1' }),
      msg({ speakerId: 'a', runId: 'r1', kind: 'inner' }),
      msg({ speakerId: 'a', runId: 'r2', opportunity: 'continue' }),
    ])
    expect(turns.map((t) => [t.says.length, !!t.inner])).toEqual([
      [2, false],
      [2, true],
      [1, false],
    ])
  })

  it('只有心声的一次（没有回）单独成段', () => {
    const turns = toTurns([msg({ kind: 'inner', runId: 'r3' })])
    expect(turns).toHaveLength(1)
    expect(turns[0].says).toHaveLength(0)
    expect(turns[0].inner).not.toBeNull()
  })
})

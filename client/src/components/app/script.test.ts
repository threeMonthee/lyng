import { describe, expect, it } from 'vitest'
import type { Line } from '@/lib/types'
import { toBlocks } from './script'

let next = 1
const line = (patch: Partial<Line>): Line => ({
  id: next++,
  round: 1,
  kind: 'say',
  speakerId: 'a',
  text: '……',
  byUser: false,
  runId: null,
  createdAt: '2026-10-16T00:00:00Z',
  ...patch,
})

describe('toBlocks', () => {
  it('同一个人同一轮的言行和心声合成一段，心声之后另起一段', () => {
    const lines = [
      line({ kind: 'narration', speakerId: null, round: 0 }),
      line({ kind: 'do', speakerId: 'a' }),
      line({ kind: 'say', speakerId: 'a' }),
      line({ kind: 'inner', speakerId: 'a' }),
      line({ kind: 'say', speakerId: 'a' }),
      line({ kind: 'inner', speakerId: 'a' }),
    ]
    const blocks = toBlocks(lines)
    expect(blocks.map((b) => b.type)).toEqual(['narration', 'person', 'person'])
    const first = blocks[1]
    expect(first.type === 'person' && first.lines.map((l) => l.kind)).toEqual(['do', 'say'])
    expect(first.type === 'person' && first.inner?.id).toBe(lines[3].id)
  })

  it('你做的和人物做的分开，换轮次也分开', () => {
    const blocks = toBlocks([
      line({ speakerId: 'a', byUser: true, round: 1 }),
      line({ speakerId: 'a', byUser: false, round: 1 }),
      line({ speakerId: 'a', byUser: false, round: 2 }),
    ])
    expect(blocks).toHaveLength(3)
  })

  it('只有心声（沉默）也成一段', () => {
    const blocks = toBlocks([line({ kind: 'wait', speakerId: 'b', text: '' }), line({ kind: 'inner', speakerId: 'b' })])
    expect(blocks).toHaveLength(1)
    expect(blocks[0].type === 'person' && blocks[0].lines[0].kind).toBe('wait')
  })
})

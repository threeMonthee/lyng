import { describe, expect, it } from 'vitest'
import { profileSections } from './labels'

describe('profileSections', () => {
  it('按【小标题】分段，标题后同一行的文字算正文，开头没标题的一段保留', () => {
    const sections = profileSections('陆雪琪\n【小传】青云门弟子。\n师从水月。\n【此刻】二十岁\n【空】')
    expect(sections).toEqual([
      { title: null, body: '陆雪琪' },
      { title: '小传', body: '青云门弟子。\n师从水月。' },
      { title: '此刻', body: '二十岁' },
      { title: '空', body: '' },
    ])
  })

  it('没有小标题时整段作为一段', () => {
    expect(profileSections('只有一段话')).toEqual([{ title: null, body: '只有一段话' }])
  })
})

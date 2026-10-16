// 界面上的说法，集中在一处。

export const toneLabel: Record<string, string> = { daily: '日常相处', adventure: '冒险' }

export const scaleLabel: Record<string, string> = { mild: '含蓄', adult: '成人' }

export const purposeLabel: Record<string, string> = {
  OPENING: '叙事者开场',
  NARRATOR: '叙事者',
  CLOSING: '叙事者收尾',
  CHARACTER: '开口',
  CONSENT: '同意检查',
  CONSOLIDATE: '整理内心',
  ASK: '构思追问',
  WRITE: '写稿',
  REVISE: '局部修改',
  CHECK: '一致性检查',
}

export const concernStatusLabel: Record<string, string> = { active: '放不下', background: '暂时放下', resolved: '已了结' }

/** 档案按【小标题】分段，用于排版。第一段可能没有标题（例如档案第一行是姓名）。 */
export function profileSections(profile: string): { title: string | null; body: string }[] {
  const sections: { title: string | null; body: string }[] = []
  let current: { title: string | null; lines: string[] } = { title: null, lines: [] }
  for (const line of profile.split('\n')) {
    const m = line.trim().match(/^【([^】]{1,20})】(.*)$/)
    if (m) {
      if (current.title !== null || current.lines.some((l) => l.trim())) {
        sections.push({ title: current.title, body: current.lines.join('\n').trim() })
      }
      current = { title: m[1], lines: m[2] ? [m[2]] : [] }
    } else {
      current.lines.push(line)
    }
  }
  if (current.title !== null || current.lines.some((l) => l.trim())) {
    sections.push({ title: current.title, body: current.lines.join('\n').trim() })
  }
  return sections
}

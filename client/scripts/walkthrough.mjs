// 用替身模式的本地服务把主要流程走一遍，在电脑和手机宽度下截图，并检查页面没有报错。
// 用法：先 scripts/start.sh --fake 启动服务，再 npm run walkthrough。
// LYNG_URL 改服务地址，LYNG_SHOTS 改截图目录，LYNG_CHROMIUM 指定浏览器。
import { chromium } from '@playwright/test'
import { existsSync, mkdirSync } from 'node:fs'
import path from 'node:path'

const base = process.env.LYNG_URL ?? 'http://127.0.0.1:8080'
const shots = path.resolve(process.env.LYNG_SHOTS ?? 'test-results/walkthrough')
const executablePath =
  process.env.LYNG_CHROMIUM ?? (existsSync('/opt/pw-browsers/chromium') ? '/opt/pw-browsers/chromium' : undefined)
mkdirSync(shots, { recursive: true })

const problems = []
const browser = await chromium.launch({ executablePath })

async function open(name, viewport, options = {}) {
  const context = await browser.newContext({
    viewport,
    deviceScaleFactor: options.scale ?? 1,
    colorScheme: options.dark ? 'dark' : 'light',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    hasTouch: options.touch ?? false,
  })
  const page = await context.newPage()
  page.on('pageerror', (e) => problems.push(`[${name}] 页面异常：${e.message}`))
  page.on('console', (m) => {
    if (m.type() === 'error') problems.push(`[${name}] 控制台错误：${m.text()}`)
  })
  return { context, page }
}

let shotNo = 0
async function shot(page, name) {
  shotNo += 1
  const file = path.join(shots, `${String(shotNo).padStart(2, '0')}-${name}.png`)
  await page.waitForTimeout(250)
  await page.screenshot({ path: file })
  console.log('截图', path.basename(file))
}

async function api(page, method, url, body) {
  return page.evaluate(
    async ({ method, url, body }) => {
      const r = await fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })
      return r.json()
    },
    { method, url, body },
  )
}

/** 等这一场（或私聊）里正在进行的步骤结束：输入区重新可用。 */
async function settled(page) {
  await page.waitForFunction(() => !document.querySelector('[class*="typing-dot"]'), null, { timeout: 30000 })
}

const desktop = { width: 1280, height: 860 }
const phone = { width: 390, height: 844 }

// ---------- 电脑：把流程走一遍 ----------
{
  const { context, page } = await open('电脑', desktop)
  await page.goto(base)
  await page.getByText('诛仙').first().waitFor()
  await shot(page, '首页')

  // 一念生：一句话 → 追问 → 写稿 → 让 AI 改一段 → 诞生
  await page.getByRole('link', { name: '一念生' }).first().click()
  await page.getByPlaceholder('一句话，写下你想进入的世界、想遇见的人').fill('雨夜的旧书店，店主和一个总在打烊前出现的客人')
  await shot(page, '一念生-一句话')
  await page.getByRole('button', { name: '开始' }).click()
  await page.getByText('动笔之前，有几件事想先问你').waitFor()
  await shot(page, '一念生-追问')
  await page.getByRole('button', { name: '就这样，开始写' }).click()
  await page.getByText('正在写').first().waitFor()
  await shot(page, '一念生-正在写')
  await page.getByRole('button', { name: /诞生/ }).waitFor({ timeout: 30000 })
  await page.getByText('没发现前后矛盾的地方').waitFor({ timeout: 30000 })
  await shot(page, '一念生-过目')
  await page.getByRole('button', { name: '让 AI 改这一段' }).nth(1).click()
  await page.getByPlaceholder('想怎么改？').fill('多一点烟火气和小习惯')
  await page.getByRole('button', { name: '改', exact: true }).click()
  await page.getByRole('button', { name: '采用' }).waitFor()
  await shot(page, '一念生-AI改一段')
  await page.getByRole('button', { name: '采用' }).click()
  await page.getByRole('dialog').waitFor({ state: 'detached' })
  await page.getByRole('button', { name: /诞生/ }).click()
  await page.getByRole('link', { name: '开一场戏' }).waitFor()
  await shot(page, '新世界')
  const newWorld = page.url()

  // 新世界里旁观一场：两个人，镜头跟着第一个
  await page.getByRole('link', { name: '开一场戏' }).click()
  await page.getByRole('checkbox').first().waitFor()
  for (const box of await page.getByRole('checkbox').all()) await box.click()
  await page.getByRole('radio', { name: /旁观/ }).click()
  await page.getByText('镜头跟着谁').locator('..').getByRole('button').first().click()
  await page.getByRole('button', { name: '傍晚，雨刚停，两人在檐下躲雨时遇见。' }).click()
  await page.getByRole('button', { name: '开场', exact: true }).click()
  await page.waitForURL(/\/scenes\//)
  await settled(page)
  for (let i = 0; i < 3; i++) {
    await page.getByRole('button', { name: '继续', exact: true }).click()
    await page.waitForTimeout(300)
    await settled(page)
  }
  await shot(page, '旁观一场')

  // 诛仙：附身张小凡，三人同场
  await page.goto(base)
  await page.getByRole('link', { name: /诛仙/ }).first().click()
  await page.getByRole('link', { name: '开一场戏' }).click()
  for (const name of ['陆雪琪', '张小凡', '碧瑶']) await page.getByRole('checkbox', { name: new RegExp(name) }).click()
  await page.getByRole('radio', { name: /附身张小凡/ }).click()
  await page.getByPlaceholder('在哪里、什么时候、发生了什么。一两句就够。').fill('深夜，青云山后山的竹林，雨刚停。三人在小溪边不期而遇。')
  await shot(page, '开一场戏')
  await page.getByRole('button', { name: '开场', exact: true }).click()
  await page.waitForURL(/\/scenes\//)
  const scene = page.url()
  await settled(page)
  const composer = page.getByPlaceholder('以张小凡的身份说…')
  await composer.fill('雪琪，你怎么也在这里？')
  await composer.press('Enter')
  await page.waitForTimeout(400)
  await settled(page)
  await page.getByRole('radio', { name: '做' }).click()
  await page.getByPlaceholder('张小凡做什么…').fill('把外袍解下来，递给碧瑶。')
  await page.getByRole('button', { name: '发送' }).click()
  await page.waitForTimeout(400)
  await settled(page)
  await shot(page, '附身一场')
  await page.getByRole('button', { name: '隐藏心声' }).click()
  await shot(page, '附身一场-隐藏心声')
  await page.getByRole('button', { name: '显示心声' }).click()
  await page.getByRole('button', { name: '更多' }).click()
  await page.getByRole('menuitem', { name: '结束这一场' }).click()
  await page.getByRole('button', { name: '让叙事者收个尾，再结束' }).click()
  await page.getByText('这一场结束了').waitFor({ timeout: 30000 })
  await shot(page, '一场结束')

  // 私聊：附身张小凡，和陆雪琪
  await page.goto(base + '/messages')
  await shot(page, '消息-空')
  await page.getByRole('button', { name: '新的私聊' }).first().click()
  await page.getByRole('dialog').getByRole('button', { name: '诛仙' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '张小凡' }).first().click()
  await page.getByRole('dialog').getByRole('button', { name: '陆雪琪' }).nth(1).click()
  await page.getByRole('button', { name: '开始私聊' }).click()
  await page.waitForURL(/\/chats\//)
  const chat = page.url()
  const input = page.getByPlaceholder('以张小凡的身份发消息')
  await input.fill('刚才在竹林里，你是不是生气了？')
  await input.press('Enter')
  await page.waitForTimeout(400)
  await settled(page)
  await input.fill('我不是故意的。\n下次……我先问你。')
  await page.getByRole('button', { name: '发送' }).click()
  await page.waitForTimeout(400)
  await settled(page)
  await page.getByRole('button', { name: '等陆雪琪接着说' }).click()
  await page.waitForTimeout(400)
  await settled(page)
  await shot(page, '私聊')
  await page.goto(base + '/messages')
  await shot(page, '消息')

  // 等整理内心完成，再看人物主页
  await page.waitForTimeout(3000)
  const home = await api(page, 'GET', '/api/home')
  const lu = home.worlds.find((w) => w.name === '诛仙').characters.find((c) => c.name === '陆雪琪')
  const character = `${base}/characters/${lu.id}`
  for (const [tab, label] of [
    ['about', '关于'],
    ['concerns', '牵挂'],
    ['memories', '记忆'],
    ['views', '看法'],
    ['experiences', '经历'],
    ['runs', '幕后'],
  ]) {
    await page.goto(tab === 'about' ? character : `${character}?tab=${tab}`)
    await page.getByRole('tab', { name: new RegExp(label) }).waitFor()
    await page.waitForTimeout(400)
    await shot(page, `人物-${label}`)
  }
  await page.getByRole('tab', { name: /看法/ }).click()
  const history = page.getByRole('button', { name: /变过/ }).first()
  if (await history.count()) {
    await history.click()
    await page.getByRole('dialog').waitFor()
    await shot(page, '人物-看法的变化')
    await page.keyboard.press('Escape')
  }
  await page.getByRole('tab', { name: /经历/ }).click()
  await page.getByRole('button', { name: /第\d+场/ }).first().click()
  await page.getByRole('dialog').waitFor()
  await shot(page, '人物-经历原样')
  await page.keyboard.press('Escape')

  await page.goto(`${scene.replace(/\/scenes\/.*/, '')}/runs?sceneId=${scene.split('/').pop()}`)
  await page.getByRole('link').filter({ hasText: '第' }).first().waitFor()
  await shot(page, '幕后-这一场')
  await page.goto(base + '/runs')
  await page.getByRole('link').filter({ hasText: '开口' }).first().click()
  await page.getByText('发给模型的全部内容').waitFor()
  await shot(page, '幕后-一次开口')
  await page.goto(base + '/settings')
  await page.getByText('用量').first().waitFor()
  await shot(page, '设置')
  await context.close()

  // ---------- 深色 ----------
  {
    const { context, page } = await open('深色', desktop, { dark: true })
    await page.goto(scene)
    await page.getByText('这一场结束了').waitFor()
    await shot(page, '深色-一场')
    await page.goto(chat)
    await page.getByPlaceholder('以张小凡的身份发消息').waitFor()
    await shot(page, '深色-私聊')
    await context.close()
  }

  // ---------- 手机 ----------
  {
    const { context, page } = await open('手机', phone, { scale: 2, touch: true })
    await page.goto(base)
    await page.locator('main').getByText('诛仙').first().waitFor()
    await shot(page, '手机-首页')
    await page.goto(scene)
    await page.getByText('这一场结束了').waitFor()
    await page.evaluate(() => window.scrollTo(0, 0))
    await shot(page, '手机-一场')
    await page.goto(chat)
    await page.getByPlaceholder('以张小凡的身份发消息').waitFor()
    await shot(page, '手机-私聊')
    await page.goto(newWorld)
    await page.locator('main').getByText('开一场戏').first().waitFor()
    await shot(page, '手机-世界')
    await page.goto(character + '?tab=memories')
    await page.getByRole('tab', { name: /记忆/ }).waitFor()
    await page.waitForTimeout(400)
    await shot(page, '手机-人物记忆')
    await page.goto(base + '/create')
    await shot(page, '手机-一念生')
    await page.goto(base + '/messages')
    await shot(page, '手机-消息')
    await page.getByRole('button', { name: '新的私聊' }).click()
    await page.getByRole('dialog').waitFor()
    await shot(page, '手机-新的私聊')
    await context.close()
  }
}

await browser.close()
if (problems.length > 0) {
  console.error('\n页面上出现了问题：\n' + problems.join('\n'))
  process.exit(1)
}
console.log(`\n走查完成，截图在 ${shots}`)

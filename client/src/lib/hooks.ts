import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api } from './api'

/** 只存在这台设备上的偏好（心声开关、主题）。读写失败时退回默认值。 */
export function useLocalStorage<T>(key: string, initial: T): [T, (value: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = localStorage.getItem(key)
      return raw === null ? initial : (JSON.parse(raw) as T)
    } catch {
      return initial
    }
  })
  const set = useCallback(
    (next: T) => {
      setValue(next)
      try {
        localStorage.setItem(key, JSON.stringify(next))
      } catch {
        // 隐私模式下写不进去，本次会话内仍然有效
      }
    },
    [key],
  )
  return [value, set]
}

export type Theme = 'system' | 'light' | 'dark'

function applyTheme(theme: Theme) {
  const dark = theme === 'dark' || (theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
  document.documentElement.classList.toggle('dark', dark)
}

export function useTheme(): [Theme, (t: Theme) => void] {
  const [theme, setTheme] = useState<Theme>(() => {
    try {
      const t = localStorage.getItem('lyng.theme')
      return t === 'light' || t === 'dark' ? t : 'system'
    } catch {
      return 'system'
    }
  })
  useEffect(() => {
    applyTheme(theme)
    if (theme !== 'system') return
    const media = matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => applyTheme('system')
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [theme])
  const set = useCallback((t: Theme) => {
    setTheme(t)
    try {
      if (t === 'system') localStorage.removeItem('lyng.theme')
      else localStorage.setItem('lyng.theme', t)
    } catch {
      // 忽略
    }
  }, [])
  return [theme, set]
}

/** 心声是否显示，场景与私聊共用一个开关。 */
export function useShowInner() {
  return useLocalStorage('lyng.inner', true)
}

/**
 * “你回来了”：打开页面或切回来时告诉服务端。可能有人物在后台给你发消息，
 * 之后一分钟里隔一阵刷新一次消息列表。
 */
export function usePresence() {
  const client = useQueryClient()
  const timers = useRef<number[]>([])
  useEffect(() => {
    const announce = () => {
      api
        .post<{ reaching: number }>('/presence')
        .then(({ reaching }) => {
          if (reaching <= 0) return
          timers.current.forEach(clearTimeout)
          timers.current = [3000, 8000, 15000, 30000, 60000].map((delay) =>
            window.setTimeout(() => {
              void client.invalidateQueries({ queryKey: ['home'] })
              void client.invalidateQueries({ queryKey: ['chats'] })
            }, delay),
          )
        })
        .catch(() => {
          // 服务没起来时页面本身会提示
        })
    }
    announce()
    const onVisible = () => {
      if (document.visibilityState === 'visible') announce()
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      timers.current.forEach(clearTimeout)
    }
  }, [client])
}

/**
 * 新内容来了就滚到底部；用户往上翻看时不打扰，只亮出“回到底部”。
 * ref 是回调形式：列表在加载完之后才出现，也能挂上监听。
 */
export function useStickToBottom(deps: unknown[]) {
  const [el, setEl] = useState<HTMLDivElement | null>(null)
  const pinned = useRef(true)
  const [away, setAway] = useState(false)
  useEffect(() => {
    if (!el) return
    el.scrollTop = el.scrollHeight
    const onScroll = () => {
      const near = el.scrollHeight - el.scrollTop - el.clientHeight < 120
      pinned.current = near
      setAway(!near)
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => el.removeEventListener('scroll', onScroll)
  }, [el])
  useEffect(() => {
    if (el && pinned.current) el.scrollTop = el.scrollHeight
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [el, ...deps])
  const toBottom = useCallback(
    (smooth?: boolean) => {
      if (!el) return
      pinned.current = true
      setAway(false)
      el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
    },
    [el],
  )
  return { ref: setEl, toBottom, away }
}

/** 页面往下滚了一点：顶栏才显出分隔线。 */
export function useScrolled(threshold = 4) {
  const [scrolled, setScrolled] = useState(false)
  useEffect(() => {
    const main = document.querySelector('main')
    if (!main) return
    const onScroll = () => setScrolled(main.scrollTop > threshold)
    onScroll()
    main.addEventListener('scroll', onScroll, { passive: true })
    return () => main.removeEventListener('scroll', onScroll)
  }, [threshold])
  return scrolled
}

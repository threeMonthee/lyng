import { Home as HomeIcon, MessageCircle, ScrollText, Settings as SettingsIcon, Sparkles } from 'lucide-react'
import type { ReactNode } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router'
import { usePresence } from '@/lib/hooks'
import { useHome } from '@/lib/query'
import { cn } from '@/lib/cn'
import { Avatar } from '@/components/ui/avatar'

const NAV = [
  { to: '/', label: '首页', icon: HomeIcon, end: true },
  { to: '/create', label: '一念生', icon: Sparkles },
  { to: '/messages', label: '消息', icon: MessageCircle, badge: true },
  { to: '/runs', label: '幕后', icon: ScrollText, desktopOnly: true },
  { to: '/settings', label: '设置', icon: SettingsIcon },
]

/** 场景与私聊在手机上占满整屏，不显示底部导航。 */
function isImmersive(path: string) {
  return /^\/(scenes|chats)\/[^/]+$/.test(path)
}

export function Shell() {
  usePresence()
  const { pathname } = useLocation()
  const home = useHome()
  const unread = home.data?.unread ?? 0
  const immersive = isImmersive(pathname)
  return (
    <div className="flex h-dvh overflow-hidden">
      <aside className="hidden w-60 shrink-0 flex-col border-r border-border/80 bg-surface/50 md:flex">
        <div className="flex items-center gap-2 px-6 pb-5 pt-7">
          <NavLink to="/" className="font-serif text-[21px] font-semibold leading-none tracking-[0.08em]">
            此间有灵
          </NavLink>
          {home.data?.fake ? (
            <span className="rounded-full bg-warn-soft px-2 py-0.5 text-[10.5px] font-medium leading-4 text-warn" title="人物的话由程序拼出，不调用模型">
              替身
            </span>
          ) : null}
        </div>
        <nav className="space-y-px px-3">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                cn(
                  'flex h-9 items-center gap-3 rounded-[10px] px-3 text-[14px] transition-colors',
                  isActive ? 'bg-surface font-medium text-text shadow-card' : 'text-muted hover:bg-surface-2/70 hover:text-text',
                )
              }
            >
              <item.icon className="h-[18px] w-[18px]" />
              <span className="flex-1">{item.label}</span>
              {item.badge && unread > 0 ? <Badge>{unread}</Badge> : null}
            </NavLink>
          ))}
        </nav>
        {home.data && home.data.worlds.length > 0 ? (
          <div className="scrollbar-thin mt-7 min-h-0 flex-1 overflow-y-auto px-3 pb-6">
            <div className="px-3 pb-2 text-[12px] font-medium text-faint">世界</div>
            <div className="space-y-px">
              {home.data.worlds.map((w) => (
                <NavLink
                  key={w.id}
                  to={`/worlds/${w.id}`}
                  className={({ isActive }) =>
                    cn(
                      'flex h-9 items-center gap-2.5 rounded-[10px] px-3 text-[14px] transition-colors',
                      isActive ? 'bg-surface text-text shadow-card' : 'text-muted hover:bg-surface-2/70 hover:text-text',
                    )
                  }
                >
                  <Avatar name={w.name} size={20} square />
                  <span className="min-w-0 flex-1 truncate">{w.name}</span>
                  {w.busy ? <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-accent text-accent" title="有进行中的戏" /> : null}
                </NavLink>
              ))}
            </div>
          </div>
        ) : null}
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <main className={cn('min-h-0 flex-1', immersive ? 'flex flex-col' : 'scrollbar-thin overflow-y-auto')}>
          <Outlet />
        </main>
        {immersive ? null : (
          <nav className="pb-safe flex shrink-0 bg-surface shadow-[0_-1px_0_var(--border)] md:hidden">
            {NAV.filter((n) => !n.desktopOnly).map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                className={({ isActive }) =>
                  cn(
                    'relative flex flex-1 flex-col items-center gap-[3px] pb-1.5 pt-2 text-[10.5px] transition-colors',
                    isActive ? 'font-medium text-accent [&_svg]:[stroke-width:2.1]' : 'text-faint',
                  )
                }
              >
                <item.icon className="h-6 w-6" />
                {item.label}
                {item.badge && unread > 0 ? (
                  <span className="absolute left-1/2 top-1 ml-1.5">
                    <Badge className="ring-2 ring-surface">{unread}</Badge>
                  </span>
                ) : null}
              </NavLink>
            ))}
          </nav>
        )}
      </div>
    </div>
  )
}

export function Badge({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-danger px-[5px] text-[11px] font-semibold leading-none tabular-nums text-white dark:text-bg',
        className,
      )}
    >
      {typeof children === 'number' && children > 99 ? '99+' : children}
    </span>
  )
}

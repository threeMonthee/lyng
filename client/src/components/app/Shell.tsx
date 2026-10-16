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
      <aside className="hidden w-60 shrink-0 flex-col border-r border-border bg-surface/60 md:flex">
        <div className="px-5 pb-4 pt-6">
          <NavLink to="/" className="font-serif text-[22px] font-semibold tracking-wide">
            此间有灵
          </NavLink>
          {home.data?.fake ? (
            <div className="mt-1.5 inline-block rounded-md bg-warn-soft px-1.5 py-0.5 text-[11px] text-warn">替身模式</div>
          ) : null}
        </div>
        <nav className="space-y-0.5 px-3">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-3 rounded-xl px-3 py-2 text-[14px] transition',
                  isActive ? 'bg-surface-2 font-medium text-text' : 'text-muted hover:bg-surface-2/70 hover:text-text',
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
          <div className="scrollbar-thin mt-6 min-h-0 flex-1 overflow-y-auto px-3 pb-6">
            <div className="px-3 pb-1.5 text-[12px] text-faint">世界</div>
            {home.data.worlds.map((w) => (
              <NavLink
                key={w.id}
                to={`/worlds/${w.id}`}
                className={({ isActive }) =>
                  cn(
                    'flex items-center gap-2.5 rounded-xl px-3 py-1.5 text-[14px] transition',
                    isActive ? 'bg-surface-2 text-text' : 'text-muted hover:bg-surface-2/70 hover:text-text',
                  )
                }
              >
                <Avatar name={w.name} size={22} />
                <span className="truncate">{w.name}</span>
                {w.busy ? <span className="ml-auto h-1.5 w-1.5 shrink-0 rounded-full bg-accent" title="有进行中的戏" /> : null}
              </NavLink>
            ))}
          </div>
        ) : null}
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <main className={cn('min-h-0 flex-1', immersive ? 'flex flex-col' : 'scrollbar-thin overflow-y-auto')}>
          <Outlet />
        </main>
        {immersive ? null : (
          <nav className="pb-safe flex shrink-0 border-t border-border bg-surface/95 backdrop-blur md:hidden">
            {NAV.filter((n) => !n.desktopOnly).map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                className={({ isActive }) =>
                  cn('relative flex flex-1 flex-col items-center gap-0.5 pb-1.5 pt-2 text-[11px]', isActive ? 'text-accent' : 'text-faint')
                }
              >
                <item.icon className="h-[22px] w-[22px]" strokeWidth={1.8} />
                {item.label}
                {item.badge && unread > 0 ? (
                  <span className="absolute left-1/2 top-1 ml-2">
                    <Badge>{unread}</Badge>
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
        'inline-flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-danger px-1 text-[11px] font-medium leading-none text-white',
        className,
      )}
    >
      {typeof children === 'number' && children > 99 ? '99+' : children}
    </span>
  )
}

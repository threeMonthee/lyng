import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, Link, RouterProvider, useRouteError } from 'react-router'
import { queryClient } from '@/lib/query'
import { Shell } from '@/components/app/Shell'
import { Empty } from '@/components/ui/feedback'
import { HomePage } from '@/pages/Home'
import { CreatePage } from '@/pages/Create'
import { DraftPageView } from '@/pages/Draft'
import { WorldPage } from '@/pages/World'
import { NewScenePage } from '@/pages/NewScene'
import { ScenePage } from '@/pages/Scene'
import { MessagesPage } from '@/pages/Messages'
import { ChatPage } from '@/pages/Chat'
import { CharacterPageView } from '@/pages/Character'
import { RunsPage } from '@/pages/Runs'
import { RunPageView } from '@/pages/Run'
import { SettingsPage } from '@/pages/Settings'
import './index.css'

function NotFound() {
  return (
    <Empty title="这里什么也没有" action={<Link to="/" className="text-accent">回到首页</Link>}>
      链接可能写错了，或者那一页已经不在了。
    </Empty>
  )
}

function Crashed() {
  const error = useRouteError()
  return (
    <Empty
      title="页面出错了"
      action={
        <button className="text-accent" onClick={() => window.location.reload()}>
          重新加载
        </button>
      }
    >
      {error instanceof Error ? error.message : '出了点意外。'}
    </Empty>
  )
}

const router = createBrowserRouter([
  {
    element: <Shell />,
    errorElement: <Crashed />,
    children: [
      { path: '/', element: <HomePage /> },
      { path: '/create', element: <CreatePage /> },
      { path: '/create/:id', element: <DraftPageView /> },
      { path: '/worlds/:id', element: <WorldPage /> },
      { path: '/worlds/:id/new-scene', element: <NewScenePage /> },
      { path: '/scenes/:id', element: <ScenePage /> },
      { path: '/messages', element: <MessagesPage /> },
      { path: '/chats/:id', element: <ChatPage /> },
      { path: '/characters/:id', element: <CharacterPageView /> },
      { path: '/runs', element: <RunsPage /> },
      { path: '/runs/:id', element: <RunPageView /> },
      { path: '/settings', element: <SettingsPage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
])

createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={queryClient}>
    <RouterProvider router={router} />
  </QueryClientProvider>,
)

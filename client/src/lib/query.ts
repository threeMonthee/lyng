import { QueryClient, useQuery } from '@tanstack/react-query'
import { api, ApiError } from './api'
import type { CharacterPage, ChatSummary, DraftPage, Home, Settings, Usage, WorldPage } from './types'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 2000,
      retry: (count, error) => !(error instanceof ApiError && error.status >= 400 && error.status < 500) && count < 2,
    },
  },
})

export const useHome = () => useQuery({ queryKey: ['home'], queryFn: () => api.get<Home>('/home'), refetchInterval: 20000 })
export const useWorld = (id: string) => useQuery({ queryKey: ['world', id], queryFn: () => api.get<WorldPage>(`/worlds/${id}`) })
export const useCharacter = (id: string) =>
  useQuery({ queryKey: ['character', id], queryFn: () => api.get<CharacterPage>(`/characters/${id}`) })
export const useChats = () =>
  useQuery({ queryKey: ['chats'], queryFn: () => api.get<ChatSummary[]>('/chats'), refetchInterval: 20000 })
export const useDraft = (id: string) => useQuery({ queryKey: ['draft', id], queryFn: () => api.get<DraftPage>(`/drafts/${id}`) })
export const useSettings = () => useQuery({ queryKey: ['settings'], queryFn: () => api.get<Settings>('/settings') })
export const useUsage = () => useQuery({ queryKey: ['usage'], queryFn: () => api.get<Usage>('/usage') })

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '出了点问题'
}

// Thin fetch wrapper: adds the JWT, turns problem+json errors into Error(detail/title).
export interface Meta {
  id: string; title: string; tags: string[]; category: string | null; author: string
  version: number; sizeBytes: number; wordCount: number; createdAt: string; updatedAt: string
}
export interface Hit { document: Meta; score: number; highlights: { field: string; snippet: string }[] }
export interface SearchResult { method: string; total: number; page: number; size: number; hits: Hit[]; tookMs: number; cached: boolean }
export interface Page { items: Meta[]; page: number; size: number; total: number }
export interface Doc extends Meta { content: string; extra: Record<string, unknown> }
export interface Version { version: number; createdAt: string }
export interface Bucket { key: string; count: number }
export interface Stats { documents: number; totalSizeBytes: number; totalWords: number; byTag: Bucket[]; byCategory: Bucket[]; byAuthor: Bucket[] }
export interface ImportReport { files: { path: string; status: string; documentId: string | null; reason: string | null; warnings: string[] }[]; created: number; duplicates: number; rejected: number }
export interface Settings { method: string; weights: Record<string, number> | null }

const KEY = 'dw_token'
const EMAIL = 'dw_email' // the token carries only the user id; the header shows the email
export const token = {
  get: () => localStorage.getItem(KEY),
  set: (t: string, email: string) => { localStorage.setItem(KEY, t); localStorage.setItem(EMAIL, email) },
  email: () => localStorage.getItem(EMAIL) ?? '',
  clear: () => { localStorage.removeItem(KEY); localStorage.removeItem(EMAIL) },
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const t = token.get()
  if (t) headers.set('Authorization', `Bearer ${t}`)
  if (typeof init.body === 'string') headers.set('Content-Type', 'application/json')
  const res = await fetch(path, { ...init, headers })
  if (!res.ok) {
    const p = await res.json().catch(() => ({}))
    if (res.status === 401 && t) { token.clear(); location.reload() }
    throw new Error(p.detail || p.title || res.statusText)
  }
  return res.status === 204 ? (undefined as T) : res.json()
}

const qs = (o: Record<string, unknown>) => {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(o)) if (v !== undefined && v !== null && v !== '') p.set(k, String(v))
  return p.toString()
}

export const api = {
  auth: (mode: 'login' | 'register', email: string, password: string) =>
    call<{ accessToken: string }>(`/api/auth/${mode}`, { method: 'POST', body: JSON.stringify({ email, password }) }),
  search: (o: Record<string, unknown>) => call<SearchResult>(`/api/search?${qs(o)}`),
  methods: () => call<string[]>('/api/search/methods'),
  settings: () => call<Settings>('/api/settings/search'),
  saveSettings: (s: Settings) => call<Settings>('/api/settings/search', { method: 'PUT', body: JSON.stringify(s) }),
  documents: (o: Record<string, unknown>) => call<Page>(`/api/documents?${qs(o)}`),
  document: (id: string) => call<Doc>(`/api/documents/${id}`),
  versions: (id: string) => call<Version[]>(`/api/documents/${id}/versions`),
  version: (id: string, v: number) => call<{ content: string }>(`/api/documents/${id}/versions/${v}`),
  save: (id: string, content: string) => call<Doc>(`/api/documents/${id}`, { method: 'PUT', body: JSON.stringify({ content }) }),
  remove: (id: string) => call<void>(`/api/documents/${id}`, { method: 'DELETE' }),
  stats: () => call<Stats>('/api/stats'),
  upload: (files: File[]) => {
    const f = new FormData()
    // a picked folder keeps its relative path, so the import report shows where each file came from
    files.forEach((x) => f.append('files', x, x.webkitRelativePath || x.name))
    return call<ImportReport>('/api/import', { method: 'POST', body: f })
  },
}

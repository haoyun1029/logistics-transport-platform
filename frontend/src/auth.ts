export type Portal = 'admin' | 'dispatcher' | 'driver'
export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'DISPATCHER' | 'DRIVER'
export type Account = { id: number; username: string; displayName: string; role: Role; passwordChangeRequired: boolean }

export function portalForRole(role: Role): Portal {
  switch (role) {
    case 'SUPER_ADMIN': case 'ADMIN': return 'admin'
    case 'DISPATCHER': return 'dispatcher'
    case 'DRIVER': return 'driver'
    default: throw new Error('当前账号没有可用的业务端权限。')
  }
}

export const portalTitles: Record<Portal, string> = {
  admin: '管理员端', dispatcher: '调度员端', driver: '司机端',
}

// Each browser tab can sign in to a different business account.
export function readToken() { return sessionStorage.getItem('logistics-token') ?? '' }
export function saveToken(token: string) { sessionStorage.setItem('logistics-token', token) }
export function clearSession() {
  sessionStorage.removeItem('logistics-token')
  // Never trust the former preview's cached role.
  for (const key of ['logistics-token', 'logistics-user', 'logistics-role']) localStorage.removeItem(key)
}

export async function sessionFetch(url: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${readToken()}`)
  const response = await fetch(url, { ...init, headers })
  if (response.status === 401) {
    clearSession()
    window.location.replace('/login?expired=1')
    throw new Error('登录已失效，请重新登录。')
  }
  if (response.status === 403) {
    const error = await response.clone().json().catch(() => null)
    if (error?.code === 'PASSWORD_CHANGE_REQUIRED') {
      window.location.replace('/change-password')
      throw new Error('请先修改临时密码。')
    }
  }
  return response
}

export async function currentAccount(): Promise<Account> {
  const response = await sessionFetch('/api/auth/me')
  if (!response.ok) throw new Error('暂时无法验证登录状态，请检查后端后重试。')
  return response.json() as Promise<Account>
}

export async function signOut() {
  const response = await fetch('/api/auth/logout', {
    method: 'POST', headers: { Authorization: `Bearer ${readToken()}` },
  })
  if (!response.ok && response.status !== 401) throw new Error('退出失败，请稍后重试。')
  clearSession()
  window.location.replace('/login')
}

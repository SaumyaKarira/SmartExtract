const BASE_URL = 'http://localhost:8080';

function getToken(): string | null {
  return localStorage.getItem('se_token');
}

/**
 * Dispatched when a 401 is received on a protected API call.
 * AuthContext listens for this to clear session and redirect to login.
 */
export const SESSION_EXPIRED_EVENT = 'se:sessionExpired';

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = {
    ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(options.headers as Record<string, string> | undefined),
  };

  let res: Response;
  try {
    res = await fetch(`${BASE_URL}${path}`, { ...options, headers });
  } catch {
    throw new Error("We couldn't connect to the server. Please try again.");
  }

  if (!res.ok) {
    let message = `Request failed: ${res.status}`;
    try {
      const body = await res.json();
      message = body.message ?? body.error ?? message;
    } catch {
      // ignore parse errors
    }

    if (res.status === 401) {
      // Only fire session-expired for protected routes (i.e. when a token was sent)
      if (token && !path.startsWith('/api/auth/')) {
        window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
      }
      throw new Error(message);
    }
    if (res.status >= 500) {
      throw new Error("We couldn't connect to the server. Please try again.");
    }
    throw new Error(message);
  }

  const text = await res.text();
  return text ? (JSON.parse(text) as T) : (undefined as unknown as T);
}

export const api = {
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }),

  postForm: <T>(path: string, formData: FormData) =>
    request<T>(path, { method: 'POST', body: formData }),

  get: <T>(path: string) => request<T>(path, { method: 'GET' }),
};

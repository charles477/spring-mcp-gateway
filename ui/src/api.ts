// Thin client for the gateway's APIs. The bearer token lives in module state only —
// never localStorage — so a page reload requires a fresh login by design.

let token: string | null = null;

export interface Actor {
  subject: string;
  username: string;
  tenantId: string;
  roles: string[];
}

export function setToken(t: string) {
  token = t;
}

export async function login(username: string, password: string): Promise<Actor> {
  const res = await fetch("/realms/mcp-gateway/protocol/openid-connect/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "password",
      client_id: "mcp-gateway-client",
      username,
      password,
    }),
  });
  if (!res.ok) throw new Error("Sign-in failed — check username and password");
  const data = await res.json();
  setToken(data.access_token);
  return api<Actor>("/api/me");
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: {
      ...(init?.headers ?? {}),
      Authorization: `Bearer ${token}`,
      ...(init?.body ? { "Content-Type": "application/json" } : {}),
    },
  });
  if (res.status === 403) throw new Error("Your role does not allow this action");
  if (!res.ok) throw new Error(`Request failed (${res.status})`);
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

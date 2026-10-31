export type Row = Record<string, any>;
let token = "";
let refresh: Promise<Row> | null = null;
export function setToken(value: string) {
  token = value;
}
export async function restore(): Promise<Row> {
  if (!refresh)
    refresh = fetch("/api/auth/refresh", {
      method: "POST",
      headers: { "X-Stockflow-CSRF": "stockflow" },
      credentials: "same-origin",
    })
      .then(async (r) => {
        if (!r.ok) throw new Error("Please sign in");
        const data = await r.json();
        token = data.accessToken;
        return data;
      })
      .finally(() => {
        refresh = null;
      });
  return refresh;
}
export async function api(
  path: string,
  method = "GET",
  body?: unknown,
  headers: Record<string, string> = {},
  retry = true,
): Promise<any> {
  const r = await fetch("/api" + path, {
    method,
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      "X-Stockflow-CSRF": "stockflow",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (r.status === 401 && retry && !path.startsWith("/auth/")) {
    try {
      await restore();
      return api(path, method, body, headers, false);
    } catch {
      window.dispatchEvent(new Event("session-expired"));
      throw new Error("Your session expired. Please sign in again.");
    }
  }
  const data = await r
    .json()
    .catch(() => ({ error: `Request failed (${r.status})` }));
  if (!r.ok)
    throw new Error(
      data.error || data.detail || `Request failed (${r.status})`,
    );
  return data;
}
export const money = (n: number | string) =>
  new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 2,
  }).format(Number(n));
export const short = (id: string) =>
  id?.startsWith("WH-") || id?.startsWith("SKU-")
    ? id
    : id?.slice(0, 8).toUpperCase();
export const label = (s: string) =>
  s
    ?.toLowerCase()
    .replaceAll("_", " ")
    .replace(/^./, (c) => c.toUpperCase());

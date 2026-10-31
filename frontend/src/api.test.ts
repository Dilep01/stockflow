import { describe, it, expect, vi, afterEach } from "vitest";
import { api, setToken, money, label, short } from "./api";
afterEach(() => vi.unstubAllGlobals());
describe("API boundary", () => {
  it("keeps authorization out of request bodies and sends the key", async () => {
    const f = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ id: "one" }), { status: 202 }),
      );
    vi.stubGlobal("fetch", f);
    setToken("signed");
    await api(
      "/orders",
      "POST",
      { items: [] },
      { "Idempotency-Key": "retry-123" },
    );
    const [, options] = f.mock.calls[0];
    expect(options.headers.Authorization).toBe("Bearer signed");
    expect(options.headers["Idempotency-Key"]).toBe("retry-123");
    expect(options.body).toBe('{"items":[]}');
  });
  it("surfaces a server conflict without retrying a mutation blindly", async () => {
    const f = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ error: "Insufficient stock" }), {
          status: 409,
        }),
      );
    vi.stubGlobal("fetch", f);
    await expect(api("/orders", "POST", {})).rejects.toThrow(
      "Insufficient stock",
    );
    expect(f).toHaveBeenCalledTimes(1);
  });
  it("handles a non-JSON upstream error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("gateway down", { status: 502 })),
    );
    await expect(api("/orders")).rejects.toThrow("502");
  });
  it("formats business labels without changing quantities", () => {
    expect(label("PARTIALLY_SHIPPED")).toBe("Partially shipped");
    expect(short("WH-BLR")).toBe("WH-BLR");
    expect(money("3499.00")).toContain("3,499");
  });
});

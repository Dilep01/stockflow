import fs from "node:fs";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
const base = process.env.BASE_URL || "http://127.0.0.1:8089";
const env = Object.fromEntries(
  fs
    .readFileSync(".env", "utf8")
    .split(/\r?\n/)
    .filter((l) => l.includes("=") && !l.startsWith("#"))
    .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);
const results = [];
let admin, customer;
async function request(path, method = "GET", body, token = admin, key) {
  const response = await fetch(base + "/api" + path, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...(key ? { "Idempotency-Key": key } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok)
    throw new Error(
      `${method} ${path}: ${response.status} ${JSON.stringify(data)}`,
    );
  return data;
}
async function waitFor(fn, predicate, timeout = 120000) {
  const start = Date.now();
  let result;
  while (Date.now() - start < timeout) {
    result = await fn();
    if (predicate(result)) return result;
    await new Promise((r) => setTimeout(r, 400));
  }
  throw new Error("Timed out: " + JSON.stringify(result));
}
async function check(name, fn) {
  const start = Date.now();
  await fn();
  results.push({ name, passed: true, durationMs: Date.now() - start });
  console.log("PASS", name);
}
function docker(...args) {
  execFileSync(
    process.platform === "win32" ? "docker.exe" : "docker",
    ["compose", "--profile", "distributed", ...args],
    { stdio: ["ignore", "pipe", "pipe"] },
  );
}
const checkout = (sku, q, key = crypto.randomUUID()) =>
  request(
    "/orders",
    "POST",
    { address: "Synthetic test address", items: [{ skuId: sku, quantity: q }] },
    customer,
    key,
  );
try {
  admin = (
    await request(
      "/auth/login",
      "POST",
      { email: "admin@stockflow.local", password: env.DEMO_PASSWORD },
      null,
    )
  ).accessToken;
  customer = (
    await request(
      "/auth/login",
      "POST",
      { email: "customer@stockflow.local", password: env.DEMO_PASSWORD },
      null,
    )
  ).accessToken;
  const product = await request("/catalog/products", "POST", {
    sku: "TEST-" + Date.now(),
    name: "Verification fixture",
    description: "Synthetic test product",
    price: 100,
    category: "Test",
  });
  await request("/inventory/adjustments", "POST", {
    warehouseId: "WH-BLR",
    skuId: product.id,
    quantity: 10,
    reason: "Concurrency fixture",
    operationId: crypto.randomUUID(),
  });
  let accepted;
  await check("100 competing checkouts allocate exactly 10 units", async () => {
    const latencies = [];
    const start = Date.now();
    const requests = await Promise.all(
      Array.from({ length: 100 }, async () => {
        const t = performance.now();
        const r = await checkout(product.id, 1);
        latencies.push(performance.now() - t);
        return r;
      }),
    );
    const ids = new Set(requests.map((r) => r.id));
    let latest = await waitFor(
      () => request("/orders", "GET", undefined, customer),
      (rows) =>
        rows.filter(
          (r) => ids.has(r.id) && ["FULFILLING", "REJECTED"].includes(r.state),
        ).length === 100,
    );
    accepted = latest.filter((r) => ids.has(r.id) && r.state === "FULFILLING");
    assert.equal(accepted.length, 10);
    assert.equal(
      latest.filter((r) => ids.has(r.id) && r.state === "REJECTED").length,
      90,
    );
    latencies.sort((a, b) => a - b);
    results.push({
      name: "checkout measurements",
      requests: 100,
      concurrency: 100,
      p50Ms: Math.round(latencies[49]),
      p95Ms: Math.round(latencies[94]),
      p99Ms: Math.round(latencies[98]),
      completionMs: Date.now() - start,
    });
    const b = (await request("/inventory/balances")).find(
      (b) => b.sku_id === product.id,
    );
    assert.equal(b.reserved, 10);
    assert.equal(b.available, 0);
  });
  await check(
    "cancellation releases stock and duplicate checkout returns the same order",
    async () => {
      await request(
        "/orders/" + accepted[0].id + "/cancel",
        "POST",
        {},
        customer,
      );
      await waitFor(
        () => request("/orders/" + accepted[0].id, "GET", undefined, customer),
        (o) => o.state === "CANCELLED",
      );
      const key = crypto.randomUUID();
      const a = await checkout(product.id, 1, key),
        b = await checkout(product.id, 1, key);
      assert.equal(a.id, b.id);
      await waitFor(
        () => request("/orders/" + a.id, "GET", undefined, customer),
        (o) => o.state === "FULFILLING",
      );
    },
  );
  await check(
    "dispatch, delivery, return inspection, and restock converge",
    async () => {
      const order = accepted[1];
      const sh = (await request("/fulfillment/shipments")).find(
        (s) => s.order_id === order.id,
      );
      for (const action of ["pick", "pack", "dispatch"])
        await request(`/fulfillment/shipments/${sh.id}/actions`, "POST", {
          action,
        });
      await waitFor(
        () => request("/fulfillment/shipments"),
        (rows) => rows.find((s) => s.id === sh.id).state === "DISPATCHED",
      );
      for (const action of ["transit", "deliver"])
        await request(`/fulfillment/shipments/${sh.id}/actions`, "POST", {
          action,
        });
      const ret = await request(
        "/fulfillment/returns",
        "POST",
        {
          shipmentId: sh.id,
          skuId: product.id,
          quantity: 1,
          reason: "Synthetic return",
          requestId: crypto.randomUUID(),
        },
        customer,
      );
      for (const action of ["approve", "receive", "inspect"])
        await request(`/fulfillment/returns/${ret.id}/actions`, "POST", {
          action,
          disposition: "SELLABLE",
        });
      await waitFor(
        () => request("/fulfillment/returns"),
        (rows) => rows.find((r) => r.id === ret.id).state === "CLOSED",
      );
      assert.equal((await request("/inventory/reconciliation")).length, 0);
    },
  );
  await check("transfer receipt is idempotent", async () => {
    const id = crypto.randomUUID();
    await request("/inventory/transfers", "POST", {
      source: "WH-BLR",
      destination: "WH-PUN",
      skuId: product.id,
      quantity: 1,
      operationId: id,
    });
    await request(`/inventory/transfers/${id}/receive`, "POST", {});
    await request(`/inventory/transfers/${id}/receive`, "POST", {});
    const b = (await request("/inventory/balances")).find(
      (b) => b.sku_id === product.id && b.warehouse_id === "WH-PUN",
    );
    assert.equal(b.on_hand, 1);
  });
  if (process.env.RECOVERY === "true") {
    await check("Redis outage falls back to PostgreSQL", async () => {
      docker("stop", "redis");
      try {
        const p = await request("/catalog/products");
        assert(p.some((p) => p.id === product.id));
      } finally {
        docker("start", "redis");
      }
    });
    await check("inventory service restart recovers queued order", async () => {
      docker("stop", "inventory");
      let o;
      try {
        o = await checkout(product.id, 1);
        await new Promise((r) => setTimeout(r, 1500));
        assert.equal(
          (await request("/orders/" + o.id, "GET", undefined, customer)).state,
          "PENDING_STOCK",
        );
      } finally {
        docker("start", "inventory");
      }
      await waitFor(
        () => request("/orders/" + o.id, "GET", undefined, customer),
        (o) => o.state === "FULFILLING",
      );
    });
    await check(
      "Kafka outage preserves order in outbox and resumes",
      async () => {
        await request("/inventory/adjustments", "POST", {
          warehouseId: "WH-BLR",
          skuId: product.id,
          quantity: 1,
          reason: "Recovery fixture",
          operationId: crypto.randomUUID(),
        });
        docker("stop", "kafka");
        let o;
        try {
          o = await checkout(product.id, 1);
          await new Promise((r) => setTimeout(r, 4000));
          assert.equal(
            (await request("/orders/" + o.id, "GET", undefined, customer))
              .state,
            "PENDING_STOCK",
          );
        } finally {
          docker("start", "kafka");
        }
        await waitFor(
          () => request("/orders/" + o.id, "GET", undefined, customer),
          (o) => o.state === "FULFILLING",
        );
      },
    );
  }
  await check("inventory ledger reconciles after all workflows", async () =>
    assert.equal((await request("/inventory/reconciliation")).length, 0),
  );
} catch (error) {
  results.push({ passed: false, error: error.message });
  console.error(error);
  process.exitCode = 1;
} finally {
  fs.mkdirSync("artifacts", { recursive: true });
  fs.writeFileSync(
    "artifacts/system-results.json",
    JSON.stringify({ base, at: new Date().toISOString(), results }, null, 2),
  );
}

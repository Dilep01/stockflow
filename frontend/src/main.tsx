import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  Boxes,
  LayoutDashboard,
  Package,
  ShoppingBag,
  Truck,
  RotateCcw,
  ArrowLeftRight,
  Activity,
  LogOut,
  Search,
  Plus,
  ArrowUpRight,
  ChevronRight,
  X,
  Warehouse,
  Check,
  RefreshCw,
  ShoppingCart,
  ShieldCheck,
  SlidersHorizontal,
  ClipboardList,
} from "lucide-react";
import { api, restore, setToken, money, short, label, type Row } from "./api";
import "./style.css";

const client = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchInterval: 5000, refetchOnWindowFocus: true },
  },
});
type Field = {
  name: string;
  label: string;
  type?: string;
  value?: string | number;
  options?: { value: string; label: string }[];
  min?: number;
  max?: number;
};
type Dialog = {
  title: string;
  subtitle?: string;
  fields?: Field[];
  submit?: (values: Row) => Promise<unknown>;
  content?: React.ReactNode;
  button?: string;
};
function Badge({ state }: { state: string }) {
  return (
    <span
      className={`badge ${["REJECTED", "CANCELLED", "EXPIRED", "DAMAGED"].includes(state) ? "red" : ["PENDING_STOCK", "DISPATCH_PENDING", "CANCELLATION_PENDING", "RESTOCK_PENDING", "IN_TRANSIT", "REQUESTED"].includes(state) ? "amber" : "green"}`}
    >
      {label(state)}
    </span>
  );
}
function Empty({
  text = "Nothing here yet. Your activity will appear here.",
}: {
  text?: string;
}) {
  return (
    <div className="empty">
      <Package size={30} />
      <p>{text}</p>
    </div>
  );
}
function DialogView({ dialog, close }: { dialog: Dialog; close: () => void }) {
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    const fn = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !busy) close();
    };
    window.addEventListener("keydown", fn);
    return () => window.removeEventListener("keydown", fn);
  }, [busy, close]);
  return (
    <div
      className="overlay"
      onClick={(e) => {
        if (e.target === e.currentTarget && !busy) close();
      }}
    >
      <section
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={dialog.title}
      >
        <div className="dialog-title">
          <div>
            <h2>{dialog.title}</h2>
            {dialog.subtitle && <p>{dialog.subtitle}</p>}
          </div>
          <button
            className="icon-button"
            onClick={close}
            aria-label="Close dialog"
          >
            <X />
          </button>
        </div>
        {dialog.content}
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            setError("");
            setBusy(true);
            try {
              const values = Object.fromEntries(new FormData(e.currentTarget));
              for (const f of dialog.fields || [])
                if (f.type === "number")
                  values[f.name] = Number(values[f.name]) as any;
              await dialog.submit?.(values);
              close();
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          {dialog.fields?.map((f, i) => (
            <label className="field" key={f.name}>
              {f.label}
              {f.options ? (
                <select name={f.name} defaultValue={f.value} required>
                  {f.options.map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              ) : f.type === "textarea" ? (
                <textarea
                  name={f.name}
                  defaultValue={f.value}
                  required
                  maxLength={500}
                />
              ) : (
                <input
                  autoFocus={i === 0}
                  name={f.name}
                  type={f.type || "text"}
                  defaultValue={f.value}
                  min={f.min}
                  max={f.max}
                  step={f.type === "number" ? "any" : undefined}
                  required
                  maxLength={f.name === "password" ? 72 : 300}
                />
              )}
            </label>
          ))}
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          {dialog.submit && (
            <div className="dialog-actions">
              <button type="button" className="secondary" onClick={close}>
                Cancel
              </button>
              <button disabled={busy}>
                {busy ? "Saving…" : dialog.button || "Save changes"}
              </button>
            </div>
          )}
        </form>
      </section>
    </div>
  );
}
function Login({ onLogin }: { onLogin: (u: Row) => void }) {
  const [register, setRegister] = useState(false),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <div className="login">
      <section className="login-story">
        <div className="brand">
          <span className="logo">
            <Boxes />
          </span>
          stockflow<span className="brand-dot">.</span>
        </div>
        <div>
          <p className="eyebrow">INVENTORY, IN ORDER.</p>
          <h1>
            Every warehouse.
            <br />
            One clear picture.
          </h1>
          <p>
            From the first reservation to the final delivery, keep your stock
            and orders moving together.
          </p>
          <div className="login-warehouses">
            {["Bengaluru", "Hyderabad", "Pune"].map((city, i) => (
              <div key={city}>
                <Warehouse />
                <span>{city}</span>
                <span className="connection">Connected</span>
                <span className="route-line" />
              </div>
            ))}
          </div>
        </div>
        <small>Local demo · Synthetic inventory · No real payments</small>
      </section>
      <section className="login-form">
        <div>
          <span className="eyebrow">WELCOME TO YOUR WORKSPACE</span>
          <h2>{register ? "Create your account" : "Good to have you back."}</h2>
          <p>
            {register
              ? "Create a customer account to place demo orders."
              : "Sign in to manage inventory or track your orders."}
          </p>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              setBusy(true);
              setError("");
              const data = Object.fromEntries(new FormData(e.currentTarget));
              try {
                const result = await api(
                  register ? "/auth/register" : "/auth/login",
                  "POST",
                  data,
                );
                setToken(result.accessToken);
                onLogin(result.user);
              } catch (e) {
                setError((e as Error).message);
              } finally {
                setBusy(false);
              }
            }}
          >
            {register && (
              <label className="field">
                Your name
                <input name="name" required minLength={2} />
              </label>
            )}
            <label className="field">
              Email address
              <input
                name="email"
                type="email"
                defaultValue={register ? "" : "admin@stockflow.local"}
                required
                autoComplete="username"
              />
            </label>
            <label className="field">
              Password
              <input
                name="password"
                type="password"
                required
                minLength={register ? 10 : 1}
                autoComplete={register ? "new-password" : "current-password"}
              />
            </label>
            {error && (
              <p className="error" role="alert">
                {error}
              </p>
            )}
            <button className="wide" disabled={busy}>
              {busy ? "Please wait…" : register ? "Create account" : "Sign in"}
              <ArrowUpRight size={18} />
            </button>
          </form>
          <button
            className="text-button"
            onClick={() => {
              setRegister(!register);
              setError("");
            }}
          >
            {register
              ? "Already have an account? Sign in"
              : "New customer? Create an account"}
          </button>
          <div className="demo-note">
            <ShieldCheck size={19} />
            <div>
              <strong>Explore the local demo</strong>
              <p>
                Use admin, operator, or customer @stockflow.local. Your local
                setup password is in the project’s .env file.
              </p>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
function Workspace({ user, onLogout }: { user: Row; onLogout: () => void }) {
  const qc = useQueryClient(),
    [page, setPage] = useState(
      user.role === "CUSTOMER" ? "Catalog" : "Overview",
    ),
    [search, setSearch] = useState(""),
    [warehouseFilter, setWarehouseFilter] = useState("all"),
    [dialog, setDialog] = useState<Dialog | null>(null),
    [cart, setCart] = useState<Record<string, number>>({}),
    [notice, setNotice] = useState(""),
    [checkoutKey, setCheckoutKey] = useState(crypto.randomUUID());
  const staff = user.role !== "CUSTOMER",
    admin = user.role === "ADMIN";
  const products = useQuery<Row[]>({
    queryKey: ["/catalog/products"],
    queryFn: () => api("/catalog/products"),
  });
  const balances = useQuery<Row[]>({
    queryKey: ["/inventory/balances"],
    queryFn: () => api("/inventory/balances"),
  });
  const warehouses = useQuery<Row[]>({
    queryKey: ["/inventory/warehouses"],
    queryFn: () => api("/inventory/warehouses"),
  });
  const orders = useQuery<Row[]>({
    queryKey: ["/orders"],
    queryFn: () => api("/orders"),
    enabled: user.role !== "OPERATOR",
  });
  const shipments = useQuery<Row[]>({
    queryKey: ["/fulfillment/shipments"],
    queryFn: () => api("/fulfillment/shipments"),
  });
  const returns = useQuery<Row[]>({
    queryKey: ["/fulfillment/returns"],
    queryFn: () => api("/fulfillment/returns"),
  });
  const transfers = useQuery<Row[]>({
    queryKey: ["/inventory/transfers"],
    queryFn: () => api("/inventory/transfers"),
    enabled: staff,
  });
  const movements = useQuery<Row[]>({
    queryKey: ["/inventory/movements"],
    queryFn: () => api("/inventory/movements"),
    enabled: staff,
  });
  const health = useQuery<Row>({
    queryKey: ["/ops/inventory/status"],
    queryFn: () => api("/ops/inventory/status"),
    enabled: admin,
  });
  const reconciliation = useQuery<Row[]>({
    queryKey: ["/inventory/reconciliation"],
    queryFn: () => api("/inventory/reconciliation"),
    enabled: admin,
  });
  const users = useQuery<Row[]>({
    queryKey: ["/auth/users"],
    queryFn: () => api("/auth/users"),
    enabled: admin && page === "System",
  });
  const ps = products.data || [],
    bs = balances.data || [],
    ws = warehouses.data || [],
    os = orders.data || [],
    ss = shipments.data || [],
    rs = returns.data || [],
    ts = transfers.data || [],
    ms = movements.data || [];
  const pname = (id: string) => ps.find((p) => p.id === id)?.name || id,
    wname = (id: string) => ws.find((w) => w.id === id)?.name || id;
  const sum = (key: string) => bs.reduce((a, b) => a + Number(b[key]), 0);
  const notify = (text: string) => {
    setNotice(text);
    setTimeout(() => setNotice(""), 5000);
  };
  const mutate = async (path: string, method = "POST", body?: unknown) => {
    const result = await api(path, method, body);
    await qc.invalidateQueries();
    notify("Changes saved");
    return result;
  };
  const run = (path: string, body?: unknown) =>
    mutate(path, "POST", body).catch((e) => notify(e.message));
  const go = (name: string) => {
    setPage(name);
    setSearch("");
  };
  const productOptions = ps.map((p) => ({ value: p.id, label: p.name })),
    warehouseOptions = ws
      .filter((w) => admin || w.id === user.warehouse)
      .map((w) => ({ value: w.id, label: w.name }));
  const matches = (r: Row) =>
    JSON.stringify(r).toLowerCase().includes(search.toLowerCase());
  const stockRows = bs.filter(
    (b) =>
      (warehouseFilter === "all" || b.warehouse_id === warehouseFilter) &&
      matches({ ...b, name: pname(b.sku_id) }),
  );
  const openAdjustment = () => {
    const operationId = crypto.randomUUID();
    setDialog({
      title: "Adjust stock",
      subtitle:
        "Positive quantities receive stock. Negative quantities remove available stock.",
      fields: [
        { name: "warehouseId", label: "Warehouse", options: warehouseOptions },
        { name: "skuId", label: "Product", options: productOptions },
        {
          name: "quantity",
          label: "Quantity change",
          type: "number",
          value: 1,
          min: -100000,
          max: 100000,
        },
        { name: "reason", label: "Reason", value: "Stock receipt" },
      ],
      submit: (v) =>
        mutate("/inventory/adjustments", "POST", { ...v, operationId }),
    });
  };
  const openProduct = (p?: Row) =>
    setDialog({
      title: p ? "Edit product" : "Add product",
      fields: [
        { name: "sku", label: "SKU", value: p?.sku },
        { name: "name", label: "Product name", value: p?.name },
        { name: "description", label: "Description", value: p?.description },
        {
          name: "price",
          label: "Unit price (INR)",
          type: "number",
          value: p?.price,
          min: 0.01,
        },
        {
          name: "category",
          label: "Category",
          value: p?.category || "Accessories",
        },
      ],
      submit: (v) =>
        mutate(
          "/catalog/products" + (p ? "/" + p.id : ""),
          p ? "PUT" : "POST",
          v,
        ),
    });
  const openTransfer = () => {
    const operationId = crypto.randomUUID();
    setDialog({
      title: "Transfer stock",
      subtitle: "Units remain in transit until the destination receives them.",
      fields: [
        { name: "source", label: "From warehouse", options: warehouseOptions },
        {
          name: "destination",
          label: "To warehouse",
          options: ws.map((w) => ({ value: w.id, label: w.name })),
        },
        { name: "skuId", label: "Product", options: productOptions },
        {
          name: "quantity",
          label: "Units",
          type: "number",
          value: 1,
          min: 1,
          max: 100000,
        },
      ],
      submit: (v) =>
        mutate("/inventory/transfers", "POST", { ...v, operationId }),
    });
  };
  const showOrder = async (id: string) => {
    try {
      const o = await api("/orders/" + id);
      setDialog({
        title: "Order " + short(id),
        subtitle: o.address,
        content: (
          <>
            <Badge state={o.state} />
            <div className="detail-list">
              {o.items.map((i: Row) => (
                <div key={i.sku_id}>
                  <span>
                    {i.name} × {i.quantity}
                  </span>
                  <strong>{money(i.price * i.quantity)}</strong>
                </div>
              ))}
            </div>
            <div className="total">
              <span>Total</span>
              <strong>{money(o.total)}</strong>
            </div>
            <h3>Order timeline</h3>
            <div className="timeline">
              {o.history.map((h: Row, i: number) => (
                <div key={i}>
                  <span className="timeline-dot" />
                  <div>
                    <strong>{label(h.state)}</strong>
                    <p>{h.note}</p>
                    <small>{new Date(h.created_at).toLocaleString()}</small>
                  </div>
                </div>
              ))}
            </div>
          </>
        ),
      });
    } catch (e) {
      notify((e as Error).message);
    }
  };
  const checkout = () =>
    setDialog({
      title: "Review your order",
      subtitle: "Demo checkout. No payment is collected.",
      content: (
        <div className="detail-list">
          {Object.entries(cart).map(([id, q]) => (
            <div key={id}>
              <span>
                {pname(id)} × {q}
              </span>
              <strong>
                {money((ps.find((p) => p.id === id)?.price || 0) * q)}
              </strong>
            </div>
          ))}
        </div>
      ),
      fields: [
        { name: "address", label: "Delivery address", type: "textarea" },
      ],
      button: "Place order",
      submit: async (v) => {
        await api(
          "/orders",
          "POST",
          {
            address: v.address,
            items: Object.entries(cart).map(([skuId, quantity]) => ({
              skuId,
              quantity,
            })),
          },
          { "Idempotency-Key": checkoutKey },
        );
        setCart({});
        setCheckoutKey(crypto.randomUUID());
        await qc.invalidateQueries();
        go("Orders");
        notify("Order submitted. Inventory allocation is in progress.");
      },
    });
  const requestReturn = (sh: Row) => {
    const requestId = crypto.randomUUID();
    setDialog({
      title: "Request a return",
      subtitle: `Shipment ${sh.tracking}`,
      fields: [
        {
          name: "skuId",
          label: "Item",
          options: sh.items.map((i: Row) => ({
            value: i.sku_id,
            label: pname(i.sku_id),
          })),
        },
        {
          name: "quantity",
          label: "Quantity",
          type: "number",
          value: 1,
          min: 1,
        },
        { name: "reason", label: "Reason", type: "textarea" },
      ],
      submit: (v) =>
        mutate("/fulfillment/returns", "POST", {
          ...v,
          shipmentId: sh.id,
          requestId,
        }),
    });
  };
  const menu = [
    { name: "Overview", icon: LayoutDashboard, show: staff },
    { name: "Inventory", icon: Boxes, show: staff },
    { name: "Catalog", icon: Package, show: true },
    { name: "Orders", icon: ShoppingBag, show: user.role !== "OPERATOR" },
    { name: "Shipments", icon: Truck, show: true },
    { name: "Returns", icon: RotateCcw, show: true },
    { name: "Transfers", icon: ArrowLeftRight, show: staff },
    { name: "Activity", icon: ClipboardList, show: staff },
    { name: "System", icon: Activity, show: admin },
  ].filter((x) => x.show);
  const subtitles: Record<string, string> = {
    Overview: "A clear view of your stock, orders, and warehouse operations.",
    Inventory: "Track available and reserved stock across your warehouses.",
    Catalog: "Browse products and keep your catalog up to date.",
    Orders: "Follow every order from reservation to delivery.",
    Shipments: "Move allocated stock through picking, packing, and dispatch.",
    Returns: "Receive, inspect, and reconcile returned items.",
    Transfers: "Move stock between warehouses without losing track.",
    Activity: "An auditable record of every stock movement.",
    System: "Inspect event delivery and inventory consistency.",
  };
  const queryError = [
    products,
    balances,
    warehouses,
    orders,
    shipments,
    returns,
    transfers,
    movements,
    health,
    reconciliation,
    users,
  ].find((q) => q.error)?.error;
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <span className="logo">
            <Boxes size={23} />
          </span>
          stockflow<span className="brand-dot">.</span>
        </div>
        <div className="workspace">
          <span className="workspace-symbol">S</span>
          <div>
            Demo workspace<small>Inventory & orders</small>
          </div>
          <ChevronRight size={15} />
        </div>
        <p className="nav-label">WORKSPACE</p>
        <nav>
          {menu.map(({ name, icon: Icon }) => (
            <button
              key={name}
              aria-label={name}
              className={page === name ? "active" : ""}
              onClick={() => go(name)}
            >
              <Icon size={19} />
              <span>{name}</span>
              {name === "Orders" && os.length > 0 && (
                <span className="nav-count">{os.length}</span>
              )}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="local-label">
            <span />
            Local environment
          </div>
          <div className="profile">
            <span className="avatar">{user.name[0]}</span>
            <div>
              {user.name}
              <small>{label(user.role)}</small>
            </div>
            <button onClick={onLogout} aria-label="Sign out">
              <LogOut size={17} />
            </button>
          </div>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="breadcrumb">
            Workspace <ChevronRight size={14} />
            <strong>{page}</strong>
          </div>
          <div className="topbar-right">
            <span className="demo-pill">DEMO WORKSPACE</span>
            <span className="avatar small">{user.name[0]}</span>
          </div>
        </header>
        <main>
          <div className="page-heading">
            <div>
              <p className="eyebrow">
                {page === "Overview"
                  ? "YOUR OPERATIONS, AT A GLANCE"
                  : "WORKSPACE / " + page.toUpperCase()}
              </p>
              <h1>{page === "Overview" ? "Operations overview" : page}</h1>
              <p>{subtitles[page]}</p>
            </div>
            <div className="heading-actions">
              <button
                className="secondary icon-button"
                aria-label="Refresh data"
                onClick={() => qc.invalidateQueries()}
              >
                <RefreshCw size={17} />
              </button>
              {page === "Inventory" && (
                <button
                  onClick={openAdjustment}
                  disabled={!ps.length || !ws.length}
                >
                  <Plus size={17} />
                  Adjust stock
                </button>
              )}
              {page === "Catalog" && admin && (
                <button onClick={() => openProduct()}>
                  <Plus size={17} />
                  Add product
                </button>
              )}
              {page === "Transfers" && (
                <button
                  onClick={openTransfer}
                  disabled={!ps.length || !ws.length}
                >
                  <Plus size={17} />
                  New transfer
                </button>
              )}
              {page === "Overview" && (
                <button onClick={() => go("Inventory")}>
                  View inventory
                  <ArrowUpRight size={17} />
                </button>
              )}
            </div>
          </div>
          {queryError && (
            <div className="error" role="alert">
              {queryError.message}{" "}
              <button
                className="text-button"
                onClick={() => qc.invalidateQueries()}
              >
                Retry
              </button>
            </div>
          )}
          {products.isLoading && (
            <p className="loading">Loading your workspace…</p>
          )}
          {page === "Overview" && (
            <>
              <div className="stats">
                {[
                  {
                    label: "Available units",
                    value: sum("available"),
                    icon: Boxes,
                    note: "Ready for allocation",
                  },
                  {
                    label: "Reserved units",
                    value: sum("reserved"),
                    icon: ShoppingBag,
                    note: "Held for customer orders",
                  },
                  {
                    label: "Open shipments",
                    value: ss.filter(
                      (s) => !["DELIVERED", "CANCELLED"].includes(s.state),
                    ).length,
                    icon: Truck,
                    note: "Across your warehouses",
                  },
                  {
                    label: "Active warehouses",
                    value: new Set(bs.map((b) => b.warehouse_id)).size,
                    icon: Warehouse,
                    note: "Connected to this workspace",
                  },
                ].map((c) => (
                  <section className="stat" key={c.label}>
                    <div>
                      <span>{c.label}</span>
                      <c.icon size={19} />
                    </div>
                    <strong>{c.value.toLocaleString()}</strong>
                    <small>{c.note}</small>
                  </section>
                ))}
              </div>
              <div className="overview-grid">
                <section className="panel">
                  <div className="panel-title">
                    <div>
                      <h2>Stock by warehouse</h2>
                      <p>Available and reserved units in each location</p>
                    </div>
                    <span className="legend">
                      <i />
                      Available <i className="held" />
                      Reserved
                    </span>
                  </div>
                  <div className="warehouse-bars">
                    {ws
                      .filter((w) => admin || w.id === user.warehouse)
                      .map((w) => {
                        const rows = bs.filter((b) => b.warehouse_id === w.id),
                          available = rows.reduce((a, b) => a + b.available, 0),
                          reserved = rows.reduce((a, b) => a + b.reserved, 0);
                        return (
                          <div className="warehouse-row" key={w.id}>
                            <div>
                              <span className="warehouse-icon">
                                <Warehouse size={21} />
                              </span>
                              <div>
                                <strong>{w.name}</strong>
                                <small>{w.city}</small>
                              </div>
                              <b>
                                {available + reserved}
                                <small>units on hand</small>
                              </b>
                            </div>
                            <div className="bar">
                              <span
                                style={{
                                  width: `${(available / Math.max(sum("on_hand") / 2, available + reserved, 1)) * 100}%`,
                                }}
                              />
                              <span
                                className="reserved"
                                style={{
                                  width: `${(reserved / Math.max(sum("on_hand") / 2, available + reserved, 1)) * 100}%`,
                                }}
                              />
                            </div>
                          </div>
                        );
                      })}
                  </div>
                </section>
                <section className="panel dispatch-panel">
                  <span className="eyebrow">FULFILLMENT DESK</span>
                  <div className="dispatch-icon">
                    <Truck size={40} />
                  </div>
                  <h2>Keep things moving.</h2>
                  <p>
                    {ss.filter((s) => s.state === "READY").length} shipments are
                    ready to pick. Review warehouse tasks and move each order
                    forward.
                  </p>
                  <button className="secondary" onClick={() => go("Shipments")}>
                    Open shipment queue
                    <ArrowUpRight size={17} />
                  </button>
                  <small>Shipping is simulated in this demo.</small>
                </section>
              </div>
              <section className="panel">
                <div className="panel-title">
                  <div>
                    <h2>Recent stock activity</h2>
                    <p>The latest movements across your network</p>
                  </div>
                  <button
                    className="text-button"
                    onClick={() => go("Activity")}
                  >
                    View all activity
                    <ChevronRight size={15} />
                  </button>
                </div>
                {ms.length ? (
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th>Product</th>
                          <th>Warehouse</th>
                          <th>Movement</th>
                          <th>Stock change</th>
                          <th>Time</th>
                        </tr>
                      </thead>
                      <tbody>
                        {ms.slice(0, 5).map((m) => (
                          <tr key={m.id}>
                            <td>
                              <strong>{pname(m.sku_id)}</strong>
                              <small>{m.sku_id}</small>
                            </td>
                            <td>{wname(m.warehouse_id)}</td>
                            <td>
                              <Badge state={m.kind} />
                            </td>
                            <td className="mono">
                              {m.on_hand_delta > 0 ? "+" : ""}
                              {m.on_hand_delta}
                            </td>
                            <td>
                              {new Date(m.created_at).toLocaleTimeString()}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <Empty />
                )}
              </section>
            </>
          )}
          {page === "Inventory" && (
            <section className="panel">
              <div className="filters">
                <div className="search">
                  <Search size={17} />
                  <input
                    aria-label="Search inventory"
                    placeholder="Search products or SKUs…"
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                  />
                </div>
                <select
                  aria-label="Filter warehouse"
                  value={warehouseFilter}
                  onChange={(e) => setWarehouseFilter(e.target.value)}
                >
                  <option value="all">All warehouses</option>
                  {ws.map((w) => (
                    <option key={w.id} value={w.id}>
                      {w.name}
                    </option>
                  ))}
                </select>
                <span className="muted">
                  {stockRows.length} stock positions
                </span>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Product / SKU</th>
                      <th>Warehouse</th>
                      <th>On hand</th>
                      <th>Reserved</th>
                      <th>Available</th>
                      <th>Damaged</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {stockRows.map((b) => (
                      <tr key={b.warehouse_id + b.sku_id}>
                        <td>
                          <strong>{pname(b.sku_id)}</strong>
                          <small>{b.sku_id}</small>
                        </td>
                        <td>{b.warehouse_name}</td>
                        <td>{b.on_hand}</td>
                        <td>{b.reserved}</td>
                        <td>
                          <strong className="teal">{b.available}</strong>
                        </td>
                        <td>{b.damaged}</td>
                        <td>
                          <Badge
                            state={
                              b.available === 0
                                ? "OUT_OF_STOCK"
                                : b.available < 5
                                  ? "LOW_STOCK"
                                  : "IN_STOCK"
                            }
                          />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!stockRows.length && <Empty />}
              <div className="table-footer">
                Available = on hand − reserved. Damaged units are held
                separately.
              </div>
            </section>
          )}
          {page === "Catalog" && (
            <>
              <div className="filters standalone">
                <div className="search">
                  <Search size={17} />
                  <input
                    aria-label="Search catalog"
                    placeholder="Find a product…"
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                  />
                </div>
                <span>{ps.filter(matches).length} products</span>
              </div>
              <div className="product-grid">
                {ps.filter(matches).map((p, i) => {
                  const available = bs
                    .filter((b) => b.sku_id === p.id)
                    .reduce((a, b) => a + b.available, 0);
                  return (
                    <article className="product-card" key={p.id}>
                      <div className={`product-art art-${i % 3}`}>
                        <Package size={54} strokeWidth={1} />
                        <span>{p.category}</span>
                      </div>
                      <div className="product-content">
                        <small>{p.sku}</small>
                        <h2>{p.name}</h2>
                        <p>{p.description}</p>
                        <div className="product-price">
                          <strong>{money(p.price)}</strong>
                          <span>{available} available</span>
                        </div>
                        <div className="product-actions">
                          {user.role !== "OPERATOR" && (
                            <button
                              className="secondary"
                              onClick={() => {
                                setCart({
                                  ...cart,
                                  [p.id]: (cart[p.id] || 0) + 1,
                                });
                                setCheckoutKey(crypto.randomUUID());
                              }}
                            >
                              <Plus size={16} />
                              Add to order
                            </button>
                          )}
                          {admin && (
                            <button
                              className="text-button"
                              onClick={() => openProduct(p)}
                            >
                              Edit
                            </button>
                          )}
                        </div>
                      </div>
                    </article>
                  );
                })}
              </div>
              {Object.keys(cart).length > 0 && (
                <section className="cart">
                  <div>
                    <ShoppingCart />
                    <strong>Your order</strong>
                    <span>
                      {Object.values(cart).reduce((a, b) => a + b, 0)} units
                    </span>
                  </div>
                  <div className="cart-lines">
                    {Object.entries(cart).map(([id, q]) => (
                      <label key={id}>
                        {pname(id)}
                        <input
                          aria-label={`Quantity for ${pname(id)}`}
                          type="number"
                          min="0"
                          max="10000"
                          value={q}
                          onChange={(e) => {
                            const n = Math.max(
                              0,
                              Math.floor(Number(e.target.value)),
                            );
                            const next = { ...cart, [id]: n };
                            if (n === 0) delete next[id];
                            setCart(next);
                            setCheckoutKey(crypto.randomUUID());
                          }}
                        />
                      </label>
                    ))}
                  </div>
                  <div>
                    <strong>
                      {money(
                        Object.entries(cart).reduce(
                          (a, [id, q]) =>
                            a + (ps.find((p) => p.id === id)?.price || 0) * q,
                          0,
                        ),
                      )}
                    </strong>
                    <button onClick={checkout}>
                      Review order
                      <ChevronRight size={16} />
                    </button>
                  </div>
                </section>
              )}
            </>
          )}
          {page === "Orders" && (
            <section className="panel">
              <div className="filters">
                <div className="search">
                  <Search size={17} />
                  <input
                    placeholder="Search orders…"
                    aria-label="Search orders"
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                  />
                </div>
                <span className="muted">{os.length} recent orders</span>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Order</th>
                      <th>Placed</th>
                      <th>Status</th>
                      <th>Total</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {os.filter(matches).map((o) => (
                      <tr key={o.id}>
                        <td>
                          <strong className="mono">#{short(o.id)}</strong>
                          <small>
                            {admin ? o.customer_email : "Your order"}
                          </small>
                        </td>
                        <td>{new Date(o.created_at).toLocaleString()}</td>
                        <td>
                          <Badge state={o.state} />
                        </td>
                        <td>{money(o.total)}</td>
                        <td className="actions">
                          <button
                            className="text-button"
                            onClick={() => showOrder(o.id)}
                          >
                            View details
                          </button>
                          {[
                            "PENDING_STOCK",
                            "CONFIRMED",
                            "FULFILLING",
                          ].includes(o.state) && (
                            <button
                              className="text-button danger"
                              onClick={() =>
                                setDialog({
                                  title: "Cancel order?",
                                  subtitle:
                                    "The warehouse will confirm whether dispatch can still be stopped.",
                                  button: "Request cancellation",
                                  submit: () =>
                                    mutate(`/orders/${o.id}/cancel`),
                                })
                              }
                            >
                              Cancel
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!os.length && (
                <Empty text="No orders yet. Browse the catalog to place your first order." />
              )}
            </section>
          )}
          {page === "Shipments" && (
            <div className="shipment-grid">
              {ss.map((sh) => (
                <section className="panel shipment" key={sh.id}>
                  <div className="panel-title">
                    <div>
                      <small className="eyebrow">
                        {wname(sh.warehouse_id)}
                      </small>
                      <h2>{sh.tracking}</h2>
                      <p>Order #{short(sh.order_id)}</p>
                    </div>
                    <Badge state={sh.state} />
                  </div>
                  <div className="shipment-items">
                    {sh.items.map((i: Row) => (
                      <div key={i.sku_id}>
                        <Package size={18} />
                        <span>{pname(i.sku_id)}</span>
                        <strong>× {i.quantity}</strong>
                      </div>
                    ))}
                  </div>
                  <div className="steps">
                    {[
                      "READY",
                      "PICKED",
                      "PACKED",
                      "DISPATCHED",
                      "IN_TRANSIT",
                      "DELIVERED",
                    ].map((st) => (
                      <span
                        key={st}
                        className={
                          sh.timeline.some((t: Row) => t.state === st)
                            ? "done"
                            : ""
                        }
                        title={label(st)}
                      >
                        <Check size={12} />
                        <small>{label(st)}</small>
                      </span>
                    ))}
                  </div>
                  <div className="shipment-footer">
                    <small>Simulated carrier</small>
                    {staff &&
                      (
                        {
                          READY: "pick",
                          PICKED: "pack",
                          PACKED: "dispatch",
                          DISPATCHED: "transit",
                          IN_TRANSIT: "deliver",
                        } as Row
                      )[sh.state] && (
                        <button
                          onClick={() =>
                            run(`/fulfillment/shipments/${sh.id}/actions`, {
                              action: (
                                {
                                  READY: "pick",
                                  PICKED: "pack",
                                  PACKED: "dispatch",
                                  DISPATCHED: "transit",
                                  IN_TRANSIT: "deliver",
                                } as Row
                              )[sh.state],
                            })
                          }
                        >
                          {
                            (
                              {
                                READY: "Mark picked",
                                PICKED: "Mark packed",
                                PACKED: "Dispatch",
                                DISPATCHED: "Mark in transit",
                                IN_TRANSIT: "Mark delivered",
                              } as Row
                            )[sh.state]
                          }
                          <ArrowUpRight size={16} />
                        </button>
                      )}
                    {sh.state === "DELIVERED" && user.role !== "OPERATOR" && (
                      <button
                        className="secondary"
                        onClick={() => requestReturn(sh)}
                      >
                        Request return
                      </button>
                    )}
                  </div>
                </section>
              ))}
              {!ss.length && (
                <Empty text="Shipments appear after an order’s inventory is confirmed." />
              )}
            </div>
          )}
          {page === "Returns" && (
            <section className="panel">
              <div className="panel-title">
                <div>
                  <h2>Return requests</h2>
                  <p>Refund amounts are simulated; no money moves.</p>
                </div>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Return / item</th>
                      <th>Quantity</th>
                      <th>Reason</th>
                      <th>Status</th>
                      <th>Refund (demo)</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rs.map((r) => (
                      <tr key={r.id}>
                        <td>
                          <strong>{pname(r.sku_id)}</strong>
                          <small>#{short(r.id)}</small>
                        </td>
                        <td>{r.quantity}</td>
                        <td>{r.reason}</td>
                        <td>
                          <Badge state={r.state} />
                        </td>
                        <td>{r.refund ? money(r.refund) : "—"}</td>
                        <td className="actions">
                          {staff && r.state === "REQUESTED" && (
                            <>
                              <button
                                className="text-button"
                                onClick={() =>
                                  run(`/fulfillment/returns/${r.id}/actions`, {
                                    action: "approve",
                                  })
                                }
                              >
                                Approve
                              </button>
                              <button
                                className="text-button danger"
                                onClick={() =>
                                  run(`/fulfillment/returns/${r.id}/actions`, {
                                    action: "reject",
                                  })
                                }
                              >
                                Reject
                              </button>
                            </>
                          )}
                          {staff && r.state === "APPROVED" && (
                            <button
                              className="text-button"
                              onClick={() =>
                                run(`/fulfillment/returns/${r.id}/actions`, {
                                  action: "receive",
                                })
                              }
                            >
                              Receive
                            </button>
                          )}
                          {staff && r.state === "RECEIVED" && (
                            <button
                              className="text-button"
                              onClick={() =>
                                setDialog({
                                  title: "Inspect returned item",
                                  fields: [
                                    {
                                      name: "disposition",
                                      label: "Condition",
                                      options: [
                                        {
                                          value: "SELLABLE",
                                          label:
                                            "Sellable — restore available stock",
                                        },
                                        {
                                          value: "DAMAGED",
                                          label: "Damaged — quarantine stock",
                                        },
                                      ],
                                    },
                                  ],
                                  submit: (v) =>
                                    mutate(
                                      `/fulfillment/returns/${r.id}/actions`,
                                      "POST",
                                      { action: "inspect", ...v },
                                    ),
                                })
                              }
                            >
                              Inspect
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!rs.length && (
                <Empty text="No return requests. Start a return from a delivered shipment." />
              )}
            </section>
          )}
          {page === "Transfers" && (
            <section className="panel">
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Transfer</th>
                      <th>Product</th>
                      <th>From → To</th>
                      <th>Units</th>
                      <th>Status</th>
                      <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {ts.map((t) => (
                      <tr key={t.id}>
                        <td className="mono">#{short(t.id)}</td>
                        <td>{pname(t.sku_id)}</td>
                        <td>
                          {wname(t.source)}
                          <br />
                          <small>→ {wname(t.destination)}</small>
                        </td>
                        <td>{t.quantity}</td>
                        <td>
                          <Badge state={t.state} />
                        </td>
                        <td>
                          {t.state === "IN_TRANSIT" &&
                            (admin || t.destination === user.warehouse) && (
                              <button
                                className="text-button"
                                onClick={() =>
                                  run(`/inventory/transfers/${t.id}/receive`)
                                }
                              >
                                Receive stock
                              </button>
                            )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!ts.length && (
                <Empty text="No transfers yet. Create a transfer to move available stock." />
              )}
            </section>
          )}
          {page === "Activity" && (
            <section className="panel">
              <div className="filters">
                <div className="search">
                  <Search size={17} />
                  <input
                    placeholder="Search movements…"
                    aria-label="Search movements"
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                  />
                </div>
                <span className="muted">Latest 200 movements</span>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Time</th>
                      <th>Product / warehouse</th>
                      <th>Type</th>
                      <th>On hand Δ</th>
                      <th>Reserved Δ</th>
                      <th>Reason / actor</th>
                    </tr>
                  </thead>
                  <tbody>
                    {ms.filter(matches).map((m) => (
                      <tr key={m.id}>
                        <td>{new Date(m.created_at).toLocaleString()}</td>
                        <td>
                          <strong>{pname(m.sku_id)}</strong>
                          <small>{wname(m.warehouse_id)}</small>
                        </td>
                        <td>
                          <Badge state={m.kind} />
                        </td>
                        <td>{m.on_hand_delta}</td>
                        <td>{m.reserved_delta}</td>
                        <td>
                          {m.reason}
                          <small>{m.actor}</small>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
          {page === "System" && (
            <>
              <div className="stats">
                <section className="stat">
                  <div>
                    Inventory event backlog
                    <Activity size={18} />
                  </div>
                  <strong>{health.data?.pending ?? "—"}</strong>
                  <small>Waiting for delivery</small>
                </section>
                <section className="stat">
                  <div>
                    Failed events
                    <RotateCcw size={18} />
                  </div>
                  <strong>{health.data?.dead ?? "—"}</strong>
                  <small>Require review and retry</small>
                </section>
                <section className="stat">
                  <div>
                    Ledger differences
                    <ShieldCheck size={18} />
                  </div>
                  <strong>{reconciliation.data?.length ?? "—"}</strong>
                  <small>Expected: zero</small>
                </section>
                <section className="stat">
                  <div>
                    Event transport
                    <Boxes size={18} />
                  </div>
                  <strong className="text-stat">
                    {health.data?.messaging || "—"}
                  </strong>
                  <small>
                    {health.data?.role === "all"
                      ? "Modular application"
                      : "Distributed services"}
                  </small>
                </section>
              </div>
              <section className="panel">
                <div className="panel-title">
                  <div>
                    <h2>Inventory event delivery</h2>
                    <p>
                      Durable events are retried automatically before requiring
                      review.
                    </p>
                  </div>
                </div>
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>Event</th>
                        <th>Target</th>
                        <th>Attempts</th>
                        <th>Delivery</th>
                        <th>Action</th>
                      </tr>
                    </thead>
                    <tbody>
                      {health.data?.events?.map((e: Row) => (
                        <tr key={e.id}>
                          <td>
                            <strong>{e.kind}</strong>
                            <small>{short(e.aggregate_id)}</small>
                          </td>
                          <td>{e.target}</td>
                          <td>{e.attempts}</td>
                          <td>
                            <Badge
                              state={
                                e.published_at
                                  ? "DELIVERED"
                                  : e.dead
                                    ? "REJECTED"
                                    : "PENDING_STOCK"
                              }
                            />
                          </td>
                          <td>
                            {e.dead && (
                              <button
                                className="text-button"
                                onClick={() =>
                                  run(`/ops/inventory/events/${e.id}/retry`)
                                }
                              >
                                Retry event
                              </button>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
              {admin && (
                <section className="panel warehouse-management">
                  <h2>Warehouse management</h2>
                  <p>Add a location, then receive stock through Inventory.</p>
                  <button
                    className="secondary"
                    onClick={() =>
                      setDialog({
                        title: "Add warehouse",
                        fields: [
                          { name: "name", label: "Warehouse name" },
                          { name: "city", label: "City" },
                          {
                            name: "priority",
                            label: "Allocation priority (lower first)",
                            type: "number",
                            value: 3,
                            min: 0,
                          },
                        ],
                        submit: (v) =>
                          mutate("/inventory/warehouses", "POST", v),
                      })
                    }
                  >
                    <Plus size={16} />
                    Add warehouse
                  </button>
                  <h3>People and warehouse access</h3>
                  <p>
                    Customers can register from the sign-in page. Assign staff
                    roles here. Existing access tokens expire within 15 minutes.
                  </p>
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th>Person</th>
                          <th>Role</th>
                          <th>Warehouse</th>
                          <th>Action</th>
                        </tr>
                      </thead>
                      <tbody>
                        {users.data?.map((person) => (
                          <tr key={person.id}>
                            <td>
                              {person.name}
                              <small>{person.email}</small>
                            </td>
                            <td>{label(person.role)}</td>
                            <td>
                              {person.warehouse ? wname(person.warehouse) : "—"}
                            </td>
                            <td>
                              <button
                                className="text-button"
                                onClick={() =>
                                  setDialog({
                                    title: "Update access",
                                    subtitle: person.email,
                                    fields: [
                                      {
                                        name: "role",
                                        label: "Role",
                                        value: person.role,
                                        options: [
                                          "CUSTOMER",
                                          "OPERATOR",
                                          "ADMIN",
                                        ].map((r) => ({
                                          value: r,
                                          label: label(r),
                                        })),
                                      },
                                      {
                                        name: "warehouse",
                                        label: "Warehouse (operators only)",
                                        value: person.warehouse || ws[0]?.id,
                                        options: ws.map((w) => ({
                                          value: w.id,
                                          label: w.name,
                                        })),
                                      },
                                    ],
                                    submit: (v) =>
                                      mutate(
                                        `/auth/users/${person.id}/assignment`,
                                        "PUT",
                                        v,
                                      ),
                                  })
                                }
                              >
                                Edit access
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>
              )}
            </>
          )}
          <footer className="page-footer">
            <span>Stockflow · Distributed Inventory & Order Management</span>
            <span>Local demo / Synthetic data</span>
          </footer>
        </main>
      </div>
      {notice && (
        <div className="toast" role="status">
          <Check size={18} />
          {notice}
          <button
            onClick={() => setNotice("")}
            aria-label="Dismiss notification"
          >
            <X size={15} />
          </button>
        </div>
      )}
      {dialog && <DialogView dialog={dialog} close={() => setDialog(null)} />}
    </div>
  );
}
function App() {
  const [user, setUser] = useState<Row | null>(null),
    [loading, setLoading] = useState(true);
  useEffect(() => {
    restore()
      .then((r) => setUser(r.user))
      .catch(() => {})
      .finally(() => setLoading(false));
    const expired = () => {
      setUser(null);
      client.clear();
    };
    window.addEventListener("session-expired", expired);
    return () => window.removeEventListener("session-expired", expired);
  }, []);
  if (loading)
    return (
      <div className="boot">
        <Boxes />
        <p>Opening Stockflow…</p>
      </div>
    );
  return user ? (
    <Workspace
      user={user}
      onLogout={async () => {
        try {
          await api("/auth/logout", "POST");
        } finally {
          setToken("");
          setUser(null);
          client.clear();
        }
      }}
    />
  ) : (
    <Login
      onLogin={(u) => {
        client.clear();
        setUser(u);
      }}
    />
  );
}
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
);

import { useState } from "react";
import { login } from "./api";
import type { Actor } from "./api";
import { Approvals, Audit, Dashboard, Policies, Registry } from "./screens";
import "./theme.css";

const SCREENS = ["Dashboard", "Registry", "Policies", "Approvals", "Audit"] as const;
type Screen = (typeof SCREENS)[number];

export default function App() {
  const [actor, setActor] = useState<Actor | null>(null);
  if (!actor) return <Login onLogin={setActor} />;
  return <Console actor={actor} />;
}

function Login({ onLogin }: { onLogin: (a: Actor) => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      onLogin(await login(username, password));
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login">
      <form className="card" onSubmit={submit}>
        <div className="brand" style={{ padding: 0, border: 0, marginBottom: 16 }}>
          <b>spring-mcp-gateway</b>
          <span>CONTROL PLANE</span>
        </div>
        <label className="lbl" htmlFor="u">Username</label>
        <input id="u" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus style={{ margin: "6px 0 12px" }} />
        <label className="lbl" htmlFor="p">Password</label>
        <input id="p" type="password" value={password} onChange={(e) => setPassword(e.target.value)} style={{ margin: "6px 0 16px" }} />
        <button className="btn" disabled={busy} style={{ width: "100%" }}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
        {error && <p className="err">{error}</p>}
        <p className="note">Signs in against Keycloak; the token stays in memory only.</p>
      </form>
    </div>
  );
}

function Console({ actor }: { actor: Actor }) {
  const [screen, setScreen] = useState<Screen>("Dashboard");
  return (
    <div className="app">
      <aside className="rail">
        <div className="brand">
          <b>spring-mcp-gateway</b>
          <span>CONTROL PLANE</span>
        </div>
        <nav className="nav">
          {SCREENS.map((s) => (
            <button key={s} className={s === screen ? "on" : ""} onClick={() => setScreen(s)}>
              {s}
            </button>
          ))}
        </nav>
        <div className="foot">
          gateway <i style={{ color: "var(--ok)", fontStyle: "normal" }}>●</i> connected
        </div>
      </aside>
      <main className="main">
        <div className="topbar">
          <span className="crumb">{screen}</span>
          <span className="who">
            <span className="tenant">{actor.tenantId}</span> {actor.username} · {actor.roles.join(", ")}
          </span>
        </div>
        {screen === "Dashboard" && <Dashboard />}
        {screen === "Registry" && <Registry />}
        {screen === "Policies" && <Policies actor={actor} />}
        {screen === "Approvals" && <Approvals />}
        {screen === "Audit" && <Audit />}
      </main>
    </div>
  );
}

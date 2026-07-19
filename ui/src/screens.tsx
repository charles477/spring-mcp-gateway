import { useEffect, useState } from "react";
import { api } from "./api";
import type { Actor } from "./api";

// ---------- shared bits ----------

function Pill({ kind, children }: { kind: string; children: React.ReactNode }) {
  return <span className={`pill p-${kind}`}>{children}</span>;
}

const decisionKind: Record<string, string> = {
  ALLOWED: "ok",
  DENIED: "bad",
  QUARANTINED: "quar",
  ERROR: "am",
};

// ---------- Dashboard ----------

interface AuditRow {
  occurredAt: string;
  actorName: string;
  action: string;
  toolRef: string | null;
  decision: string;
  detail: string | null;
}

export function Dashboard() {
  const [rows, setRows] = useState<AuditRow[]>([]);
  const [digest, setDigest] = useState<string>("");
  const [error, setError] = useState("");

  useEffect(() => {
    api<AuditRow[]>("/admin/audit?limit=25")
      .then(setRows)
      .catch((e) => setError(e.message));
    api<{ digest: string }>("/admin/ai/digest", { method: "POST", body: "{}" })
      .then((d) => setDigest(d.digest))
      .catch(() => setDigest(""));
  }, []);

  const calls = rows.filter((r) => r.action === "tools/call");
  const denies = calls.filter((r) => r.decision === "DENIED");

  return (
    <section>
      <h2>Recent activity</h2>
      <p className="sub">Every number links back to the audit trail behind it.</p>
      {error && <p className="err">{error}</p>}
      <div className="grid g4">
        <div className="card"><div className="lbl">Tool calls (recent)</div><div className="val">{calls.length}</div></div>
        <div className="card"><div className="lbl">Policy denies</div><div className="val">{denies.length}</div></div>
        <div className="card"><div className="lbl">Quarantine events</div>
          <div className="val" style={{ color: "var(--quar)" }}>{rows.filter((r) => r.decision === "QUARANTINED").length}</div></div>
        <div className="card"><div className="lbl">Audit rows loaded</div><div className="val">{rows.length}</div></div>
      </div>
      <div className="grid g2" style={{ marginTop: 14 }}>
        <div className="card">
          <div className="lbl">Live decision stream</div>
          <table style={{ marginTop: 8 }}>
            <tbody>
              {rows.slice(0, 8).map((r, i) => (
                <tr key={i}>
                  <td className="mono">{r.occurredAt.slice(11, 19)}</td>
                  <td className="mono">{r.actorName}</td>
                  <td className="mono">{r.toolRef ?? r.action}</td>
                  <td><Pill kind={decisionKind[r.decision] ?? "dim"}>{r.decision}</Pill></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card copilot">
          <span className="tag">◆ AI digest</span>
          <p style={{ fontSize: 13 }}>{digest || "AI not configured — set ANTHROPIC_API_KEY on the gateway to enable the digest."}</p>
        </div>
      </div>
    </section>
  );
}

// ---------- Registry ----------

interface ToolInfo { id: string; name: string; sensitivityTier: string; status: string; manifestHash: string; }
interface Server { id: string; name: string; shared: boolean; enabled: boolean; tools: ToolInfo[]; }

export function Registry() {
  const [servers, setServers] = useState<Server[]>([]);
  const [error, setError] = useState("");
  const load = () => api<Server[]>("/admin/servers").then(setServers).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  const toggle = (s: Server) =>
    api(`/admin/servers/${s.id}/enabled`, { method: "PATCH", body: JSON.stringify({ enabled: !s.enabled }) })
      .then(load).catch((e) => setError(e.message));
  const reapprove = (t: ToolInfo) =>
    api(`/admin/tools/${t.id}/reapprove`, { method: "POST" }).then(load).catch((e) => setError(e.message));

  return (
    <section>
      <h2>Registered MCP servers</h2>
      <p className="sub">Manifest-pinned. Drift quarantines automatically; the kill switch is fleet-wide within a second.</p>
      {error && <p className="err">{error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Server</th><th>Scope</th><th>Tool</th><th>Manifest</th><th>Status</th><th>Kill switch</th></tr></thead>
          <tbody>
            {servers.flatMap((s) =>
              s.tools.map((t) => (
                <tr key={t.id}>
                  <td className="mono">{s.name}</td>
                  <td><Pill kind={s.shared ? "am" : "dim"}>{s.shared ? "shared" : "tenant"}</Pill></td>
                  <td className="mono">{t.name} {t.sensitivityTier === "RESTRICTED" && <Pill kind="am">RESTRICTED</Pill>}</td>
                  <td className="mono">{t.manifestHash.slice(0, 10)}…</td>
                  <td>
                    <Pill kind={t.status === "ACTIVE" ? "ok" : "quar"}>{t.status}</Pill>{" "}
                    {t.status === "QUARANTINED" && (
                      <button className="btn ghost" style={{ padding: "3px 10px", fontSize: 11 }} onClick={() => reapprove(t)}>
                        Re-approve
                      </button>
                    )}
                  </td>
                  <td>
                    <button className={s.enabled ? "btn danger" : "btn"} style={{ padding: "3px 10px", fontSize: 11 }} onClick={() => toggle(s)}>
                      {s.enabled ? "Disable" : "Restore"}
                    </button>
                  </td>
                </tr>
              )),
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

// ---------- Policies ----------

interface Policy { id: string; name: string; version: number; status: string; effect: string; }
interface DraftResult { name: string; effect: string; subjects: unknown; resources: unknown; conditions: unknown; rationale: string; }

export function Policies({ actor }: { actor: Actor }) {
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [intent, setIntent] = useState("");
  const [draft, setDraft] = useState<DraftResult | null>(null);
  const [sim, setSim] = useState<{ current: string; withDrafts: string } | null>(null);
  const [draftId, setDraftId] = useState<string>("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const load = () => api<Policy[]>("/admin/policies").then(setPolicies).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  const generate = async () => {
    setBusy(true); setError(""); setSim(null);
    try {
      const d = await api<DraftResult>("/admin/ai/draft-policy", {
        method: "POST",
        body: JSON.stringify({ intent }),
      });
      setDraft(d);
      const created = await api<Policy>("/admin/policies", {
        method: "POST",
        body: JSON.stringify({ name: d.name, effect: d.effect, platformGlobal: false, subjects: d.subjects, resources: d.resources, conditions: d.conditions }),
      });
      setDraftId(created.id);
      const s = await api<{ current: { allowed: boolean }; withDrafts: { allowed: boolean } }>(
        "/admin/policies/simulate",
        { method: "POST", body: JSON.stringify({ actorSubject: actor.subject, actorRoles: ["tool-user"], tool: "crm-tools.crm.read", includeDrafts: [created.id] }) },
      );
      setSim({ current: s.current.allowed ? "ALLOW" : "DENY", withDrafts: s.withDrafts.allowed ? "ALLOW" : "DENY" });
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  };

  const activate = () =>
    api(`/admin/policies/${draftId}/activate`, { method: "POST" })
      .then(() => { setDraft(null); setSim(null); load(); })
      .catch((e) => setError(e.message));

  return (
    <section>
      <h2>Policies</h2>
      <p className="sub">Deny by default. Explicit deny wins. Nothing activates without a dry-run.</p>
      {error && <p className="err">{error}</p>}
      <div className="grid g2">
        <div className="card">
          <table>
            <thead><tr><th>Policy</th><th>Effect</th><th>Ver</th><th>Status</th></tr></thead>
            <tbody>
              {policies.map((p) => (
                <tr key={p.id}>
                  <td>{p.name}</td>
                  <td><Pill kind={p.effect === "ALLOW" ? "ok" : "bad"}>{p.effect}</Pill></td>
                  <td className="mono">v{p.version}</td>
                  <td><Pill kind={p.status === "ACTIVE" ? "ok" : "dim"}>{p.status}</Pill></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card copilot">
          <span className="tag">◆ Policy copilot</span>
          <textarea rows={2} value={intent} onChange={(e) => setIntent(e.target.value)}
            placeholder="Describe the access rule in plain English…" />
          <button className="btn" onClick={generate} disabled={busy || !intent.trim()} style={{ marginTop: 10 }}>
            {busy ? "Drafting…" : "Draft policy"}
          </button>
          {draft && (
            <pre>{JSON.stringify({ name: draft.name, effect: draft.effect, subjects: draft.subjects, resources: draft.resources, conditions: draft.conditions }, null, 1)}</pre>
          )}
          {sim && (
            <p className="mono" style={{ marginTop: 10 }}>
              dry-run: <Pill kind={sim.current === "ALLOW" ? "ok" : "bad"}>now {sim.current}</Pill> →{" "}
              <Pill kind={sim.withDrafts === "ALLOW" ? "ok" : "bad"}>with draft {sim.withDrafts}</Pill>{" "}
              <button className="btn" style={{ marginLeft: 8 }} onClick={activate}>Activate</button>
            </p>
          )}
          <p className="note">The copilot only drafts — every draft passes the same simulate-then-activate gate as a hand-written one.</p>
        </div>
      </div>
    </section>
  );
}

// ---------- Approvals ----------

interface Approval { id: string; toolRef: string; requesterName: string; arguments: string; status: string; }

export function Approvals() {
  const [items, setItems] = useState<Approval[]>([]);
  const [error, setError] = useState("");
  const load = () => api<Approval[]>("/admin/approvals").then(setItems).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);
  const decide = (id: string, verb: "approve" | "reject") =>
    api(`/admin/approvals/${id}/${verb}`, { method: "POST" }).then(load).catch((e) => setError(e.message));

  return (
    <section>
      <h2>Approval queue</h2>
      <p className="sub">One-shot tickets bound to the exact arguments you approve. Requesters can never approve themselves.</p>
      {error && <p className="err">{error}</p>}
      <div className="card">
        {items.length === 0 && <p className="sub" style={{ margin: 0 }}>Nothing pending.</p>}
        {items.map((a) => (
          <div key={a.id} style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "13px 0", borderBottom: "1px solid rgba(36,49,73,.5)" }}>
            <div>
              <b>{a.requesterName}</b> wants <span className="mono" style={{ color: "var(--amber)" }}>{a.toolRef}</span>
              <div className="mono" style={{ color: "var(--amber)" }}>{a.arguments}</div>
            </div>
            <div>
              <button className="btn" onClick={() => decide(a.id, "approve")}>Approve</button>{" "}
              <button className="btn danger" onClick={() => decide(a.id, "reject")}>Reject</button>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

// ---------- Audit ----------

export function Audit() {
  const [rows, setRows] = useState<AuditRow[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    api<AuditRow[]>("/admin/audit?limit=50").then(setRows).catch((e) => setError(e.message));
  }, []);
  return (
    <section>
      <h2>Audit trail</h2>
      <p className="sub">Append-only. The agent never sees why — you always do.</p>
      {error && <p className="err">{error}</p>}
      <div className="card">
        <table>
          <thead><tr><th>Time</th><th>Actor</th><th>Action</th><th>Decision</th><th>Why</th></tr></thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i}>
                <td className="mono">{r.occurredAt.replace("T", " ").slice(0, 19)}</td>
                <td className="mono">{r.actorName}</td>
                <td className="mono">{r.action} {r.toolRef ?? ""}</td>
                <td><Pill kind={decisionKind[r.decision] ?? "dim"}>{r.decision}</Pill></td>
                <td style={{ color: "var(--dim)", fontSize: 12 }}>{r.detail ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

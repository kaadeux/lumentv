// Lumen TV profile sync. Stores one encrypted blob per profile; it never sees the content nor the passphrase.
//
// A device derives, from the profile name and passphrase: the profile id (64 hex), an encryption key (kept on the
// device) and an access token (64 hex). The server keeps only SHA-256(token), so its database alone gives no access.
//
//   GET    /v1/profile/:id   Authorization: Bearer <token>   -> 200 {version, data} | 404 | 403
//   PUT    /v1/profile/:id   {baseVersion, data}             -> 200 {version} | 409 {version, data} | 403
//          (baseVersion 0 creates the profile; a stale baseVersion gets 409 with the current copy to merge with)
//   DELETE /v1/profile/:id                                   -> 204 | 403

const MAX_BYTES = 4 * 1024 * 1024;
const HEX64 = /^[0-9a-f]{64}$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/" || url.pathname === "/v1/health") return json({ ok: true, service: "lumentv-sync" });

    const m = url.pathname.match(/^\/v1\/profile\/([0-9a-f]{64})$/);
    if (!m) return json({ error: "not found" }, 404);
    const id = m[1];
    const token = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
    if (!HEX64.test(token)) return json({ error: "missing token" }, 401);
    const auth = await sha256Hex(token);

    const row = await env.DB.prepare("SELECT auth, version, data FROM profiles WHERE id = ?").bind(id).first();
    if (row && !timingSafeEqual(row.auth, auth)) return json({ error: "forbidden" }, 403);

    if (request.method === "GET") {
      if (!row) return json({ error: "no profile" }, 404);
      return json({ version: row.version, data: row.data });
    }

    if (request.method === "PUT") {
      const len = Number(request.headers.get("Content-Length") || 0);
      if (len > MAX_BYTES) return json({ error: "too large" }, 413);
      let body;
      try { body = await request.json(); } catch { return json({ error: "bad json" }, 400); }
      const { baseVersion, data } = body || {};
      if (typeof data !== "string" || data.length > MAX_BYTES || !Number.isInteger(baseVersion)) return json({ error: "bad body" }, 400);
      const now = Date.now();
      if (!row) {
        if (baseVersion !== 0) return json({ error: "no profile" }, 404);
        const r = await env.DB.prepare("INSERT OR IGNORE INTO profiles (id, auth, version, data, updated) VALUES (?, ?, 1, ?, ?)")
          .bind(id, auth, data, now).run();
        if (r.meta.changes === 1) return json({ version: 1 });
        return conflict(env, id); // created at the same moment by another device
      }
      // Compare-and-swap on the version: two devices writing at once cannot overwrite each other.
      const r = await env.DB.prepare("UPDATE profiles SET version = version + 1, data = ?, updated = ? WHERE id = ? AND version = ?")
        .bind(data, now, id, baseVersion).run();
      if (r.meta.changes === 1) return json({ version: baseVersion + 1 });
      return conflict(env, id);
    }

    if (request.method === "DELETE") {
      if (row) await env.DB.prepare("DELETE FROM profiles WHERE id = ?").bind(id).run();
      return new Response(null, { status: 204 });
    }
    return json({ error: "method" }, 405);
  },
};

async function conflict(env, id) {
  const cur = await env.DB.prepare("SELECT version, data FROM profiles WHERE id = ?").bind(id).first();
  return json({ version: cur.version, data: cur.data }, 409);
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
}

async function sha256Hex(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

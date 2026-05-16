/**
 * litellm.js — LiteLLM Proxy monitoring and key management.
 *
 * Features:
 *  - Proxy health status
 *  - All-users usage summary (cost, requests)
 *  - Per-user key lookup and revocation
 *  - Create per-user API keys
 *
 * API endpoints:
 *  GET    /admin/api/litellm/health
 *  GET    /admin/api/litellm/usage/all
 *  GET    /admin/api/litellm/usage/{user_id}
 *  GET    /admin/api/litellm/keys/{user_id}
 *  POST   /admin/api/litellm/keys/create?user_id=...
 *  DELETE /admin/api/litellm/keys/revoke?key=...
 */

const LiteLLM = (() => {

  // ── Shell ───────────────────────────────────────────────────────────────────

  async function render() {
    const el = document.getElementById('section-litellm');
    el.innerHTML = `
      <div class="page-header">
        <h3>LiteLLM Proxy</h3>
        <button class="btn btn-secondary btn-sm" id="llm-reload">&#8635; Reload</button>
      </div>

      <!-- Health -->
      <div id="llm-health-row" class="kpi-row mb-4">
        <div class="loading"><div class="spinner"></div></div>
      </div>

      <!-- Usage Table -->
      <div class="card mb-4">
        <div class="card-header">
          <h4>&#128202; All-Users Usage</h4>
          <span class="text-sm text-muted" id="llm-total-cost"></span>
        </div>
        <div class="table-wrap" id="llm-usage-table">
          <div class="loading"><div class="spinner"></div> Loading…</div>
        </div>
      </div>

      <!-- Per-user lookup -->
      <div class="card">
        <div class="card-header"><h4>&#128273; Per-User Key Management</h4></div>
        <div class="card-body">
          <div class="flex gap-2 mb-4" style="flex-wrap:wrap;align-items:flex-end;">
            <div class="form-group" style="margin:0;flex:1;min-width:200px;">
              <label>User ID / UID</label>
              <input type="text" id="llm-uid-input" placeholder="Firebase UID or user@email.com" />
            </div>
            <button class="btn btn-primary btn-sm" onclick="LiteLLM.lookupUser()">Look Up</button>
            <button class="btn btn-secondary btn-sm" onclick="LiteLLM.openCreateKey()">+ Create Key</button>
          </div>
          <div id="llm-user-result"></div>
        </div>
      </div>`;

    document.getElementById('llm-reload').addEventListener('click', _loadAll);
    await _loadAll();
  }

  // ── Load all ────────────────────────────────────────────────────────────────

  async function _loadAll() {
    await Promise.all([_loadHealth(), _loadUsage()]);
  }

  // ── Health ──────────────────────────────────────────────────────────────────

  async function _loadHealth() {
    const row = document.getElementById('llm-health-row');
    if (!row) return;
    try {
      const data = await API.get('/litellm/health');
      const isOk = data.status === 'healthy';
      row.innerHTML = `
        <div class="kpi-card">
          <div class="kpi-icon" style="background:${isOk ? '#10b98120' : '#ef444420'}">
            <span style="font-size:20px;">${isOk ? '&#10003;' : '&#9888;'}</span>
          </div>
          <div class="kpi-info">
            <div class="kpi-value" style="color:${isOk ? '#10b981' : '#ef4444'}">${isOk ? 'Healthy' : 'Unhealthy'}</div>
            <div class="kpi-label">LiteLLM Proxy</div>
          </div>
        </div>
        <div class="kpi-card">
          <div class="kpi-icon" style="background:#3b82f620"><span style="font-size:20px;">&#128279;</span></div>
          <div class="kpi-info">
            <div class="kpi-value" style="font-size:13px;">${esc(data.proxy_url || '—')}</div>
            <div class="kpi-label">Proxy URL</div>
          </div>
        </div>`;
    } catch (err) {
      row.innerHTML = `<div class="empty-state"><p>&#9888; ${esc(err.message)}</p></div>`;
    }
  }

  // ── Usage table ─────────────────────────────────────────────────────────────

  async function _loadUsage() {
    const wrap = document.getElementById('llm-usage-table');
    const totalEl = document.getElementById('llm-total-cost');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      const data = await API.get('/litellm/usage/all');
      const totalCost = data.total_cost_all_users || 0;
      if (totalEl) totalEl.textContent = `Total: $${totalCost.toFixed(4)}`;

      // Handle both list and dict shapes
      let rows = [];
      if (Array.isArray(data)) {
        rows = data;
      } else if (data.users) {
        if (Array.isArray(data.users)) {
          rows = data.users.map(u => ({
            uid: u.user_id || u.id || '—',
            cost: u.spend || u.total_cost || 0,
            requests: u.total_requests_made || u.total_requests || 0,
          }));
        } else {
          rows = Object.entries(data.users).map(([uid, v]) => ({
            uid,
            cost: v.total_cost || v.spend || 0,
            requests: v.total_requests || 0,
          }));
        }
      }

      rows.sort((a, b) => (b.cost || 0) - (a.cost || 0));

      if (!rows.length) {
        wrap.innerHTML = '<div class="empty-state"><p>No usage data yet.</p></div>';
        return;
      }

      wrap.innerHTML = `
        <table class="data-table">
          <thead>
            <tr>
              <th>#</th>
              <th>User ID</th>
              <th>Total Cost (USD)</th>
              <th>Requests</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            ${rows.map((r, i) => `
              <tr>
                <td class="text-muted">${i + 1}</td>
                <td><code style="font-size:11px;">${esc(r.uid || '—')}</code></td>
                <td>
                  <span style="font-weight:600;color:${(r.cost || 0) > 1 ? '#ef4444' : '#10b981'}">
                    $${(r.cost || 0).toFixed(4)}
                  </span>
                </td>
                <td>${(r.requests || 0).toLocaleString()}</td>
                <td>
                  <button class="btn btn-sm btn-secondary"
                    onclick="LiteLLM.lookupUserById('${esc(r.uid || '')}')">
                    Keys &amp; Details
                  </button>
                </td>
              </tr>`).join('')}
          </tbody>
        </table>`;
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${esc(err.message)}</p></div>`;
      if (totalEl) totalEl.textContent = '';
    }
  }

  // ── Per-user lookup ─────────────────────────────────────────────────────────

  function lookupUser() {
    const uid = (document.getElementById('llm-uid-input')?.value || '').trim();
    if (!uid) { Toast.error('Enter a user ID.'); return; }
    lookupUserById(uid);
  }

  async function lookupUserById(uid) {
    const result = document.getElementById('llm-user-result');
    if (!result) return;

    // Fill the input
    const inp = document.getElementById('llm-uid-input');
    if (inp) inp.value = uid;

    result.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';

    try {
      const [keysData, usageData] = await Promise.all([
        API.get(`/litellm/keys/${encodeURIComponent(uid)}`).catch(() => ({ keys: [] })),
        API.get(`/litellm/usage/${encodeURIComponent(uid)}`).catch(() => null),
      ]);

      const keys = keysData.keys || [];
      const usage = usageData || {};

      result.innerHTML = `
        <div class="card mb-3">
          <div class="card-header">
            <h4>Usage — <code style="font-size:12px;">${esc(uid)}</code></h4>
          </div>
          <div class="card-body">
            <div class="kv-grid">
              <span class="kv-key">Total Cost</span>
              <span class="kv-val">$${((usage.total_cost || usage.spend || 0)).toFixed(4)}</span>
              <span class="kv-key">Requests</span>
              <span class="kv-val">${(usage.total_requests || 0).toLocaleString()}</span>
              <span class="kv-key">Input Tokens</span>
              <span class="kv-val">${(usage.total_input_tokens || 0).toLocaleString()}</span>
              <span class="kv-key">Output Tokens</span>
              <span class="kv-val">${(usage.total_output_tokens || 0).toLocaleString()}</span>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-header">
            <h4>API Keys (${keys.length})</h4>
            <button class="btn btn-sm btn-primary" onclick="LiteLLM.openCreateKey('${esc(uid)}')">+ Create Key</button>
          </div>
          <div class="card-body">
            ${keys.length ? `
              <table class="data-table">
                <thead><tr><th>Key (masked)</th><th>Created</th><th>Status</th><th>Actions</th></tr></thead>
                <tbody>
                  ${keys.map(k => `
                    <tr>
                      <td><code style="font-size:11px;">${esc(_maskKey(k.key || k.token || ''))}</code></td>
                      <td>${esc(k.created_at ? new Date(k.created_at).toLocaleDateString() : '—')}</td>
                      <td><span class="badge ${k.is_valid !== false ? 'badge-green' : 'badge-gray'}">${k.is_valid !== false ? 'Active' : 'Revoked'}</span></td>
                      <td>
                        ${k.is_valid !== false ? `<button class="btn btn-sm btn-danger"
                          onclick="LiteLLM.revokeKey('${esc(k.key || k.token || '')}', '${esc(uid)}')">
                          Revoke</button>` : '—'}
                      </td>
                    </tr>`).join('')}
                </tbody>
              </table>` : '<p class="text-sm text-muted">No API keys for this user.</p>'}
          </div>
        </div>`;
    } catch (err) {
      result.innerHTML = `<div class="empty-state"><p>&#9888; ${esc(err.message)}</p></div>`;
    }
  }

  // ── Create key ──────────────────────────────────────────────────────────────

  function openCreateKey(prefillUid = '') {
    Modal.open('Create LiteLLM Key', `
      <div class="form-grid">
        <div class="form-group">
          <label>User ID <span style="color:red">*</span></label>
          <input type="text" id="ck-uid" value="${esc(prefillUid)}" placeholder="Firebase UID" />
        </div>
        <div class="form-group">
          <label>Display Name (optional)</label>
          <input type="text" id="ck-name" placeholder="e.g. Ravi Kumar" />
        </div>
      </div>
      <div class="form-group mt-2">
        <p class="text-sm text-muted">
          Creates a new API key in LiteLLM for per-user token tracking and budget enforcement.
        </p>
      </div>`, {
      saveLabel: 'Create Key',
      onSave: async () => {
        const uid  = (document.getElementById('ck-uid')?.value || '').trim();
        const name = (document.getElementById('ck-name')?.value || '').trim();
        if (!uid) { Toast.error('User ID is required.'); return; }
        try {
          const params = new URLSearchParams({ user_id: uid });
          if (name) params.append('name', name);
          const res = await API.post(`/litellm/keys/create?${params}`);
          Modal.close();
          Toast.success(`Key created: ${_maskKey(res.key || '')}`);
          if (uid === (document.getElementById('llm-uid-input')?.value || '').trim()) {
            await lookupUserById(uid);
          }
        } catch (err) {
          Toast.error(err.message);
        }
      },
    });
  }

  // ── Revoke key ──────────────────────────────────────────────────────────────

  async function revokeKey(key, uid) {
    Modal.open('Revoke Key', `
      <div class="warn-box">
        <p>&#9888; Revoke API key <code>${esc(_maskKey(key))}</code>?</p>
        <p class="text-sm text-muted">The user won't be able to make LLM calls with this key.</p>
      </div>`, {
      saveLabel: 'Revoke',
      onSave: async () => {
        try {
          await API.delete(`/litellm/keys/revoke?key=${encodeURIComponent(key)}`);
          Modal.close();
          Toast.success('Key revoked.');
          await lookupUserById(uid);
        } catch (err) {
          Toast.error(err.message);
        }
      },
    });
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  function _maskKey(key) {
    if (!key || key.length < 12) return key || '—';
    return key.slice(0, 8) + '…' + key.slice(-4);
  }

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  return { render, lookupUser, lookupUserById, openCreateKey, revokeKey };
})();

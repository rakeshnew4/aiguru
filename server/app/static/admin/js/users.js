/**
 * users.js — User management section.
 *
 * Features:
 *  - Paginated table of users with search/filter
 *  - View full user details (including chapter_progress)
 *  - Edit user fields via JSON editor
 *  - Delete user (with confirmation)
 *
 * API endpoints used:
 *  GET    /admin/api/users
 *  GET    /admin/api/users/{uid}
 *  PUT    /admin/api/users/{uid}
 *  DELETE /admin/api/users/{uid}
 */

const Users = (() => {
  let _allUsers   = [];
  let _filtered   = [];
  let _page       = 1;
  const PAGE_SIZE = 25;

  // ── Render shell ─────────────────────────────────────────────

  async function render() {
    const el = document.getElementById('section-users');
    el.innerHTML = `
      <div class="page-header">
        <h3>Users</h3>
        <button class="btn btn-primary btn-sm" id="users-reload">&#8635; Reload</button>
      </div>

      <div class="filter-bar">
        <input type="text" id="user-search" placeholder="Search by name, email, UID…" />
        <select id="user-filter-plan">
          <option value="">All plans</option>
        </select>
        <select id="user-filter-grade">
          <option value="">All grades</option>
        </select>
      </div>

      <div class="card">
        <div class="card-header">
          <h4 id="users-count">Users</h4>
          <span class="text-sm text-muted">Showing up to 200 records</span>
        </div>
        <div class="table-wrap" id="users-table-wrap">
          <div class="loading"><div class="spinner"></div> Loading…</div>
        </div>
        <div id="users-pagination" class="pagination" style="padding:12px 16px;"></div>
      </div>`;

    document.getElementById('users-reload').addEventListener('click', loadUsers);
    document.getElementById('user-search').addEventListener('input', applyFilter);
    document.getElementById('user-filter-plan').addEventListener('change', applyFilter);
    document.getElementById('user-filter-grade').addEventListener('change', applyFilter);

    await loadUsers();
  }

  // ── Load ──────────────────────────────────────────────────────

  async function loadUsers() {
    const wrap = document.getElementById('users-table-wrap');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      _allUsers = await API.get('/users?limit=200');
      _page = 1;
      _populateFilters();
      applyFilter();
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function _populateFilters() {
    const plans  = [...new Set(_allUsers.map(u => u.plan_id || u.plan || '').filter(Boolean))];
    const grades = [...new Set(_allUsers.map(u => String(u.grade || u.class_grade || '')).filter(Boolean))];

    const planSel  = document.getElementById('user-filter-plan');
    const gradeSel = document.getElementById('user-filter-grade');
    if (!planSel) return;

    planSel.innerHTML = '<option value="">All plans</option>' +
      plans.map(p => `<option value="${p}">${p}</option>`).join('');
    gradeSel.innerHTML = '<option value="">All grades</option>' +
      grades.sort().map(g => `<option value="${g}">${g}</option>`).join('');
  }

  function applyFilter() {
    const q     = (document.getElementById('user-search')?.value || '').toLowerCase();
    const plan  = document.getElementById('user-filter-plan')?.value  || '';
    const grade = document.getElementById('user-filter-grade')?.value || '';

    _filtered = _allUsers.filter(u => {
      const matchQ = !q ||
        (u._id    || '').toLowerCase().includes(q) ||
        (u.email  || '').toLowerCase().includes(q) ||
        (u.name   || u.display_name || '').toLowerCase().includes(q) ||
        (u.phone  || '').toLowerCase().includes(q);
      const matchP = !plan  || (u.plan_id || u.plan || '') === plan;
      const matchG = !grade || String(u.grade || u.class_grade || '') === grade;
      return matchQ && matchP && matchG;
    });

    _page = 1;
    renderTable();
  }

  // ── Table ─────────────────────────────────────────────────────

  function renderTable() {
    const wrap = document.getElementById('users-table-wrap');
    const countEl = document.getElementById('users-count');
    if (!wrap) return;

    const total = _filtered.length;
    const start = (_page - 1) * PAGE_SIZE;
    const rows  = _filtered.slice(start, start + PAGE_SIZE);
    if (countEl) countEl.textContent = `Users (${total.toLocaleString()})`;

    if (rows.length === 0) {
      wrap.innerHTML = '<div class="empty-state"><p>No users found.</p></div>';
      renderPagination(total);
      return;
    }

    wrap.innerHTML = `
      <table>
        <thead>
          <tr>
            <th>UID</th>
            <th>Name</th>
            <th>Email / Phone</th>
            <th>Grade</th>
            <th>Plan</th>
            <th>Credits</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          ${rows.map(u => `
            <tr>
              <td class="td-truncate" style="max-width:110px;font-size:11px;color:var(--c-text-2);">${u._id}</td>
              <td>${esc(u.name || u.display_name || '—')}</td>
              <td>${esc(u.email || u.phone || '—')}</td>
              <td>${esc(String(u.grade || u.class_grade || '—'))}</td>
              <td>${u.plan_id || u.plan ? `<span class="badge badge-blue">${esc(u.plan_id || u.plan)}</span>` : '—'}</td>
              <td>—</td>
              <td>
                <div class="td-actions">
                  <button class="btn btn-ghost btn-sm" onclick="Users.viewUser('${u._id}')">View</button>
                  <button class="btn btn-secondary btn-sm" onclick="Users.editUser('${u._id}')">Edit</button>
                  <button class="btn btn-danger btn-sm" onclick="Users.deleteUser('${u._id}')">Delete</button>
                </div>
              </td>
            </tr>`).join('')}
        </tbody>
      </table>`;
    renderPagination(total);
  }

  function renderPagination(total) {
    const pg = document.getElementById('users-pagination');
    if (!pg) return;
    const pages = Math.ceil(total / PAGE_SIZE);
    if (pages <= 1) { pg.innerHTML = ''; return; }

    let html = `<span class="pagination-info">Page ${_page} of ${pages}</span>`;
    if (_page > 1)    html += `<button class="btn btn-secondary btn-sm" onclick="Users._goPage(${_page-1})">&#8249; Prev</button>`;
    if (_page < pages) html += `<button class="btn btn-secondary btn-sm" onclick="Users._goPage(${_page+1})">Next &#8250;</button>`;
    pg.innerHTML = html;
  }

  function _goPage(p) { _page = p; renderTable(); }

  // ── View ──────────────────────────────────────────────────────

  async function viewUser(uid) {
    Modal.open('User Details', '<div class="loading"><div class="spinner"></div></div>', { saveLabel: null, wide: true });
    try {
      const u = await API.get(`/users/${uid}`);
      const progress = u.chapter_progress || [];
      delete u.chapter_progress;

      let creditsHtml = '<div class="loading" style="padding:16px;">Loading credits…</div>';
      try {
        const credits = await API.get(`/users/${uid}/credits`);
        const transactions = await API.get(`/users/${uid}/credits/transactions?limit=5`);
        creditsHtml = `
          <div style="background:#f5f5f5;padding:12px;border-radius:4px;margin-bottom:12px;">
            <div style="display:flex;justify-content:space-between;align-items:center;">
              <div>
                <p style="font-size:12px;color:#666;margin:0;">Current Balance</p>
                <p style="font-size:20px;font-weight:bold;color:#22c55e;margin:4px 0;">${credits.balance || 0}</p>
              </div>
              <div>
                <p style="font-size:12px;color:#666;margin:0;">Lifetime Earned</p>
                <p style="font-size:20px;font-weight:bold;color:#3b82f6;margin:4px 0;">${credits.lifetime_earned || 0}</p>
              </div>
              <button class="btn btn-primary btn-sm" onclick="Users.adjustCredits('${uid}');Modal.close()">💳 Adjust Credits</button>
            </div>
          </div>
          ${transactions.length ? `
            <h4 style="margin-bottom:8px;font-size:13px;">Recent Transactions</h4>
            <div class="table-wrap">
              <table>
                <thead><tr><th>Type</th><th>Amount</th><th>Reason</th><th>Balance After</th></tr></thead>
                <tbody>
                  ${transactions.map(t => `<tr>
                    <td><span class="badge ${t.amount > 0 ? 'badge-green' : 'badge-red'}">${t.type}</span></td>
                    <td style="font-weight:bold;color:${t.amount > 0 ? '#22c55e' : '#ef4444'}">${t.amount > 0 ? '+' : ''}${t.amount}</td>
                    <td style="font-size:12px;color:#666;">${esc(t.reason || '—')}</td>
                    <td>${t.balance_after}</td>
                  </tr>`).join('')}
                </tbody>
              </table>
            </div>` : '<p style="color:#999;font-size:12px;">No transactions yet.</p>'}`;
      } catch (e) {
        creditsHtml = `<div style="color:#dc2626;font-size:12px;">Could not load credits: ${e.message}</div>`;
      }

      Modal.setBody(`
        <div class="kv-grid mb-4">
          ${Object.entries(u).map(([k, v]) => `
            <span class="kv-key">${esc(k)}</span>
            <span class="kv-val">${esc(JSON.stringify(v))}</span>
          `).join('')}
        </div>
        <h4 style="margin:16px 0 8px;font-size:14px;font-weight:bold;">Credits</h4>
        ${creditsHtml}
        ${progress.length ? `
          <h4 style="margin:16px 0 8px;font-size:14px;font-weight:bold;">Chapter Progress (${progress.length})</h4>
          <div class="table-wrap">
            <table>
              <thead><tr><th>Chapter</th><th>Score</th><th>Completed</th></tr></thead>
              <tbody>
                ${progress.map(p => `<tr>
                  <td>${esc(p._id)}</td>
                  <td>${p.score ?? '—'}</td>
                  <td>${p.completed ? '✅' : '—'}</td>
                </tr>`).join('')}
              </tbody>
            </table>
          </div>` : ''}
        <div class="mt-4 flex gap-2">
          <button class="btn btn-secondary btn-sm" onclick="Users.editUser('${uid}');Modal.close()">Edit User</button>
          <button class="btn btn-tertiary btn-sm" onclick="Users.quickQuota('${uid}');Modal.close()">⚙️ Quick Quota</button>
        </div>`);
    } catch (err) {
      Modal.setBody(`<div class="empty-state"><p>&#9888; ${err.message}</p></div>`);
    }
  }

  // ── Edit ──────────────────────────────────────────────────────

  async function editUser(uid) {
    Modal.open('Edit User', '<div class="loading"><div class="spinner"></div></div>', {
      onSave: () => _saveUser(uid),
    });
    try {
      const u = await API.get(`/users/${uid}`);
      delete u.chapter_progress;
      Modal.setBody(`
        <p class="text-sm text-muted mb-4">Editing UID: <strong>${uid}</strong></p>
        <div class="form-group">
          <label>User data (JSON)</label>
          <textarea class="json-editor" id="user-edit-json">${JSON.stringify(u, null, 2)}</textarea>
        </div>`);
    } catch (err) {
      Modal.setBody(`<div class="empty-state"><p>&#9888; ${err.message}</p></div>`);
    }
  }

  async function _saveUser(uid) {
    const raw = document.getElementById('user-edit-json')?.value;
    let payload;
    try { payload = JSON.parse(raw); } catch (_) { Toast.error('Invalid JSON'); return; }
    try {
      await API.put(`/users/${uid}`, payload);
      Toast.success('User updated');
      Modal.close();
      await loadUsers();
    } catch (err) { Toast.error(err.message); }
  }

  // ── Adjust Credits ───────────────────────────────────────────

  function adjustCredits(uid) {
    Modal.open('Adjust Credits', `
      <p class="text-sm text-muted mb-4">User: <strong>${uid}</strong></p>
      <div class="form-group">
        <label>Amount (positive = grant, negative = deduct)</label>
        <input type="number" id="credit-amount" placeholder="e.g. 100 or -50" value="100">
      </div>
      <div class="form-group">
        <label>Reason</label>
        <input type="text" id="credit-reason" placeholder="e.g. admin_grant, refund" value="admin_adjustment">
      </div>
      <div id="credit-preview" style="background:#f5f5f5;padding:12px;border-radius:4px;margin:12px 0;font-size:12px;color:#666;">
        Will grant 100 credits
      </div>`, {
      onSave: () => _saveCredits(uid),
    });
    document.getElementById('credit-amount').addEventListener('input', () => {
      const amt = Number(document.getElementById('credit-amount').value) || 0;
      const action = amt > 0 ? 'grant' : amt < 0 ? 'deduct' : 'no change';
      const absAmt = Math.abs(amt);
      document.getElementById('credit-preview').textContent = `Will ${action} ${absAmt} credits`;
    });
  }

  async function _saveCredits(uid) {
    const amount = Number(document.getElementById('credit-amount').value);
    const reason = document.getElementById('credit-reason').value || 'admin_adjustment';
    if (!amount) { Toast.error('Amount must be non-zero'); return; }
    try {
      await API.post(`/users/${uid}/credits/adjust`, { amount, reason });
      Toast.success(`Credits ${amount > 0 ? 'granted' : 'deducted'}`);
      Modal.close();
      await loadUsers();
    } catch (err) { Toast.error(err.message); }
  }

  // ── Quick Quota ──────────────────────────────────────────────

  function quickQuota(uid) {
    Modal.open('Quick Quota Editor', `
      <p class="text-sm text-muted mb-4">User: <strong>${uid}</strong></p>
      <div class="form-group">
        <label>Plan ID</label>
        <input type="text" id="qq-planId" placeholder="e.g. basic, pro">
      </div>
      <div class="form-group">
        <label>Daily Chat Limit</label>
        <input type="number" id="qq-plan_daily_chat_limit" placeholder="e.g. 50">
      </div>
      <div class="form-group">
        <label>Daily Blackboard Limit</label>
        <input type="number" id="qq-plan_daily_bb_limit" placeholder="e.g. 20">
      </div>
      <div class="form-group">
        <label>TTS Quota (chars/month)</label>
        <input type="number" id="qq-ai_tts_quota_chars" placeholder="e.g. 10000">
      </div>
      <div class="form-group">
        <label>Plan Expiry (timestamp)</label>
        <input type="text" id="qq-plan_expiry" placeholder="leave empty for no expiry">
      </div>`, {
      onSave: () => _saveQuota(uid),
    });
    _loadCurrentQuota(uid);
  }

  async function _loadCurrentQuota(uid) {
    try {
      const u = await API.get(`/users/${uid}`);
      if (u.planId) document.getElementById('qq-planId').value = u.planId;
      if (u.plan_daily_chat_limit) document.getElementById('qq-plan_daily_chat_limit').value = u.plan_daily_chat_limit;
      if (u.plan_daily_bb_limit) document.getElementById('qq-plan_daily_bb_limit').value = u.plan_daily_bb_limit;
      if (u.ai_tts_quota_chars) document.getElementById('qq-ai_tts_quota_chars').value = u.ai_tts_quota_chars;
      if (u.plan_expiry) document.getElementById('qq-plan_expiry').value = u.plan_expiry;
    } catch (e) {}
  }

  async function _saveQuota(uid) {
    const payload = {
      planId: document.getElementById('qq-planId').value || undefined,
      plan_daily_chat_limit: Number(document.getElementById('qq-plan_daily_chat_limit').value) || undefined,
      plan_daily_bb_limit: Number(document.getElementById('qq-plan_daily_bb_limit').value) || undefined,
      ai_tts_quota_chars: Number(document.getElementById('qq-ai_tts_quota_chars').value) || undefined,
      plan_expiry: document.getElementById('qq-plan_expiry').value || undefined,
    };
    const safe = {};
    Object.entries(payload).forEach(([k, v]) => { if (v !== undefined) safe[k] = v; });
    if (!Object.keys(safe).length) { Toast.error('No fields changed'); return; }
    try {
      await API.put(`/users/${uid}/quota`, safe);
      Toast.success('Quota updated');
      Modal.close();
      await loadUsers();
    } catch (err) { Toast.error(err.message); }
  }

  // ── Delete ────────────────────────────────────────────────────

  async function deleteUser(uid) {
    if (!confirm(`Delete user ${uid}? This cannot be undone.`)) return;
    try {
      await API.del(`/users/${uid}`);
      Toast.success('User deleted');
      await loadUsers();
    } catch (err) { Toast.error(err.message); }
  }

  // ── Helpers ───────────────────────────────────────────────────

  function esc(s) {
    return String(s)
      .replace(/&/g,'&amp;').replace(/</g,'&lt;')
      .replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render, viewUser, editUser, deleteUser, adjustCredits, quickQuota, _goPage };
})();

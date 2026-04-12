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
            <th>Quota</th>
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
              <td>${u.daily_quota !== undefined ? u.daily_quota : '—'}</td>
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
    Modal.open('User Details', '<div class="loading"><div class="spinner"></div></div>', { saveLabel: null });
    try {
      const u = await API.get(`/users/${uid}`);
      const progress = u.chapter_progress || [];
      delete u.chapter_progress;

      Modal.setBody(`
        <div class="kv-grid mb-4">
          ${Object.entries(u).map(([k, v]) => `
            <span class="kv-key">${esc(k)}</span>
            <span class="kv-val">${esc(JSON.stringify(v))}</span>
          `).join('')}
        </div>
        ${progress.length ? `
          <h4 style="margin-bottom:8px;font-size:13px;">Chapter Progress (${progress.length})</h4>
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
          <button class="btn btn-secondary btn-sm" onclick="Users.editUser('${uid}');Modal.close()">Edit this user</button>
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

  return { render, viewUser, editUser, deleteUser, _goPage };
})();

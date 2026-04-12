/**
 * referrals.js — Referral Codes management.
 *
 * Read + delete only (creation is done via seed script).
 *
 * API endpoints:
 *  GET    /admin/api/referral-codes
 *  DELETE /admin/api/referral-codes/{code}
 */

const Referrals = (() => {
  let _all      = [];
  let _filtered = [];

  async function render() {
    const el = document.getElementById('section-referrals');
    el.innerHTML = `
      <div class="page-header">
        <h3>Referral Codes</h3>
        <button class="btn btn-secondary btn-sm" id="ref-reload">&#8635; Reload</button>
      </div>
      <div class="filter-bar">
        <input type="text" id="ref-search" placeholder="Search code, user…" />
        <select id="ref-filter-used">
          <option value="">All</option>
          <option value="used">Used</option>
          <option value="unused">Unused</option>
        </select>
      </div>
      <div class="card">
        <div class="card-header"><h4 id="ref-count">Referral Codes</h4></div>
        <div class="table-wrap" id="ref-table">
          <div class="loading"><div class="spinner"></div> Loading…</div>
        </div>
      </div>`;

    document.getElementById('ref-reload').addEventListener('click', load);
    document.getElementById('ref-search').addEventListener('input', applyFilter);
    document.getElementById('ref-filter-used').addEventListener('change', applyFilter);
    await load();
  }

  async function load() {
    const wrap = document.getElementById('ref-table');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      _all = await API.get('/referral-codes?limit=500');
      applyFilter();
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function applyFilter() {
    const q    = (document.getElementById('ref-search')?.value || '').toLowerCase();
    const used = document.getElementById('ref-filter-used')?.value || '';

    _filtered = _all.filter(r => {
      const matchQ = !q ||
        (r._id || '').toLowerCase().includes(q) ||
        (r.owner_uid || r.created_by || '').toLowerCase().includes(q) ||
        (r.used_by || '').toLowerCase().includes(q);
      const isUsed = !!(r.used_by || r.used_count > 0 || r.is_used);
      const matchU = !used || (used === 'used' ? isUsed : !isUsed);
      return matchQ && matchU;
    });
    renderTable();
  }

  function renderTable() {
    const wrap = document.getElementById('ref-table');
    if (!wrap) return;
    const cnt = document.getElementById('ref-count');
    if (cnt) cnt.textContent = `Referral Codes (${_filtered.length} / ${_all.length})`;

    if (!_filtered.length) {
      wrap.innerHTML = '<div class="empty-state"><p>No referral codes found.</p></div>';
      return;
    }

    wrap.innerHTML = `
      <table>
        <thead>
          <tr>
            <th>Code</th>
            <th>Owner UID</th>
            <th>Used By</th>
            <th>Used Count</th>
            <th>Status</th>
            <th>Created</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          ${_filtered.map(r => {
            const isUsed = !!(r.used_by || r.used_count > 0 || r.is_used);
            return `
            <tr>
              <td><code>${esc(r._id)}</code></td>
              <td class="td-truncate text-sm">${esc(r.owner_uid || r.created_by || '—')}</td>
              <td class="td-truncate text-sm">${esc(r.used_by || '—')}</td>
              <td>${r.used_count ?? (isUsed ? 1 : 0)}</td>
              <td>${isUsed
                ? '<span class="badge badge-yellow">Used</span>'
                : '<span class="badge badge-green">Available</span>'}</td>
              <td class="text-sm">${_fmtDate(r.created_at)}</td>
              <td>
                <button class="btn btn-danger btn-sm" onclick="Referrals.del('${esc(r._id)}')">Delete</button>
              </td>
            </tr>`;
          }).join('')}
        </tbody>
      </table>`;
  }

  async function del(code) {
    if (!confirm(`Delete referral code "${code}"?`)) return;
    try {
      await API.del(`/referral-codes/${encodeURIComponent(code)}`);
      Toast.success('Referral code deleted');
      await load();
    } catch (err) { Toast.error(err.message); }
  }

  function _fmtDate(v) {
    if (!v) return '—';
    try {
      const d = typeof v === 'object' && v._seconds
        ? new Date(v._seconds * 1000)
        : new Date(v);
      return d.toLocaleDateString('en-IN');
    } catch (_) { return String(v); }
  }

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render, del };
})();

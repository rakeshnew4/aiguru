/**
 * payments.js — Payment Intents, Receipts, and Webhooks viewer.
 *
 * Read-only section (admin can view, not mutate payments).
 * Three tabs: Intents | Receipts | Webhooks
 *
 * API endpoints:
 *  GET /admin/api/payments/intents
 *  GET /admin/api/payments/receipts
 *  GET /admin/api/payments/webhooks
 */

const Payments = (() => {
  let _activeTab = 'intents';
  let _data = { intents: null, receipts: null, webhooks: null };
  let _search = '';

  async function render() {
    const el = document.getElementById('section-payments');
    el.innerHTML = `
      <div class="page-header">
        <h3>Payments</h3>
        <button class="btn btn-secondary btn-sm" id="pay-reload">&#8635; Reload</button>
      </div>

      <div class="tabs">
        <button class="tab-btn active" data-tab="intents">Intents</button>
        <button class="tab-btn" data-tab="receipts">Receipts</button>
        <button class="tab-btn" data-tab="webhooks">Webhooks</button>
      </div>

      <div class="filter-bar">
        <input type="text" id="pay-search" placeholder="Search…" />
      </div>

      <div id="tab-intents"  class="tab-panel active"></div>
      <div id="tab-receipts" class="tab-panel"></div>
      <div id="tab-webhooks" class="tab-panel"></div>`;

    document.getElementById('pay-reload').addEventListener('click', () => {
      _data[_activeTab] = null;
      loadTab(_activeTab);
    });
    document.getElementById('pay-search').addEventListener('input', e => {
      _search = e.target.value.toLowerCase();
      renderTab(_activeTab);
    });
    document.querySelectorAll('.tab-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
        document.getElementById(`tab-${btn.dataset.tab}`).classList.add('active');
        _activeTab = btn.dataset.tab;
        loadTab(_activeTab);
      });
    });

    await loadTab('intents');
  }

  async function loadTab(tab) {
    const panel = document.getElementById(`tab-${tab}`);
    if (!panel) return;
    if (_data[tab] !== null) { renderTab(tab); return; }

    panel.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      _data[tab] = await API.get(`/payments/${tab}`);
      renderTab(tab);
    } catch (err) {
      panel.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function renderTab(tab) {
    const panel = document.getElementById(`tab-${tab}`);
    if (!panel || !_data[tab]) return;

    let rows = _data[tab];
    if (_search) {
      rows = rows.filter(r => JSON.stringify(r).toLowerCase().includes(_search));
    }

    if (!rows.length) {
      panel.innerHTML = '<div class="empty-state"><p>No records found.</p></div>';
      return;
    }

    if (tab === 'intents')  panel.innerHTML = _renderIntents(rows);
    if (tab === 'receipts') panel.innerHTML = _renderReceipts(rows);
    if (tab === 'webhooks') panel.innerHTML = _renderWebhooks(rows);
  }

  function _renderIntents(rows) {
    return `
      <div class="card">
        <div class="card-header"><h4>Payment Intents (${rows.length})</h4></div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>ID</th><th>User</th><th>Amount</th><th>Currency</th>
                <th>Status</th><th>Plan</th><th>Created</th><th>Details</th>
              </tr>
            </thead>
            <tbody>
              ${rows.map(r => `
                <tr>
                  <td class="text-muted text-sm td-truncate">${r._id}</td>
                  <td class="td-truncate">${esc(r.user_id || r.uid || '—')}</td>
                  <td>${r.amount != null ? '₹' + r.amount : '—'}</td>
                  <td>${esc(r.currency || 'INR')}</td>
                  <td>${_statusBadge(r.status)}</td>
                  <td>${esc(r.plan_id || '—')}</td>
                  <td class="text-sm">${_fmtDate(r.created_at || r.timestamp)}</td>
                  <td><button class="btn btn-ghost btn-sm" onclick="Payments.viewRecord(${JSON.stringify(JSON.stringify(r))})">View</button></td>
                </tr>`).join('')}
            </tbody>
          </table>
        </div>
      </div>`;
  }

  function _renderReceipts(rows) {
    return `
      <div class="card">
        <div class="card-header"><h4>Payment Receipts (${rows.length})</h4></div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>ID</th><th>User</th><th>Amount</th>
                <th>Status</th><th>Plan</th><th>Date</th><th>Details</th>
              </tr>
            </thead>
            <tbody>
              ${rows.map(r => `
                <tr>
                  <td class="text-muted text-sm td-truncate">${r._id}</td>
                  <td class="td-truncate">${esc(r.user_id || r.uid || '—')}</td>
                  <td>${r.amount != null ? '₹' + r.amount : '—'}</td>
                  <td>${_statusBadge(r.status || r.payment_status)}</td>
                  <td>${esc(r.plan_id || '—')}</td>
                  <td class="text-sm">${_fmtDate(r.created_at || r.paid_at)}</td>
                  <td><button class="btn btn-ghost btn-sm" onclick="Payments.viewRecord(${JSON.stringify(JSON.stringify(r))})">View</button></td>
                </tr>`).join('')}
            </tbody>
          </table>
        </div>
      </div>`;
  }

  function _renderWebhooks(rows) {
    return `
      <div class="card">
        <div class="card-header"><h4>Webhooks (${rows.length})</h4></div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>ID</th><th>Event</th><th>Status</th><th>Date</th><th>Details</th></tr></thead>
            <tbody>
              ${rows.map(r => `
                <tr>
                  <td class="text-muted text-sm td-truncate">${r._id}</td>
                  <td>${esc(r.event || r.event_type || '—')}</td>
                  <td>${_statusBadge(r.status)}</td>
                  <td class="text-sm">${_fmtDate(r.created_at || r.received_at)}</td>
                  <td><button class="btn btn-ghost btn-sm" onclick="Payments.viewRecord(${JSON.stringify(JSON.stringify(r))})">View</button></td>
                </tr>`).join('')}
            </tbody>
          </table>
        </div>
      </div>`;
  }

  function viewRecord(jsonStr) {
    let data;
    try { data = JSON.parse(jsonStr); } catch (_) { data = {}; }
    Modal.open('Payment Record', `
      <pre class="json-view">${esc(JSON.stringify(data, null, 2))}</pre>
    `, { saveLabel: null });
  }

  function _statusBadge(s) {
    if (!s) return '—';
    const cls = {
      success:'badge-green', completed:'badge-green', paid:'badge-green',
      pending:'badge-yellow', created:'badge-yellow',
      failed:'badge-red', cancelled:'badge-red', expired:'badge-red',
    }[String(s).toLowerCase()] || 'badge-gray';
    return `<span class="badge ${cls}">${esc(s)}</span>`;
  }

  function _fmtDate(v) {
    if (!v) return '—';
    try {
      const d = typeof v === 'object' && v._seconds
        ? new Date(v._seconds * 1000)
        : new Date(v);
      return d.toLocaleString('en-IN', { dateStyle:'short', timeStyle:'short' });
    } catch (_) { return String(v); }
  }

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render, viewRecord };
})();

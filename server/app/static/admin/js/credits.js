/**
 * credits.js — Credit Packs management.
 *
 * Manages credit_topups collection (credit packs that users can purchase):
 *  - name, credits, bonus_credits, price_inr, is_active, display_order
 *
 * API endpoints:
 *  GET/POST /admin/api/credit-topups
 *  PUT/DELETE /admin/api/credit-topups/{id}
 */

const Credits = (() => {
  let _all = [];

  const FIELDS = [
    { key:'name',            label:'Pack Name',         type:'text',   required:true  },
    { key:'credits',         label:'Credits',           type:'number', required:true  },
    { key:'bonus_credits',   label:'Bonus Credits',     type:'number', required:false },
    { key:'price_inr',       label:'Price (₹)',         type:'number', required:false },
    { key:'display_order',   label:'Display Order',     type:'number', required:false },
    { key:'is_active',       label:'Active',            type:'checkbox',required:false },
  ];

  async function render() {
    const el = document.getElementById('section-credits');
    el.innerHTML = `
      <div class="page-header">
        <h3>Credit Packs</h3>
        <div class="flex gap-2">
          <button class="btn btn-secondary btn-sm" id="credits-reload">&#8635; Reload</button>
          <button class="btn btn-primary btn-sm" onclick="Credits.openCreate()">+ Add Pack</button>
        </div>
      </div>
      <div class="card">
        <div class="card-header"><h4 id="credits-count">Credit Packs</h4></div>
        <div class="table-wrap" id="credits-table"></div>
      </div>`;

    document.getElementById('credits-reload').addEventListener('click', load);
    await load();
  }

  async function load() {
    const wrap = document.getElementById('credits-table');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      _all = await API.get('/credit-topups_new');
      renderTable();
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function renderTable() {
    const wrap = document.getElementById('credits-table');
    if (!wrap) return;
    const countEl = document.getElementById('credits-count');
    if (countEl) countEl.textContent = `Credit Packs (${_all.length})`;

    if (!_all.length) { wrap.innerHTML = '<div class="empty-state"><p>No credit packs found.</p></div>'; return; }

    wrap.innerHTML = `
      <table>
        <thead>
          <tr>
            <th>ID</th><th>Name</th><th>Credits</th><th>Bonus</th><th>Price (₹)</th><th>Order</th><th>Active</th><th>Actions</th>
          </tr>
        </thead>
        <tbody>
          ${_all.map(p => `
            <tr>
              <td class="text-muted text-sm td-truncate">${p._id}</td>
              <td>${esc(p.name || '—')}</td>
              <td style="font-weight:bold;color:#3b82f6;">${p.credits || '—'}</td>
              <td style="color:#22c55e;">${p.bonus_credits ? '+' + p.bonus_credits : '—'}</td>
              <td>${p.price_inr != null ? '₹' + p.price_inr : '—'}</td>
              <td>${p.display_order != null ? p.display_order : '—'}</td>
              <td>${p.is_active !== false ? '<span class="badge badge-green">Yes</span>' : '<span class="badge badge-gray">No</span>'}</td>
              <td>
                <div class="td-actions">
                  <button class="btn btn-secondary btn-sm" onclick="Credits.openEdit('${p._id}')">Edit</button>
                  <button class="btn btn-danger btn-sm" onclick="Credits.del('${p._id}')">Delete</button>
                </div>
              </td>
            </tr>`).join('')}
        </tbody>
      </table>`;
  }

  function _formHtml(data = {}) {
    return FIELDS.map(f => {
      const val = data[f.key] !== undefined ? data[f.key] : '';
      if (f.type === 'checkbox') {
        return `<div class="form-group" style="flex-direction:row;align-items:center;gap:8px;">
          <input type="checkbox" id="f_${f.key}" ${val ? 'checked' : ''} style="width:16px;height:16px;">
          <label for="f_${f.key}">${f.label}</label>
        </div>`;
      }
      return `<div class="form-group">
        <label>${f.label}${f.required?' *':''}</label>
        <input type="${f.type}" id="f_${f.key}" value="${esc(String(val))}">
      </div>`;
    }).join('');
  }

  function _collectForm() {
    const obj = {};
    FIELDS.forEach(f => {
      const el = document.getElementById(`f_${f.key}`);
      if (!el) return;
      if (f.type === 'checkbox') obj[f.key] = el.checked;
      else if (f.type === 'number') obj[f.key] = el.value !== '' ? Number(el.value) : null;
      else obj[f.key] = el.value;
    });
    return obj;
  }

  function openCreate() {
    Modal.open('Add Credit Pack', _formHtml(), {
      onSave: async () => {
        const payload = _collectForm();
        if (!payload.name || !payload.credits) { Toast.error('Name and Credits are required'); return; }
        try {
          await API.post('/credit-topups_new', payload);
          Toast.success('Credit pack created'); Modal.close(); await load();
        } catch (err) { Toast.error(err.message); }
      },
    });
  }

  function openEdit(id) {
    const item = _all.find(p => p._id === id);
    if (!item) return;
    Modal.open('Edit Credit Pack', _formHtml(item), {
      onSave: async () => {
        try {
          await API.put(`/credit-topups_new/${id}`, _collectForm());
          Toast.success('Credit pack updated'); Modal.close(); await load();
        } catch (err) { Toast.error(err.message); }
      },
    });
  }

  async function del(id) {
    if (!confirm(`Delete credit pack ${id}?`)) return;
    try {
      await API.del(`/credit-topups_new/${id}`);
      Toast.success('Deleted'); await load();
    } catch (err) { Toast.error(err.message); }
  }

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render, openCreate, openEdit, del };
})();

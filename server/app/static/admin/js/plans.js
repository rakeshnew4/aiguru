/**
 * plans.js — Plans & Schools management.
 *
 * Two independent sections bundled together because they share the same
 * generic CRUD pattern.
 *
 * API endpoints:
 *  GET/POST /admin/api/plans
 *  PUT/DELETE /admin/api/plans/{id}
 *  GET/POST /admin/api/schools
 *  PUT/DELETE /admin/api/schools/{id}
 */

/* ══════════════════════════════════════════════════════════════
   PLANS
══════════════════════════════════════════════════════════════ */
const Plans = (() => {
  let _all = [];

  const FIELDS = [
    { key:'name',            label:'Plan Name',         type:'text',   required:true },
    { key:'display_name',    label:'Display Name',      type:'text',   required:false },
    { key:'price',           label:'Price (INR)',        type:'number', required:false },
    { key:'duration_days',   label:'Duration (days)',   type:'number', required:false },
    { key:'daily_quota',     label:'Daily Quota',       type:'number', required:false },
    { key:'features',        label:'Features (JSON)',   type:'json',   required:false },
    { key:'is_active',       label:'Active',            type:'checkbox',required:false},
  ];

  async function render() {
    const el = document.getElementById('section-plans');
    el.innerHTML = `
      <div class="page-header">
        <h3>Plans</h3>
        <div class="flex gap-2">
          <button class="btn btn-secondary btn-sm" id="plans-reload">&#8635; Reload</button>
          <button class="btn btn-primary btn-sm" onclick="Plans.openCreate()">+ Add Plan</button>
        </div>
      </div>
      <div class="card">
        <div class="card-header"><h4 id="plans-count">Plans</h4></div>
        <div class="table-wrap" id="plans-table"></div>
      </div>`;

    document.getElementById('plans-reload').addEventListener('click', load);
    await load();
  }

  async function load() {
    const wrap = document.getElementById('plans-table');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      _all = await API.get('/plans');
      renderTable();
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function renderTable() {
    const wrap = document.getElementById('plans-table');
    if (!wrap) return;
    const countEl = document.getElementById('plans-count');
    if (countEl) countEl.textContent = `Plans (${_all.length})`;

    if (!_all.length) { wrap.innerHTML = '<div class="empty-state"><p>No plans found.</p></div>'; return; }

    wrap.innerHTML = `
      <table>
        <thead>
          <tr>
            <th>ID</th><th>Name</th><th>Display Name</th>
            <th>Price</th><th>Duration</th><th>Daily Quota</th><th>Active</th><th>Actions</th>
          </tr>
        </thead>
        <tbody>
          ${_all.map(p => `
            <tr>
              <td class="text-muted text-sm td-truncate">${p._id}</td>
              <td>${esc(p.name || '—')}</td>
              <td>${esc(p.display_name || '—')}</td>
              <td>${p.price != null ? '₹' + p.price : '—'}</td>
              <td>${p.duration_days != null ? p.duration_days + 'd' : '—'}</td>
              <td>${p.daily_quota ?? '—'}</td>
              <td>${p.is_active !== false ? '<span class="badge badge-green">Yes</span>' : '<span class="badge badge-gray">No</span>'}</td>
              <td>
                <div class="td-actions">
                  <button class="btn btn-secondary btn-sm" onclick="Plans.openEdit('${p._id}')">Edit</button>
                  <button class="btn btn-danger btn-sm" onclick="Plans.del('${p._id}')">Delete</button>
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
      if (f.type === 'json') {
        const jsonVal = typeof val === 'object' ? JSON.stringify(val, null, 2) : String(val || '[]');
        return `<div class="form-group">
          <label>${f.label}</label>
          <textarea class="json-editor" id="f_${f.key}" style="min-height:80px;">${jsonVal}</textarea>
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
      else if (f.type === 'json') {
        try { obj[f.key] = JSON.parse(el.value); } catch (_) { obj[f.key] = el.value; }
      } else obj[f.key] = el.value;
    });
    return obj;
  }

  function openCreate() {
    Modal.open('Add Plan', _formHtml(), {
      onSave: async () => {
        const payload = _collectForm();
        if (!payload.name) { Toast.error('Name is required'); return; }
        try {
          await API.post('/plans', payload);
          Toast.success('Plan created'); Modal.close(); await load();
        } catch (err) { Toast.error(err.message); }
      },
    });
  }

  function openEdit(id) {
    const item = _all.find(p => p._id === id);
    if (!item) return;
    Modal.open('Edit Plan', _formHtml(item), {
      onSave: async () => {
        try {
          await API.put(`/plans/${id}`, _collectForm());
          Toast.success('Plan updated'); Modal.close(); await load();
        } catch (err) { Toast.error(err.message); }
      },
    });
  }

  async function del(id) {
    if (!confirm(`Delete plan ${id}?`)) return;
    try {
      await API.del(`/plans/${id}`);
      Toast.success('Deleted'); await load();
    } catch (err) { Toast.error(err.message); }
  }

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render, openCreate, openEdit, del };
})();


/* ══════════════════════════════════════════════════════════════
   SCHOOLS
══════════════════════════════════════════════════════════════ */
const Schools = (() => {
  let _all = [];

  const FIELDS = [
    { key:'name',      label:'School Name', type:'text',   required:true  },
    { key:'city',      label:'City',        type:'text',   required:false },
    { key:'state',     label:'State',       type:'text',   required:false },
    { key:'code',      label:'Code',        type:'text',   required:false },
    { key:'email',     label:'Email',       type:'text',   required:false },
    { key:'phone',     label:'Phone',       type:'text',   required:false },
    { key:'is_active', label:'Active',      type:'checkbox',required:false},
  ];

  async function render() {
    const el = document.getElementById('section-schools');
    el.innerHTML = `
      <div class="page-header">
        <h3>Schools</h3>
        <div class="flex gap-2">
          <button class="btn btn-secondary btn-sm" id="schools-reload">&#8635; Reload</button>
          <button class="btn btn-primary btn-sm" onclick="Schools.openCreate()">+ Add School</button>
        </div>
      </div>
      <div class="filter-bar">
        <input type="text" id="schools-search" placeholder="Search schools…" />
      </div>
      <div class="card">
        <div class="card-header"><h4 id="schools-count">Schools</h4></div>
        <div class="table-wrap" id="schools-table"></div>
      </div>`;

    document.getElementById('schools-reload').addEventListener('click', load);
    document.getElementById('schools-search').addEventListener('input', renderTable);
    await load();
  }

  async function load() {
    const wrap = document.getElementById('schools-table');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      _all = await API.get('/schools');
      renderTable();
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function renderTable() {
    const wrap = document.getElementById('schools-table');
    const q    = (document.getElementById('schools-search')?.value || '').toLowerCase();
    if (!wrap) return;

    const rows = _all.filter(s =>
      !q || (s.name||'').toLowerCase().includes(q) ||
             (s.city||'').toLowerCase().includes(q) ||
             (s.code||'').toLowerCase().includes(q)
    );
    const countEl = document.getElementById('schools-count');
    if (countEl) countEl.textContent = `Schools (${rows.length})`;

    if (!rows.length) { wrap.innerHTML = '<div class="empty-state"><p>No schools found.</p></div>'; return; }

    wrap.innerHTML = `
      <table>
        <thead><tr><th>ID</th><th>Name</th><th>Code</th><th>City</th><th>State</th><th>Active</th><th>Actions</th></tr></thead>
        <tbody>
          ${rows.map(s => `
            <tr>
              <td class="text-muted text-sm td-truncate">${s._id}</td>
              <td>${esc(s.name || '—')}</td>
              <td>${esc(s.code || '—')}</td>
              <td>${esc(s.city || '—')}</td>
              <td>${esc(s.state || '—')}</td>
              <td>${s.is_active !== false ? '<span class="badge badge-green">Yes</span>' : '<span class="badge badge-gray">No</span>'}</td>
              <td>
                <div class="td-actions">
                  <button class="btn btn-secondary btn-sm" onclick="Schools.openEdit('${s._id}')">Edit</button>
                  <button class="btn btn-danger btn-sm" onclick="Schools.del('${s._id}')">Delete</button>
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
      else obj[f.key] = el.value;
    });
    return obj;
  }

  function openCreate() {
    Modal.open('Add School', _formHtml(), {
      onSave: async () => {
        const p = _collectForm();
        if (!p.name) { Toast.error('Name required'); return; }
        try {
          await API.post('/schools', p);
          Toast.success('School created'); Modal.close(); await load();
        } catch (err) { Toast.error(err.message); }
      },
    });
  }

  function openEdit(id) {
    const item = _all.find(s => s._id === id);
    if (!item) return;
    Modal.open('Edit School', _formHtml(item), {
      onSave: async () => {
        try {
          await API.put(`/schools/${id}`, _collectForm());
          Toast.success('School updated'); Modal.close(); await load();
        } catch (err) { Toast.error(err.message); }
      },
    });
  }

  async function del(id) {
    if (!confirm(`Delete school ${id}?`)) return;
    try {
      await API.del(`/schools/${id}`);
      Toast.success('Deleted'); await load();
    } catch (err) { Toast.error(err.message); }
  }

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render, openCreate, openEdit, del };
})();

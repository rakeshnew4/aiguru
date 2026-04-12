/**
 * config.js — App Config, Offers, and Notifications.
 *
 * Three independent sections:
 *  - AppConfig:     GET/PUT /admin/api/app-config
 *  - Offers:        GET/POST/PUT/DELETE /admin/api/offers
 *  - Notifications: GET/POST/PUT/DELETE /admin/api/notifications
 */

/* ══════════════════════════════════════════════════════════════
   APP CONFIG
══════════════════════════════════════════════════════════════ */
const AppConfig = (() => {

  async function render() {
    const el = document.getElementById('section-appconfig');
    el.innerHTML = `
      <div class="page-header">
        <h3>App Config</h3>
        <button class="btn btn-secondary btn-sm" id="appconfig-reload">&#8635; Reload</button>
      </div>
      <div id="appconfig-content">
        <div class="loading"><div class="spinner"></div> Loading…</div>
      </div>`;
    document.getElementById('appconfig-reload').addEventListener('click', load);
    await load();
  }

  async function load() {
    const container = document.getElementById('appconfig-content');
    if (!container) return;
    container.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      const data = await API.get('/app-config');
      container.innerHTML = `
        <div class="card">
          <div class="card-header">
            <h4>App Config <code style="font-size:11px;color:var(--c-text-2);">updates/app_config</code></h4>
          </div>
          <div class="card-body">
            <p class="text-sm text-muted mb-4">
              Stored in Firestore <code>updates/app_config</code>.
              Controls force-update flags, minimum app version, feature toggles, etc.
            </p>

            <!-- Common fields quick-edit -->
            <div class="form-row">
              <div class="form-group">
                <label>Min Version (Android)</label>
                <input type="text" id="ac_min_version_android" value="${esc(data.min_version_android || data.minimum_version || '')}">
              </div>
              <div class="form-group">
                <label>Min Version (iOS)</label>
                <input type="text" id="ac_min_version_ios" value="${esc(data.min_version_ios || '')}">
              </div>
              <div class="form-group">
                <label>Latest Version</label>
                <input type="text" id="ac_latest_version" value="${esc(data.latest_version || '')}">
              </div>
              <div class="form-group">
                <label>Force Update</label>
                <select id="ac_force_update">
                  <option value="false" ${!data.force_update ? 'selected' : ''}>No</option>
                  <option value="true"  ${data.force_update  ? 'selected' : ''}>Yes</option>
                </select>
              </div>
            </div>

            <div class="form-group">
              <label>Update Message</label>
              <input type="text" id="ac_update_message" value="${esc(data.update_message || '')}">
            </div>
            <div class="form-group">
              <label>Maintenance Mode</label>
              <select id="ac_maintenance">
                <option value="false" ${!data.maintenance_mode ? 'selected' : ''}>Off</option>
                <option value="true"  ${data.maintenance_mode  ? 'selected' : ''}>On</option>
              </select>
            </div>

            <hr style="margin:16px 0;border-color:var(--c-border);">
            <h4 style="margin-bottom:12px;font-size:13px;">Full Document (JSON)</h4>
            <div class="form-group">
              <textarea class="json-editor" id="appconfig-json" style="min-height:200px;">${esc(JSON.stringify(data, null, 2))}</textarea>
            </div>

            <div class="flex gap-2 mt-4">
              <button class="btn btn-primary" id="appconfig-save">Save Changes</button>
              <button class="btn btn-secondary" id="appconfig-format">Format JSON</button>
            </div>
          </div>
        </div>`;

      document.getElementById('appconfig-save').addEventListener('click', () => saveConfig(data));
      document.getElementById('appconfig-format').addEventListener('click', () => {
        const ta = document.getElementById('appconfig-json');
        try { ta.value = JSON.stringify(JSON.parse(ta.value), null, 2); }
        catch (_) { Toast.error('Invalid JSON'); }
      });

      // Sync quick-fields → json textarea on change
      ['ac_min_version_android','ac_min_version_ios','ac_latest_version',
       'ac_force_update','ac_update_message','ac_maintenance'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.addEventListener('change', syncJson);
      });
    } catch (err) {
      container.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function syncJson() {
    const ta = document.getElementById('appconfig-json');
    try {
      const obj = JSON.parse(ta.value);
      const v = id => document.getElementById(id)?.value;
      obj.min_version_android = v('ac_min_version_android') || obj.min_version_android;
      obj.min_version_ios     = v('ac_min_version_ios')     || obj.min_version_ios;
      obj.latest_version      = v('ac_latest_version')      || obj.latest_version;
      obj.force_update        = document.getElementById('ac_force_update')?.value === 'true';
      obj.update_message      = v('ac_update_message')      || obj.update_message;
      obj.maintenance_mode    = document.getElementById('ac_maintenance')?.value === 'true';
      ta.value = JSON.stringify(obj, null, 2);
    } catch (_) { /* leave as is */ }
  }

  async function saveConfig() {
    const raw = document.getElementById('appconfig-json')?.value;
    let payload;
    try { payload = JSON.parse(raw); } catch (_) { Toast.error('Invalid JSON'); return; }
    const btn = document.getElementById('appconfig-save');
    btn.disabled = true; btn.textContent = 'Saving…';
    try {
      await API.put('/app-config', payload);
      Toast.success('App config saved');
    } catch (err) { Toast.error(err.message); }
    finally { btn.disabled = false; btn.textContent = 'Save Changes'; }
  }

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render };
})();


/* ══════════════════════════════════════════════════════════════
   OFFERS
══════════════════════════════════════════════════════════════ */
const Offers = (() => {
  let _all = [];

  const FIELDS = [
    { key:'title',       label:'Title',         type:'text',   required:true  },
    { key:'description', label:'Description',   type:'text',   required:false },
    { key:'code',        label:'Promo Code',     type:'text',   required:false },
    { key:'discount',    label:'Discount (%)',   type:'number', required:false },
    { key:'plan_id',     label:'Plan ID',        type:'text',   required:false },
    { key:'valid_till',  label:'Valid Till',     type:'text',   required:false },
    { key:'is_active',   label:'Active',         type:'checkbox',required:false},
  ];

  async function render() {
    const el = document.getElementById('section-offers');
    el.innerHTML = `
      <div class="page-header">
        <h3>Offers</h3>
        <div class="flex gap-2">
          <button class="btn btn-secondary btn-sm" id="offers-reload">&#8635; Reload</button>
          <button class="btn btn-primary btn-sm" onclick="Offers.openCreate()">+ Add Offer</button>
        </div>
      </div>
      <div class="card">
        <div class="card-header"><h4 id="offers-count">Offers</h4></div>
        <div class="table-wrap" id="offers-table"></div>
      </div>`;
    document.getElementById('offers-reload').addEventListener('click', load);
    await load();
  }

  async function load() {
    const wrap = document.getElementById('offers-table');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      _all = await API.get('/offers');
      renderTable();
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function renderTable() {
    const wrap = document.getElementById('offers-table');
    if (!wrap) return;
    const cnt = document.getElementById('offers-count');
    if (cnt) cnt.textContent = `Offers (${_all.length})`;

    if (!_all.length) { wrap.innerHTML = '<div class="empty-state"><p>No offers found.</p></div>'; return; }
    wrap.innerHTML = `
      <table>
        <thead><tr><th>ID</th><th>Title</th><th>Code</th><th>Discount</th><th>Plan</th><th>Active</th><th>Actions</th></tr></thead>
        <tbody>
          ${_all.map(o => `
            <tr>
              <td class="text-muted text-sm td-truncate">${o._id}</td>
              <td>${esc(o.title||'—')}</td>
              <td><code>${esc(o.code||'—')}</code></td>
              <td>${o.discount != null ? o.discount + '%' : '—'}</td>
              <td>${esc(o.plan_id||'—')}</td>
              <td>${o.is_active !== false ? '<span class="badge badge-green">Yes</span>' : '<span class="badge badge-gray">No</span>'}</td>
              <td>
                <div class="td-actions">
                  <button class="btn btn-secondary btn-sm" onclick="Offers.openEdit('${o._id}')">Edit</button>
                  <button class="btn btn-danger btn-sm" onclick="Offers.del('${o._id}')">Delete</button>
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
    Modal.open('Add Offer', _formHtml(), {
      onSave: async () => {
        const p = _collectForm();
        if (!p.title) { Toast.error('Title required'); return; }
        try { await API.post('/offers', p); Toast.success('Offer created'); Modal.close(); await load(); }
        catch (err) { Toast.error(err.message); }
      },
    });
  }

  function openEdit(id) {
    const item = _all.find(o => o._id === id);
    if (!item) return;
    Modal.open('Edit Offer', _formHtml(item), {
      onSave: async () => {
        try { await API.put(`/offers/${id}`, _collectForm()); Toast.success('Updated'); Modal.close(); await load(); }
        catch (err) { Toast.error(err.message); }
      },
    });
  }

  async function del(id) {
    if (!confirm(`Delete offer ${id}?`)) return;
    try { await API.del(`/offers/${id}`); Toast.success('Deleted'); await load(); }
    catch (err) { Toast.error(err.message); }
  }

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render, openCreate, openEdit, del };
})();


/* ══════════════════════════════════════════════════════════════
   NOTIFICATIONS
══════════════════════════════════════════════════════════════ */
const Notifications = (() => {
  let _all = [];

  const FIELDS = [
    { key:'title',      label:'Title',    type:'text',   required:true  },
    { key:'body',       label:'Body',     type:'textarea',required:false },
    { key:'type',       label:'Type',     type:'text',   required:false },
    { key:'target',     label:'Target',   type:'text',   required:false },
    { key:'image_url',  label:'Image URL',type:'text',   required:false },
    { key:'action_url', label:'Action URL',type:'text',  required:false },
    { key:'is_active',  label:'Active',   type:'checkbox',required:false},
  ];

  async function render() {
    const el = document.getElementById('section-notifications');
    el.innerHTML = `
      <div class="page-header">
        <h3>Notifications</h3>
        <div class="flex gap-2">
          <button class="btn btn-secondary btn-sm" id="notif-reload">&#8635; Reload</button>
          <button class="btn btn-primary btn-sm" onclick="Notifications.openCreate()">+ Add Notification</button>
        </div>
      </div>
      <div class="card">
        <div class="card-header"><h4 id="notif-count">Notifications</h4></div>
        <div class="table-wrap" id="notif-table"></div>
      </div>`;
    document.getElementById('notif-reload').addEventListener('click', load);
    await load();
  }

  async function load() {
    const wrap = document.getElementById('notif-table');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      _all = await API.get('/notifications');
      renderTable();
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function renderTable() {
    const wrap = document.getElementById('notif-table');
    if (!wrap) return;
    const cnt = document.getElementById('notif-count');
    if (cnt) cnt.textContent = `Notifications (${_all.length})`;

    if (!_all.length) { wrap.innerHTML = '<div class="empty-state"><p>No notifications found.</p></div>'; return; }
    wrap.innerHTML = `
      <table>
        <thead><tr><th>ID</th><th>Title</th><th>Type</th><th>Target</th><th>Active</th><th>Actions</th></tr></thead>
        <tbody>
          ${_all.map(n => `
            <tr>
              <td class="text-muted text-sm td-truncate">${n._id}</td>
              <td>${esc(n.title||'—')}</td>
              <td>${n.type ? `<span class="badge badge-blue">${esc(n.type)}</span>` : '—'}</td>
              <td>${esc(n.target||'all')}</td>
              <td>${n.is_active !== false ? '<span class="badge badge-green">Yes</span>' : '<span class="badge badge-gray">No</span>'}</td>
              <td>
                <div class="td-actions">
                  <button class="btn btn-secondary btn-sm" onclick="Notifications.openEdit('${n._id}')">Edit</button>
                  <button class="btn btn-danger btn-sm" onclick="Notifications.del('${n._id}')">Delete</button>
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
      if (f.type === 'textarea') {
        return `<div class="form-group">
          <label>${f.label}</label>
          <textarea id="f_${f.key}" style="min-height:68px;">${esc(String(val))}</textarea>
        </div>`;
      }
      return `<div class="form-group">
        <label>${f.label}${f.required?' *':''}</label>
        <input type="text" id="f_${f.key}" value="${esc(String(val))}">
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
    Modal.open('Add Notification', _formHtml(), {
      onSave: async () => {
        const p = _collectForm();
        if (!p.title) { Toast.error('Title required'); return; }
        try { await API.post('/notifications', p); Toast.success('Created'); Modal.close(); await load(); }
        catch (err) { Toast.error(err.message); }
      },
    });
  }

  function openEdit(id) {
    const item = _all.find(n => n._id === id);
    if (!item) return;
    Modal.open('Edit Notification', _formHtml(item), {
      onSave: async () => {
        try { await API.put(`/notifications/${id}`, _collectForm()); Toast.success('Updated'); Modal.close(); await load(); }
        catch (err) { Toast.error(err.message); }
      },
    });
  }

  async function del(id) {
    if (!confirm(`Delete notification ${id}?`)) return;
    try { await API.del(`/notifications/${id}`); Toast.success('Deleted'); await load(); }
    catch (err) { Toast.error(err.message); }
  }

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render, openCreate, openEdit, del };
})();

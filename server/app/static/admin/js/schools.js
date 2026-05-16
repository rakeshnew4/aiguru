/**
 * schools.js — Schools management section.
 *
 * Full CRUD: list, view (with student count), create, edit, delete.
 *
 * API endpoints:
 *  GET    /admin/api/schools
 *  POST   /admin/api/schools
 *  PUT    /admin/api/schools/{school_id}
 *  DELETE /admin/api/schools/{school_id}
 */

const Schools = (() => {
  let _all      = [];
  let _filtered = [];

  const FIELDS = [
    { key: 'name',              label: 'School Name',     type: 'text',     required: true  },
    { key: 'shortName',         label: 'Short Name',      type: 'text',     required: false },
    { key: 'city',              label: 'City',            type: 'text',     required: false },
    { key: 'code',              label: 'School Code',     type: 'text',     required: false },
    { key: 'subscription_plan', label: 'Plan Type',       type: 'text',     required: false },
    { key: 'max_students',      label: 'Max Students',    type: 'number',   required: false },
    { key: 'logo_url',          label: 'Logo URL',        type: 'text',     required: false },
    { key: 'primary_color',     label: 'Primary Color',   type: 'text',     required: false },
    { key: 'is_active',         label: 'Active',          type: 'checkbox', required: false },
  ];

  // ── Shell ───────────────────────────────────────────────────────────────────

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
        <input type="text" id="schools-search" placeholder="Search by name, city, code…" />
        <select id="schools-filter-active">
          <option value="">All status</option>
          <option value="active">Active</option>
          <option value="inactive">Inactive</option>
        </select>
      </div>

      <div class="card">
        <div class="card-header">
          <h4 id="schools-count">Schools</h4>
        </div>
        <div class="table-wrap" id="schools-table">
          <div class="loading"><div class="spinner"></div> Loading…</div>
        </div>
      </div>`;

    document.getElementById('schools-reload').addEventListener('click', load);
    document.getElementById('schools-search').addEventListener('input', _applyFilter);
    document.getElementById('schools-filter-active').addEventListener('change', _applyFilter);
    await load();
  }

  // ── Load ────────────────────────────────────────────────────────────────────

  async function load() {
    const wrap = document.getElementById('schools-table');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      _all = await API.get('/schools');
      _filtered = [..._all];
      _applyFilter();
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function _applyFilter() {
    const q     = (document.getElementById('schools-search')?.value || '').toLowerCase();
    const activeF = document.getElementById('schools-filter-active')?.value || '';

    _filtered = _all.filter(s => {
      const matchQ = !q
        || (s.name || '').toLowerCase().includes(q)
        || (s.city || '').toLowerCase().includes(q)
        || (s.code || '').toLowerCase().includes(q)
        || (s.shortName || '').toLowerCase().includes(q);
      const matchA = !activeF
        || (activeF === 'active'   &&  s.is_active)
        || (activeF === 'inactive' && !s.is_active);
      return matchQ && matchA;
    });

    _renderTable();
    const count = document.getElementById('schools-count');
    if (count) count.textContent = `Schools (${_filtered.length} of ${_all.length})`;
  }

  // ── Table ───────────────────────────────────────────────────────────────────

  function _renderTable() {
    const wrap = document.getElementById('schools-table');
    if (!wrap) return;
    if (!_filtered.length) {
      wrap.innerHTML = '<div class="empty-state"><p>No schools found.</p></div>';
      return;
    }
    wrap.innerHTML = `
      <table class="data-table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Short</th>
            <th>City</th>
            <th>Code</th>
            <th>Plan</th>
            <th>Max Students</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          ${_filtered.map(s => `
            <tr>
              <td><strong>${esc(s.name || '—')}</strong></td>
              <td>${esc(s.shortName || '—')}</td>
              <td>${esc(s.city || '—')}</td>
              <td><code>${esc(s.code || '—')}</code></td>
              <td>${esc(s.subscription_plan || s.plan_type || '—')}</td>
              <td>${s.max_students ?? '—'}</td>
              <td>
                <span class="badge ${s.is_active ? 'badge-green' : 'badge-gray'}">
                  ${s.is_active ? 'Active' : 'Inactive'}
                </span>
              </td>
              <td class="actions">
                <button class="btn btn-sm btn-secondary" onclick="Schools.openEdit('${esc(s._id || '')}')">Edit</button>
                <button class="btn btn-sm btn-danger"    onclick="Schools.confirmDelete('${esc(s._id || '')}', '${esc(s.name || '')}')">Delete</button>
              </td>
            </tr>`).join('')}
        </tbody>
      </table>`;
  }

  // ── Create ──────────────────────────────────────────────────────────────────

  function openCreate() {
    Modal.open('Add School', _formHtml(null), {
      saveLabel: 'Create',
      onSave: async () => {
        const data = _collectForm();
        if (!data.name) { Toast.error('School name is required.'); return; }
        try {
          await API.post('/schools', data);
          Modal.close();
          Toast.success('School created.');
          await load();
        } catch (err) {
          Toast.error(err.message);
        }
      },
    });
  }

  // ── Edit ────────────────────────────────────────────────────────────────────

  async function openEdit(id) {
    const school = _all.find(s => s._id === id);
    if (!school) { Toast.error('School not found.'); return; }

    Modal.open(`Edit: ${school.name}`, _formHtml(school), {
      saveLabel: 'Save',
      onSave: async () => {
        const data = _collectForm();
        try {
          await API.put(`/schools/${id}`, data);
          Modal.close();
          Toast.success('School updated.');
          await load();
        } catch (err) {
          Toast.error(err.message);
        }
      },
    });
  }

  // ── Delete ──────────────────────────────────────────────────────────────────

  function confirmDelete(id, name) {
    Modal.open('Delete School', `
      <div class="warn-box">
        <p>&#9888; Permanently delete <strong>${esc(name)}</strong>?</p>
        <p class="text-sm text-muted">This will NOT delete associated student accounts.</p>
      </div>`, {
      saveLabel: 'Delete',
      onSave: async () => {
        try {
          await API.del(`/schools/${id}`);
          Modal.close();
          Toast.success('School deleted.');
          await load();
        } catch (err) {
          Toast.error(err.message);
        }
      },
    });
  }

  // ── Form helpers ────────────────────────────────────────────────────────────

  function _formHtml(item) {
    return `<div class="form-grid">` +
      FIELDS.map(f => {
        const val = item ? (item[f.key] ?? '') : '';
        if (f.type === 'checkbox') {
          return `
            <div class="form-group">
              <label class="checkbox-label">
                <input type="checkbox" name="${f.key}" ${val ? 'checked' : ''} />
                ${f.label}
              </label>
            </div>`;
        }
        return `
          <div class="form-group">
            <label>${f.label}${f.required ? ' <span style="color:red">*</span>' : ''}</label>
            <input type="${f.type}" name="${f.key}" value="${esc(String(val))}"
              ${f.required ? 'required' : ''} />
          </div>`;
      }).join('') + `</div>`;
  }

  function _collectForm() {
    const data = {};
    FIELDS.forEach(f => {
      const el = document.querySelector(`#modal-body [name="${f.key}"]`);
      if (!el) return;
      if (f.type === 'checkbox') data[f.key] = el.checked;
      else if (f.type === 'number') data[f.key] = el.value !== '' ? Number(el.value) : null;
      else data[f.key] = el.value.trim();
    });
    return data;
  }

  // ── Utility ─────────────────────────────────────────────────────────────────

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  return { render, openCreate, openEdit, confirmDelete };
})();

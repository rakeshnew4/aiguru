/**
 * content.js — Subjects & Chapters management.
 *
 * Both sections live here because Chapters are tightly coupled to Subjects
 * (filter by subject_id).
 *
 * Subjects section: section-subjects
 * Chapters section: section-chapters
 *
 * API endpoints:
 *  GET/POST /admin/api/subjects
 *  PUT/DELETE /admin/api/subjects/{id}
 *  GET/POST /admin/api/chapters?subject_id=...
 *  PUT/DELETE /admin/api/chapters/{id}
 */

/* ══════════════════════════════════════════════════════════════
   SUBJECTS
══════════════════════════════════════════════════════════════ */
const Subjects = (() => {
  let _all = [];

  const FIELDS = [
    { key:'name',        label:'Name',        type:'text',   required:true  },
    { key:'description', label:'Description', type:'text',   required:false },
    { key:'grade',       label:'Grade',       type:'number', required:false },
    { key:'icon_url',    label:'Icon URL',    type:'text',   required:false },
    { key:'order',       label:'Order',       type:'number', required:false },
    { key:'is_active',   label:'Active',      type:'checkbox',required:false},
  ];

  async function render() {
    const el = document.getElementById('section-subjects');
    el.innerHTML = `
      <div class="page-header">
        <h3>Subjects</h3>
        <div class="flex gap-2">
          <button class="btn btn-secondary btn-sm" id="subjects-reload">&#8635; Reload</button>
          <button class="btn btn-primary btn-sm" onclick="Subjects.openCreate()">+ Add Subject</button>
        </div>
      </div>
      <div class="filter-bar">
        <input type="text" id="subjects-search" placeholder="Search subjects…" />
      </div>
      <div class="card">
        <div class="card-header"><h4 id="subjects-count">Subjects</h4></div>
        <div class="table-wrap" id="subjects-table"></div>
      </div>`;

    document.getElementById('subjects-reload').addEventListener('click', load);
    document.getElementById('subjects-search').addEventListener('input', renderTable);
    await load();
  }

  async function load() {
    const wrap = document.getElementById('subjects-table');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      _all = await API.get('/subjects');
      renderTable();
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function renderTable() {
    const wrap = document.getElementById('subjects-table');
    const q    = (document.getElementById('subjects-search')?.value || '').toLowerCase();
    if (!wrap) return;

    const rows = _all.filter(s =>
      !q || (s.name||'').toLowerCase().includes(q) || (s._id||'').toLowerCase().includes(q)
    );
    const countEl = document.getElementById('subjects-count');
    if (countEl) countEl.textContent = `Subjects (${rows.length})`;

    if (!rows.length) { wrap.innerHTML = '<div class="empty-state"><p>No subjects found.</p></div>'; return; }

    wrap.innerHTML = `
      <table>
        <thead><tr><th>ID</th><th>Name</th><th>Grade</th><th>Order</th><th>Active</th><th>Actions</th></tr></thead>
        <tbody>
          ${rows.map(s => `
            <tr>
              <td class="text-muted text-sm td-truncate">${s._id}</td>
              <td>${esc(s.name || '—')}</td>
              <td>${s.grade || '—'}</td>
              <td>${s.order ?? '—'}</td>
              <td>${s.is_active !== false ? '<span class="badge badge-green">Yes</span>' : '<span class="badge badge-gray">No</span>'}</td>
              <td>
                <div class="td-actions">
                  <button class="btn btn-secondary btn-sm" onclick="Subjects.openEdit('${s._id}')">Edit</button>
                  <button class="btn btn-danger btn-sm" onclick="Subjects.del('${s._id}')">Delete</button>
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
    Modal.open('Add Subject', _formHtml(), {
      onSave: async () => {
        const payload = _collectForm();
        try {
          await API.post('/subjects', payload);
          Toast.success('Subject created'); Modal.close(); await load();
        } catch (err) { Toast.error(err.message); }
      },
    });
  }

  function openEdit(id) {
    const subj = _all.find(s => s._id === id);
    if (!subj) return;
    Modal.open('Edit Subject', _formHtml(subj), {
      onSave: async () => {
        const payload = _collectForm();
        try {
          await API.put(`/subjects/${id}`, payload);
          Toast.success('Subject updated'); Modal.close(); await load();
        } catch (err) { Toast.error(err.message); }
      },
    });
  }

  async function del(id) {
    if (!confirm(`Delete subject ${id}?`)) return;
    try {
      await API.del(`/subjects/${id}`);
      Toast.success('Deleted'); await load();
    } catch (err) { Toast.error(err.message); }
  }

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render, openCreate, openEdit, del };
})();


/* ══════════════════════════════════════════════════════════════
   CHAPTERS
══════════════════════════════════════════════════════════════ */
const Chapters = (() => {
  let _all      = [];
  let _subjects = [];

  const FIELDS = [
    { key:'title',       label:'Title',       type:'text',   required:true  },
    { key:'subject_id',  label:'Subject ID',  type:'text',   required:true  },
    { key:'description', label:'Description', type:'text',   required:false },
    { key:'grade',       label:'Grade',       type:'number', required:false },
    { key:'order',       label:'Order',       type:'number', required:false },
    { key:'is_active',   label:'Active',      type:'checkbox',required:false},
  ];

  async function render() {
    const el = document.getElementById('section-chapters');
    el.innerHTML = `
      <div class="page-header">
        <h3>Chapters</h3>
        <div class="flex gap-2">
          <button class="btn btn-secondary btn-sm" id="chapters-reload">&#8635; Reload</button>
          <button class="btn btn-primary btn-sm" onclick="Chapters.openCreate()">+ Add Chapter</button>
        </div>
      </div>
      <div class="filter-bar">
        <input type="text" id="chapters-search" placeholder="Search chapters…" />
        <select id="chapters-filter-subject"><option value="">All subjects</option></select>
      </div>
      <div class="card">
        <div class="card-header"><h4 id="chapters-count">Chapters</h4></div>
        <div class="table-wrap" id="chapters-table"></div>
      </div>`;

    document.getElementById('chapters-reload').addEventListener('click', load);
    document.getElementById('chapters-search').addEventListener('input', renderTable);
    document.getElementById('chapters-filter-subject').addEventListener('change', renderTable);
    await load();
  }

  async function load() {
    const wrap = document.getElementById('chapters-table');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      [_all, _subjects] = await Promise.all([
        API.get('/chapters?limit=2000'),
        API.get('/subjects'),
      ]);
      _populateSubjectFilter();
      renderTable();
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function _populateSubjectFilter() {
    const sel = document.getElementById('chapters-filter-subject');
    if (!sel) return;
    sel.innerHTML = '<option value="">All subjects</option>' +
      _subjects.map(s => `<option value="${s._id}">${esc(s.name)} (${s._id})</option>`).join('');
  }

  function renderTable() {
    const wrap = document.getElementById('chapters-table');
    const q    = (document.getElementById('chapters-search')?.value || '').toLowerCase();
    const sid  = document.getElementById('chapters-filter-subject')?.value || '';
    if (!wrap) return;

    const rows = _all.filter(c => {
      const matchQ = !q || (c.title||'').toLowerCase().includes(q) || (c._id||'').toLowerCase().includes(q);
      const matchS = !sid || c.subject_id === sid;
      return matchQ && matchS;
    });
    const countEl = document.getElementById('chapters-count');
    if (countEl) countEl.textContent = `Chapters (${rows.length})`;

    if (!rows.length) { wrap.innerHTML = '<div class="empty-state"><p>No chapters found.</p></div>'; return; }

    const subjMap = {};
    _subjects.forEach(s => { subjMap[s._id] = s.name; });

    wrap.innerHTML = `
      <table>
        <thead><tr><th>ID</th><th>Title</th><th>Subject</th><th>Grade</th><th>Order</th><th>Active</th><th>Actions</th></tr></thead>
        <tbody>
          ${rows.map(c => `
            <tr>
              <td class="text-muted text-sm td-truncate">${c._id}</td>
              <td>${esc(c.title || '—')}</td>
              <td><span class="badge badge-blue">${esc(subjMap[c.subject_id] || c.subject_id || '—')}</span></td>
              <td>${c.grade || '—'}</td>
              <td>${c.order ?? '—'}</td>
              <td>${c.is_active !== false ? '<span class="badge badge-green">Yes</span>' : '<span class="badge badge-gray">No</span>'}</td>
              <td>
                <div class="td-actions">
                  <button class="btn btn-secondary btn-sm" onclick="Chapters.openEdit('${c._id}')">Edit</button>
                  <button class="btn btn-danger btn-sm" onclick="Chapters.del('${c._id}')">Delete</button>
                </div>
              </td>
            </tr>`).join('')}
        </tbody>
      </table>`;
  }

  function _subjectOptions(selected = '') {
    return _subjects.map(s =>
      `<option value="${s._id}" ${s._id === selected ? 'selected' : ''}>${esc(s.name)} (${s._id})</option>`
    ).join('');
  }

  function _formHtml(data = {}) {
    return FIELDS.map(f => {
      if (f.key === 'subject_id') {
        return `<div class="form-group">
          <label>Subject *</label>
          <select id="f_subject_id"><option value="">Select…</option>${_subjectOptions(data.subject_id)}</select>
        </div>`;
      }
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
      if (f.key === 'subject_id') {
        obj[f.key] = document.getElementById('f_subject_id')?.value || '';
        return;
      }
      const el = document.getElementById(`f_${f.key}`);
      if (!el) return;
      if (f.type === 'checkbox') obj[f.key] = el.checked;
      else if (f.type === 'number') obj[f.key] = el.value !== '' ? Number(el.value) : null;
      else obj[f.key] = el.value;
    });
    return obj;
  }

  function openCreate() {
    Modal.open('Add Chapter', _formHtml(), {
      onSave: async () => {
        const payload = _collectForm();
        if (!payload.title) { Toast.error('Title is required'); return; }
        if (!payload.subject_id) { Toast.error('Subject is required'); return; }
        try {
          await API.post('/chapters', payload);
          Toast.success('Chapter created'); Modal.close(); await load();
        } catch (err) { Toast.error(err.message); }
      },
    });
  }

  function openEdit(id) {
    const ch = _all.find(c => c._id === id);
    if (!ch) return;
    Modal.open('Edit Chapter', _formHtml(ch), {
      onSave: async () => {
        const payload = _collectForm();
        try {
          await API.put(`/chapters/${id}`, payload);
          Toast.success('Chapter updated'); Modal.close(); await load();
        } catch (err) { Toast.error(err.message); }
      },
    });
  }

  async function del(id) {
    if (!confirm(`Delete chapter ${id}?`)) return;
    try {
      await API.del(`/chapters/${id}`);
      Toast.success('Deleted'); await load();
    } catch (err) { Toast.error(err.message); }
  }

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render, openCreate, openEdit, del };
})();

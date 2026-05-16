/**
 * bbsamples.js — BB (Blackboard) Samples management.
 *
 * These are the onboarding lesson samples copied to new user accounts.
 * Full CRUD: list, view frames preview, create, edit, delete.
 *
 * API endpoints:
 *  GET    /admin/api/bb-samples
 *  POST   /admin/api/bb-samples
 *  PUT    /admin/api/bb-samples/{sample_id}
 *  DELETE /admin/api/bb-samples/{sample_id}
 */

const BBSamples = (() => {
  let _all      = [];
  let _filtered = [];

  // ── Shell ───────────────────────────────────────────────────────────────────

  async function render() {
    const el = document.getElementById('section-bbsamples');
    el.innerHTML = `
      <div class="page-header">
        <h3>BB Samples <span class="badge badge-gray" style="margin-left:6px;">Onboarding Lessons</span></h3>
        <div class="flex gap-2">
          <button class="btn btn-secondary btn-sm" id="bbs-reload">&#8635; Reload</button>
          <button class="btn btn-primary btn-sm" onclick="BBSamples.openCreate()">+ Add Sample</button>
        </div>
      </div>
      <p class="text-sm text-muted mb-4" style="padding:0 4px;">
        These sample lessons are copied to every new user account at registration. 
        They appear as pre-filled BB sessions to help users get started.
      </p>

      <div class="filter-bar">
        <input type="text" id="bbs-search" placeholder="Search by title, subject…" />
        <select id="bbs-filter-lang">
          <option value="">All languages</option>
          <option value="en-US">English</option>
          <option value="hi-IN">Hindi</option>
        </select>
      </div>

      <div class="card">
        <div class="card-header">
          <h4 id="bbs-count">BB Samples</h4>
        </div>
        <div class="table-wrap" id="bbs-table">
          <div class="loading"><div class="spinner"></div> Loading…</div>
        </div>
      </div>`;

    document.getElementById('bbs-reload').addEventListener('click', load);
    document.getElementById('bbs-search').addEventListener('input', _applyFilter);
    document.getElementById('bbs-filter-lang').addEventListener('change', _applyFilter);
    await load();
  }

  // ── Load ────────────────────────────────────────────────────────────────────

  async function load() {
    const wrap = document.getElementById('bbs-table');
    if (!wrap) return;
    wrap.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      _all = await API.get('/bb-samples');
      _filtered = [..._all];
      _applyFilter();
    } catch (err) {
      wrap.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function _applyFilter() {
    const q    = (document.getElementById('bbs-search')?.value || '').toLowerCase();
    const lang = document.getElementById('bbs-filter-lang')?.value || '';

    _filtered = _all.filter(s => {
      const matchQ = !q
        || (s.title   || '').toLowerCase().includes(q)
        || (s.subject || '').toLowerCase().includes(q)
        || (s.chapter || '').toLowerCase().includes(q)
        || (s._id     || '').toLowerCase().includes(q);
      const matchL = !lang || (s.lang || 'en-US') === lang;
      return matchQ && matchL;
    });

    _renderTable();
    const count = document.getElementById('bbs-count');
    if (count) count.textContent = `BB Samples (${_filtered.length})`;
  }

  // ── Table ───────────────────────────────────────────────────────────────────

  function _renderTable() {
    const wrap = document.getElementById('bbs-table');
    if (!wrap) return;
    if (!_filtered.length) {
      wrap.innerHTML = '<div class="empty-state"><p>No BB samples found.</p></div>';
      return;
    }
    wrap.innerHTML = `
      <table class="data-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>Title</th>
            <th>Subject</th>
            <th>Chapter</th>
            <th>Lang</th>
            <th>Frames</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          ${_filtered.map(s => {
            let frameCount = '?';
            if (s.steps_json) {
              try { frameCount = JSON.parse(s.steps_json).length; } catch (_) {}
            } else if (Array.isArray(s.steps)) {
              frameCount = s.steps.length;
            }
            return `
            <tr>
              <td><code style="font-size:11px;">${esc(s._id || '—')}</code></td>
              <td><strong>${esc(s.title || '—')}</strong></td>
              <td>${esc(s.subject || '—')}</td>
              <td>${esc(s.chapter || '—')}</td>
              <td><span class="badge badge-gray">${esc(s.lang || 'en-US')}</span></td>
              <td>${frameCount}</td>
              <td class="actions">
                <button class="btn btn-sm btn-secondary" onclick="BBSamples.viewSample('${esc(s._id || '')}')">View</button>
                <button class="btn btn-sm btn-secondary" onclick="BBSamples.openEdit('${esc(s._id || '')}')">Edit</button>
                <button class="btn btn-sm btn-danger"    onclick="BBSamples.confirmDelete('${esc(s._id || '')}', '${esc(s.title || '')}')">Delete</button>
              </td>
            </tr>`;
          }).join('')}
        </tbody>
      </table>`;
  }

  // ── View ─────────────────────────────────────────────────────────────────────

  function viewSample(id) {
    const s = _all.find(x => x._id === id);
    if (!s) return;

    let framesHtml = '';
    let frames = [];
    if (s.steps_json) {
      try { frames = JSON.parse(s.steps_json); } catch (_) {}
    } else if (Array.isArray(s.steps)) {
      frames = s.steps;
    }

    if (frames.length) {
      framesHtml = frames.map((f, i) => `
        <div style="border:1px solid var(--c-border);border-radius:6px;padding:10px;margin-bottom:8px;">
          <div style="display:flex;gap:8px;align-items:center;margin-bottom:6px;">
            <span class="badge badge-gray">Frame ${i + 1}</span>
            <span class="badge" style="background:#4b5563;color:#fff;">${esc(f.type || f.frame_type || '—')}</span>
          </div>
          <p style="margin:0;font-size:13px;color:var(--c-text-1);">${esc((f.content || f.text || f.question || '').slice(0, 200))}</p>
        </div>`).join('');
    } else {
      framesHtml = '<p class="text-sm text-muted">No frames data.</p>';
    }

    Modal.open(`Sample: ${s.title}`, `
      <div class="kv-grid" style="margin-bottom:16px;">
        <span class="kv-key">ID</span>       <span class="kv-val"><code>${esc(s._id || '')}</code></span>
        <span class="kv-key">Subject</span>  <span class="kv-val">${esc(s.subject || '—')}</span>
        <span class="kv-key">Chapter</span>  <span class="kv-val">${esc(s.chapter || '—')}</span>
        <span class="kv-key">Language</span> <span class="kv-val">${esc(s.lang || 'en-US')}</span>
        <span class="kv-key">Frames</span>   <span class="kv-val">${frames.length}</span>
      </div>
      <div class="section-label" style="margin-bottom:8px;">Frames Preview</div>
      ${framesHtml}
      <div style="margin-top:12px;">
        <button class="btn btn-sm btn-secondary" onclick="BBSamples._showRaw('${esc(id)}')">View Raw JSON</button>
      </div>`, {
      saveLabel: null,
      wide: true,
    });
  }

  function _showRaw(id) {
    const s = _all.find(x => x._id === id);
    if (!s) return;
    const json = JSON.stringify(s, null, 2);
    Modal.open('Raw JSON', `
      <textarea style="width:100%;height:420px;font-family:monospace;font-size:12px;
        background:var(--c-bg-2);color:var(--c-text-1);border:1px solid var(--c-border);
        border-radius:4px;padding:8px;" readonly>${esc(json)}</textarea>`, {
      saveLabel: null,
      wide: true,
    });
  }

  // ── Create ──────────────────────────────────────────────────────────────────

  function openCreate() {
    Modal.open('Add BB Sample', _formHtml(null), {
      saveLabel: 'Create',
      wide: true,
      onSave: async () => {
        const data = _collectForm();
        if (!data.id) { Toast.error('Document ID is required.'); return; }
        if (!data.title) { Toast.error('Title is required.'); return; }
        if (!data.steps_json) { Toast.error('Steps JSON is required.'); return; }
        try {
          JSON.parse(data.steps_json);
        } catch (_) {
          Toast.error('Steps JSON is not valid JSON.'); return;
        }
        try {
          await API.post('/bb-samples', data);
          Modal.close();
          Toast.success('BB sample created.');
          await load();
        } catch (err) {
          Toast.error(err.message);
        }
      },
    });
  }

  // ── Edit ────────────────────────────────────────────────────────────────────

  function openEdit(id) {
    const s = _all.find(x => x._id === id);
    if (!s) { Toast.error('Sample not found.'); return; }

    Modal.open(`Edit: ${s.title}`, _formHtml(s), {
      saveLabel: 'Save',
      wide: true,
      onSave: async () => {
        const data = _collectForm();
        if (data.steps_json) {
          try { JSON.parse(data.steps_json); }
          catch (_) { Toast.error('Steps JSON is not valid JSON.'); return; }
        }
        delete data.id; // ID is the doc key, not updateable
        try {
          await API.put(`/bb-samples/${id}`, data);
          Modal.close();
          Toast.success('BB sample updated.');
          await load();
        } catch (err) {
          Toast.error(err.message);
        }
      },
    });
  }

  // ── Delete ──────────────────────────────────────────────────────────────────

  function confirmDelete(id, title) {
    Modal.open('Delete BB Sample', `
      <div class="warn-box">
        <p>&#9888; Delete sample <strong>${esc(title)}</strong>?</p>
        <p class="text-sm text-muted">New users won't get this sample. Existing copies are unaffected.</p>
      </div>`, {
      saveLabel: 'Delete',
      onSave: async () => {
        try {
          await API.del(`/bb-samples/${id}`);
          Modal.close();
          Toast.success('Sample deleted.');
          await load();
        } catch (err) {
          Toast.error(err.message);
        }
      },
    });
  }

  // ── Form ────────────────────────────────────────────────────────────────────

  function _formHtml(item) {
    const isEdit = !!item;
    const stepsVal = item?.steps_json
      ? item.steps_json
      : (Array.isArray(item?.steps) ? JSON.stringify(item.steps, null, 2) : '');
    return `
      <div class="form-grid">
        <div class="form-group">
          <label>Document ID <span style="color:red">*</span></label>
          <input type="text" name="id" value="${esc(item?._id || '')}" ${isEdit ? 'readonly style="opacity:.6"' : ''} />
          <p class="text-sm text-muted" style="margin-top:2px;">Unique key (e.g. photosynthesis_en)</p>
        </div>
        <div class="form-group">
          <label>Title <span style="color:red">*</span></label>
          <input type="text" name="title" value="${esc(item?.title || '')}" />
        </div>
        <div class="form-group">
          <label>Subject</label>
          <input type="text" name="subject" value="${esc(item?.subject || '')}" />
        </div>
        <div class="form-group">
          <label>Chapter</label>
          <input type="text" name="chapter" value="${esc(item?.chapter || '')}" />
        </div>
        <div class="form-group">
          <label>Language</label>
          <select name="lang">
            <option value="en-US" ${(item?.lang || 'en-US') === 'en-US' ? 'selected' : ''}>English (en-US)</option>
            <option value="hi-IN" ${item?.lang === 'hi-IN' ? 'selected' : ''}>Hindi (hi-IN)</option>
          </select>
        </div>
      </div>
      <div class="form-group" style="margin-top:8px;">
        <label>Steps JSON <span style="color:red">*</span></label>
        <textarea name="steps_json" rows="14"
          style="width:100%;font-family:monospace;font-size:12px;resize:vertical;
            background:var(--c-bg-2);color:var(--c-text-1);border:1px solid var(--c-border);
            border-radius:4px;padding:8px;">${esc(stepsVal)}</textarea>
        <p class="text-sm text-muted" style="margin-top:2px;">Array of BB frame objects. Each: {type, content/text, …}</p>
      </div>`;
  }

  function _collectForm() {
    const get = (n) => document.querySelector(`#modal-body [name="${n}"]`);
    return {
      id:         (get('id')?.value || '').trim(),
      title:      (get('title')?.value || '').trim(),
      subject:    (get('subject')?.value || '').trim(),
      chapter:    (get('chapter')?.value || '').trim(),
      lang:       get('lang')?.value || 'en-US',
      steps_json: (get('steps_json')?.value || '').trim(),
    };
  }

  // ── Utility ─────────────────────────────────────────────────────────────────

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  return { render, viewSample, openCreate, openEdit, confirmDelete, _showRaw };
})();

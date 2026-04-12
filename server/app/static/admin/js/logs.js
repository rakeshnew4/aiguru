/**
 * logs.js — Activity Logs section module for the AI Guru Admin Portal.
 *
 * Shows a live feed of user activity (registrations, chat calls, quiz events)
 * with event-type badges, filters, and auto-refresh every 30 seconds.
 */

const Logs = (() => {
  let _refreshTimer = null;
  let _currentFilter = '';

  // ── Event-type badge colours ────────────────────────────────
  const BADGE_COLORS = {
    register:       '#4caf50',  // green
    chat:           '#2196f3',  // blue
    quiz_generate:  '#ff9800',  // orange
    login:          '#9c27b0',  // purple
  };

  function _badgeHtml(type) {
    const color = BADGE_COLORS[type] || '#607d8b';
    return `<span style="
      background:${color};color:#fff;padding:2px 8px;border-radius:10px;
      font-size:11px;font-weight:600;white-space:nowrap;">${type}</span>`;
  }

  function _formatTs(entry) {
    const iso = entry.ts_iso || entry.timestamp;
    if (!iso) return '—';
    if (typeof iso === 'number') {
      return new Date(iso).toLocaleString();
    }
    return String(iso).replace('T', ' ').slice(0, 19) + ' UTC';
  }

  function _extraDetails(entry) {
    const skip = new Set(['event_type', 'uid', 'name', 'email', 'timestamp', 'ts_iso', 'id']);
    const parts = [];
    for (const [k, v] of Object.entries(entry)) {
      if (skip.has(k)) continue;
      parts.push(`<span style="color:#aaa;font-size:11px;">${k}:</span> ${String(v).slice(0, 80)}`);
    }
    return parts.join(' &nbsp;|&nbsp; ');
  }

  // ── Stats bar ───────────────────────────────────────────────
  async function _renderStats() {
    const statsEl = document.getElementById('logs-stats-bar');
    if (!statsEl) return;
    try {
      const data = await API.get('/activity-logs/stats');
      const counts = data.counts || {};
      const total = data.total || 0;
      const parts = Object.entries(counts)
        .sort((a, b) => b[1] - a[1])
        .map(([k, v]) => `${_badgeHtml(k)} <strong>${v}</strong>`)
        .join(' &nbsp; ');
      statsEl.innerHTML = `<span style="margin-right:12px;color:#aaa;font-size:12px;">Last 500 events</span>${parts}
        <span style="margin-left:16px;color:#aaa;font-size:12px;">Total: <strong>${total}</strong></span>`;
    } catch (e) {
      statsEl.innerHTML = `<span style="color:#f44;">Could not load stats: ${e.message}</span>`;
    }
  }

  // ── Table render ────────────────────────────────────────────
  async function _renderTable(filter) {
    const tbody = document.getElementById('logs-tbody');
    const countEl = document.getElementById('logs-count');
    if (!tbody) return;

    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:20px;color:#aaa;">Loading…</td></tr>';

    try {
      let url = '/activity-logs?limit=200';
      if (filter) url += `&event_type=${encodeURIComponent(filter)}`;
      const rows = await API.get(url);

      if (countEl) countEl.textContent = `${rows.length} events`;

      if (!rows.length) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:20px;color:#aaa;">No activity yet.</td></tr>';
        return;
      }

      tbody.innerHTML = rows.map(r => `
        <tr>
          <td style="font-size:12px;white-space:nowrap;">${_formatTs(r)}</td>
          <td>${_badgeHtml(r.event_type || 'unknown')}</td>
          <td style="font-size:12px;max-width:120px;overflow:hidden;text-overflow:ellipsis;">${r.name || '—'}</td>
          <td style="font-size:12px;max-width:160px;overflow:hidden;text-overflow:ellipsis;">${r.email || '—'}</td>
          <td style="font-size:11px;color:#aaa;max-width:100px;overflow:hidden;text-overflow:ellipsis;">${(r.uid || '—').slice(0, 16)}</td>
          <td style="font-size:11px;">${_extraDetails(r)}</td>
        </tr>`).join('');
    } catch (e) {
      tbody.innerHTML = `<tr><td colspan="6" style="text-align:center;color:#f44;">Error: ${e.message}</td></tr>`;
    }
  }

  // ── Refresh helpers ─────────────────────────────────────────
  function _startAutoRefresh() {
    _stopAutoRefresh();
    _refreshTimer = setInterval(() => {
      _renderTable(_currentFilter);
      _renderStats();
    }, 30000);
  }

  function _stopAutoRefresh() {
    if (_refreshTimer) { clearInterval(_refreshTimer); _refreshTimer = null; }
  }

  // ── Public render ───────────────────────────────────────────
  function render() {
    const section = document.getElementById('section-logs');
    if (!section) return;

    section.innerHTML = `
      <div class="section-header" style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px;margin-bottom:16px;">
        <h2 style="margin:0;">Activity Logs</h2>
        <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
          <select id="logs-filter" style="padding:6px 10px;border-radius:6px;border:1px solid #333;background:#1e1e2f;color:#e0e0e0;font-size:13px;">
            <option value="">All Events</option>
            <option value="register">Register</option>
            <option value="chat">Chat</option>
            <option value="quiz_generate">Quiz Generate</option>
            <option value="login">Login</option>
          </select>
          <span id="logs-count" style="font-size:12px;color:#aaa;"></span>
          <button id="logs-refresh-btn" class="btn btn-secondary" style="font-size:12px;padding:5px 12px;">&#8635; Refresh</button>
        </div>
      </div>

      <div id="logs-stats-bar" style="margin-bottom:12px;padding:8px 12px;background:#1a1a2e;border-radius:8px;display:flex;flex-wrap:wrap;gap:8px;align-items:center;"></div>

      <div style="overflow-x:auto;">
        <table style="width:100%;border-collapse:collapse;font-size:13px;">
          <thead>
            <tr style="background:#1e1e2f;text-align:left;">
              <th style="padding:10px 12px;color:#aaa;">Time</th>
              <th style="padding:10px 12px;color:#aaa;">Event</th>
              <th style="padding:10px 12px;color:#aaa;">Name</th>
              <th style="padding:10px 12px;color:#aaa;">Email</th>
              <th style="padding:10px 12px;color:#aaa;">UID</th>
              <th style="padding:10px 12px;color:#aaa;">Details</th>
            </tr>
          </thead>
          <tbody id="logs-tbody">
            <tr><td colspan="6" style="text-align:center;padding:20px;color:#aaa;">Loading…</td></tr>
          </tbody>
        </table>
      </div>

      <div style="margin-top:10px;font-size:11px;color:#555;">Auto-refreshes every 30 seconds.</div>
    `;

    // Wire up controls
    document.getElementById('logs-filter').addEventListener('change', (e) => {
      _currentFilter = e.target.value;
      _renderTable(_currentFilter);
    });

    document.getElementById('logs-refresh-btn').addEventListener('click', () => {
      _renderTable(_currentFilter);
      _renderStats();
    });

    _currentFilter = '';
    _renderStats();
    _renderTable('');
    _startAutoRefresh();
  }

  // Stop refreshing when navigating away (called by app.js)
  function destroy() {
    _stopAutoRefresh();
  }

  return { render, destroy };
})();

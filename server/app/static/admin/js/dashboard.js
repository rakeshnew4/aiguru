/**
 * dashboard.js — Rich analytics overview for the AI Guru Admin Portal.
 *
 * Uses two backend endpoints:
 *   GET /admin/api/stats     — collection counts (count() aggregation, cached 5 min)
 *   GET /admin/api/analytics — user growth, plan dist, revenue, LLM cost (cached 5 min)
 *   GET /admin/api/litellm/health — proxy health check
 */

const Dashboard = (() => {

  const STAT_CONFIG = {
    users:          { icon: '👤', label: 'Total Users',    color: '#3b82f6' },
    subjects:       { icon: '📚', label: 'Subjects',       color: '#8b5cf6' },
    chapters:       { icon: '📄', label: 'Chapters',       color: '#6366f1' },
    plans:          { icon: '💳', label: 'Plans',          color: '#0ea5e9' },
    payments:       { icon: '💵', label: 'Payments',       color: '#10b981' },
    quizzes:        { icon: '📝', label: 'Quizzes',        color: '#f59e0b' },
    schools:        { icon: '🏫', label: 'Schools',        color: '#ec4899' },
    referral_codes: { icon: '🔗', label: 'Referral Codes', color: '#14b8a6' },
  };

  const PLAN_COLORS = {
    free:              '#94a3b8',
    student_basic:     '#3b82f6',
    student_pro:       '#10b981',
    school_unlimited:  '#f59e0b',
  };

  // ── Shell ──────────────────────────────────────────────────────────────────

  async function render() {
    const el = document.getElementById('section-dashboard');
    el.innerHTML = `
      <div class="page-header">
        <h3>Dashboard</h3>
        <div class="flex gap-2 items-center">
          <span id="llm-health-badge" class="health-badge loading-badge">LiteLLM: checking…</span>
          <button class="btn btn-secondary btn-sm" id="dash-refresh">&#8635; Refresh</button>
        </div>
      </div>

      <div class="section-label">Collection Counts <span id="stats-cache-note" style="font-weight:400;text-transform:none;letter-spacing:0;color:#94a3b8;font-size:10px;">(cached 5 min)</span></div>
      <div class="stats-grid" id="stats-grid">
        <div class="loading"><div class="spinner"></div> Loading…</div>
      </div>

      <div class="section-label mt-4">Growth &amp; Activity</div>
      <div class="kpi-row" id="kpi-row">
        <div class="loading"><div class="spinner"></div></div>
      </div>

      <div class="two-col-grid mt-4" id="dist-row">
        <div class="loading" style="grid-column:1/-1"><div class="spinner"></div></div>
      </div>

      <div class="card mt-4" id="top-spenders-card" style="display:none;">
        <div class="card-header">
          <h4>💸 Top LLM Spenders</h4>
          <span class="text-sm text-muted" id="spenders-subtitle"></span>
        </div>
        <div class="table-wrap" id="top-spenders-body"></div>
      </div>

      <div class="card mt-4">
        <div class="card-header"><h4>Quick Actions</h4></div>
        <div class="card-body">
          <div class="flex gap-2" style="flex-wrap:wrap;">
            <button class="btn btn-primary btn-sm" onclick="App.navigate('users')">Manage Users</button>
            <button class="btn btn-primary btn-sm" onclick="App.navigate('analytics')">Full Analytics</button>
            <button class="btn btn-primary btn-sm" onclick="App.navigate('subjects')">Manage Subjects</button>
            <button class="btn btn-primary btn-sm" onclick="App.navigate('plans')">Manage Plans</button>
            <button class="btn btn-primary btn-sm" onclick="App.navigate('models')">Model Config</button>
            <button class="btn btn-primary btn-sm" onclick="App.navigate('appconfig')">App Config</button>
            <button class="btn btn-primary btn-sm" onclick="App.navigate('logs')">Activity Logs</button>
          </div>
        </div>
      </div>`;

    document.getElementById('dash-refresh').addEventListener('click', _loadAll);
    await _loadAll();
  }

  async function _loadAll() {
    await Promise.all([_loadStats(), _loadAnalytics(), _checkHealth()]);
  }

  // ── Collection counts ──────────────────────────────────────────────────────

  async function _loadStats() {
    const grid = document.getElementById('stats-grid');
    if (!grid) return;
    grid.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      const stats = await API.get('/stats');
      grid.innerHTML = Object.entries(stats).map(([key, val]) => {
        const cfg = STAT_CONFIG[key] || { icon: '▪', label: key, color: '#64748b' };
        return `<div class="stat-card" style="border-top:3px solid ${cfg.color};">
          <div class="stat-icon">${cfg.icon}</div>
          <div class="stat-num" style="color:${cfg.color};">${val.toLocaleString()}</div>
          <div class="stat-label">${cfg.label}</div>
        </div>`;
      }).join('');
    } catch (err) {
      grid.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  // ── Analytics: KPIs + distributions + top spenders ────────────────────────

  async function _loadAnalytics() {
    const kpiRow = document.getElementById('kpi-row');
    const distRow = document.getElementById('dist-row');
    if (!kpiRow) return;

    try {
      const data = await API.get('/analytics');
      const u   = data.users   || {};
      const rev = data.revenue || {};
      const llm = data.llm     || {};
      const totalUsers = Math.max(u.total || 1, 1);

      // ── KPI cards ──────────────────────────────────────────────────────────
      const kpis = [
        { label: 'Active Today',    val: (u.active_today   || 0).toLocaleString(), icon: '⚡', cls: 'kpi-green'  },
        { label: 'New This Week',   val: (u.new_this_week  || 0).toLocaleString(), icon: '📈', cls: 'kpi-blue'   },
        { label: 'New This Month',  val: (u.new_this_month || 0).toLocaleString(), icon: '🗓', cls: 'kpi-purple'  },
        { label: 'Total Revenue',   val: '₹' + (rev.total  || 0).toLocaleString(), icon: '💰', cls: 'kpi-yellow' },
        { label: 'LLM Spend (USD)', val: '$' + (llm.total_cost_usd || 0).toFixed(3), icon: '🤖', cls: 'kpi-orange' },
        { label: 'Avg Cost / User', val: '$' + (llm.avg_cost_per_user_usd || 0).toFixed(4), icon: '💡', cls: 'kpi-teal' },
      ];
      kpiRow.innerHTML = kpis.map(k => `
        <div class="kpi-card ${k.cls}">
          <div class="kpi-icon">${k.icon}</div>
          <div class="kpi-val">${k.val}</div>
          <div class="kpi-label">${k.label}</div>
        </div>`).join('');

      // ── Plan distribution ──────────────────────────────────────────────────
      const planDist  = u.plan_distribution  || {};
      const gradeDist = u.grade_distribution || {};

      const planBars = Object.entries(planDist).length
        ? Object.entries(planDist).map(([plan, cnt]) => {
            const pct   = Math.round(cnt / totalUsers * 100);
            const color = PLAN_COLORS[plan] || '#6366f1';
            return `<div class="bar-row">
              <span class="bar-label" title="${plan}">${plan}</span>
              <div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:${color};"></div></div>
              <span class="bar-count">${cnt} <span style="color:#94a3b8;">(${pct}%)</span></span>
            </div>`;
          }).join('')
        : '<div class="empty-state" style="padding:20px;font-size:13px;">No plan data yet.</div>';

      const gradeBars = Object.entries(gradeDist).length
        ? Object.entries(gradeDist).slice(0, 10).map(([grade, cnt]) => {
            const pct = Math.round(cnt / totalUsers * 100);
            return `<div class="bar-row">
              <span class="bar-label">Grade ${grade}</span>
              <div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:#6366f1;"></div></div>
              <span class="bar-count">${cnt}</span>
            </div>`;
          }).join('')
        : '<div class="empty-state" style="padding:20px;font-size:13px;">No grade data yet.</div>';

      distRow.innerHTML = `
        <div class="card">
          <div class="card-header">
            <h4>📊 Plan Distribution</h4>
            <span class="text-sm text-muted">${totalUsers} users</span>
          </div>
          <div class="card-body">${planBars}</div>
        </div>
        <div class="card">
          <div class="card-header">
            <h4>🎓 Grade Distribution</h4>
            <span class="text-sm text-muted">Top 10 grades</span>
          </div>
          <div class="card-body">${gradeBars}</div>
        </div>`;

      // ── Top spenders ───────────────────────────────────────────────────────
      const spenders = llm.top_spenders || [];
      if (spenders.length > 0) {
        const card = document.getElementById('top-spenders-card');
        const body = document.getElementById('top-spenders-body');
        const sub  = document.getElementById('spenders-subtitle');
        if (card) card.style.display = '';
        if (sub)  sub.textContent = `${llm.users_with_usage || 0} users with LLM activity · total $${(llm.total_cost_usd || 0).toFixed(4)}`;
        if (body) body.innerHTML = `
          <table>
            <thead><tr>
              <th>#</th>
              <th>User ID</th>
              <th>Total Cost (USD)</th>
              <th>Requests</th>
              <th>Avg / Request</th>
            </tr></thead>
            <tbody>
              ${spenders.map((s, i) => {
                const badge = s.cost > 1 ? 'badge-red' : s.cost > 0.1 ? 'badge-yellow' : 'badge-green';
                const avg   = s.requests > 0 ? (s.cost / s.requests).toFixed(5) : '—';
                return `<tr>
                  <td style="color:var(--c-text-2);font-size:12px;">${i + 1}</td>
                  <td class="td-truncate" style="max-width:200px;font-size:12px;color:var(--c-text-2);">${s.uid}</td>
                  <td><span class="badge ${badge}">$${s.cost.toFixed(4)}</span></td>
                  <td>${s.requests.toLocaleString()}</td>
                  <td style="font-size:12px;color:var(--c-text-2);">$${avg}</td>
                </tr>`;
              }).join('')}
            </tbody>
          </table>`;
      }

    } catch (err) {
      if (kpiRow)  kpiRow.innerHTML  = `<div class="empty-state" style="grid-column:1/-1;"><p>&#9888; Analytics unavailable: ${err.message}</p></div>`;
      if (distRow) distRow.innerHTML = '';
    }
  }

  // ── LiteLLM health ─────────────────────────────────────────────────────────

  async function _checkHealth() {
    const badge = document.getElementById('llm-health-badge');
    if (!badge) return;
    try {
      const h = await API.get('/litellm/health');
      badge.className   = h.status === 'healthy' ? 'health-badge health-ok' : 'health-badge health-err';
      badge.textContent = `LiteLLM: ${h.status}`;
    } catch (_) {
      badge.className   = 'health-badge health-err';
      badge.textContent = 'LiteLLM: unreachable';
    }
  }

  return { render };
})();

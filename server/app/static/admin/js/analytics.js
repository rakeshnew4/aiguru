/**
 * analytics.js — Full Analytics section for the AI Guru Admin Portal.
 *
 * Provides deeper drill-down than the dashboard summary:
 *   - App Growth: user totals, daily active, new signups timeline
 *   - Plan & Grade breakdown (full, not capped)
 *   - Revenue summary
 *   - LLM Usage: total cost, per-user cost, top spenders (full list)
 *
 * Endpoint: GET /admin/api/analytics  (cached 5 min server-side)
 */

const Analytics = (() => {

  const PLAN_COLORS = {
    free:             '#94a3b8',
    student_basic:    '#3b82f6',
    student_pro:      '#10b981',
    school_unlimited: '#f59e0b',
  };

  // ── Public render ──────────────────────────────────────────────────────────

  async function render() {
    const el = document.getElementById('section-analytics');
    if (!el) return;
    el.innerHTML = `
      <div class="page-header">
        <h3>Analytics</h3>
        <button class="btn btn-secondary btn-sm" id="analytics-refresh">&#8635; Refresh</button>
      </div>
      <div id="analytics-content">
        <div class="loading"><div class="spinner"></div> Loading analytics…</div>
      </div>`;
    document.getElementById('analytics-refresh').addEventListener('click', _load);
    await _load();
  }

  async function _load() {
    const el = document.getElementById('analytics-content');
    if (!el) return;
    el.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      const data = await API.get('/analytics');
      el.innerHTML = _buildHTML(data);
    } catch (err) {
      el.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  // ── Main render ────────────────────────────────────────────────────────────

  function _buildHTML(data) {
    const u   = data.users   || {};
    const rev = data.revenue || {};
    const llm = data.llm     || {};
    const total = Math.max(u.total || 1, 1);

    return `
      <!-- ── App Growth KPIs ─────────────────────────────────── -->
      <div class="section-label">App Growth</div>
      <div class="analytics-kpi-grid">
        ${_kpi('Total Users',     u.total         || 0, '👥', '#3b82f6')}
        ${_kpi('Active Today',    u.active_today  || 0, '⚡', '#10b981')}
        ${_kpi('New This Week',   u.new_this_week || 0, '📈', '#6366f1')}
        ${_kpi('New This Month',  u.new_this_month|| 0, '🗓', '#8b5cf6')}
      </div>

      <!-- ── Revenue ─────────────────────────────────────────── -->
      <div class="section-label mt-4">Revenue</div>
      <div class="analytics-kpi-grid">
        ${_kpi('Total Revenue',     '₹' + (rev.total      || 0).toLocaleString(), '💰', '#f59e0b')}
        ${_kpi('This Month',        '₹' + (rev.this_month || 0).toLocaleString(), '📅', '#ec4899')}
        ${_kpi('Payment Count',     rev.payment_count || 0,                       '🧾', '#14b8a6')}
        ${_kpi('Avg per Payment',   rev.payment_count ? '₹' + ((rev.total || 0) / rev.payment_count).toFixed(0) : '—', '📊', '#0ea5e9')}
      </div>

      <!-- ── LLM Cost ─────────────────────────────────────────── -->
      <div class="section-label mt-4">LLM Usage &amp; Cost</div>
      <div class="analytics-kpi-grid">
        ${_kpi('Total Spend (USD)',  '$' + (llm.total_cost_usd         || 0).toFixed(4), '🤖', '#ef4444')}
        ${_kpi('Avg Cost / User',    '$' + (llm.avg_cost_per_user_usd  || 0).toFixed(5), '💡', '#f97316')}
        ${_kpi('Users w/ Usage',     llm.users_with_usage || 0,                          '👤', '#a855f7')}
        ${_kpi('Usage Penetration',  total > 0 ? Math.round((llm.users_with_usage || 0) / total * 100) + '%' : '—', '📶', '#22c55e')}
      </div>

      <!-- ── Distributions ───────────────────────────────────── -->
      <div class="two-col-grid mt-4">
        <div class="card">
          <div class="card-header">
            <h4>📊 Plan Distribution</h4>
            <span class="text-sm text-muted">${total} users</span>
          </div>
          <div class="card-body">${_planBars(u.plan_distribution || {}, total)}</div>
        </div>
        <div class="card">
          <div class="card-header">
            <h4>🎓 Grade Distribution</h4>
            <span class="text-sm text-muted">All grades</span>
          </div>
          <div class="card-body">${_gradeBars(u.grade_distribution || {}, total)}</div>
        </div>
      </div>

      <!-- ── Top Spenders ─────────────────────────────────────── -->
      ${(llm.top_spenders || []).length > 0 ? `
      <div class="card mt-4">
        <div class="card-header">
          <h4>💸 LLM Top Spenders</h4>
          <span class="text-sm text-muted">All ${llm.users_with_usage || 0} active users · total $${(llm.total_cost_usd || 0).toFixed(4)}</span>
        </div>
        <div class="table-wrap">${_spendersTable(llm.top_spenders)}</div>
      </div>` : ''}

      <!-- ── Cost Efficiency ──────────────────────────────────── -->
      ${_costEfficiencyCard(llm, total)}
    `;
  }

  // ── Sub-renderers ──────────────────────────────────────────────────────────

  function _kpi(label, val, icon, color) {
    return `<div class="analytics-kpi-card" style="border-left:4px solid ${color};">
      <div class="akpi-icon" style="color:${color};">${icon}</div>
      <div class="akpi-val">${typeof val === 'number' ? val.toLocaleString() : val}</div>
      <div class="akpi-label">${label}</div>
    </div>`;
  }

  function _planBars(dist, total) {
    const entries = Object.entries(dist);
    if (!entries.length) return '<div class="empty-state" style="padding:20px;font-size:13px;">No data yet.</div>';
    return entries.map(([plan, cnt]) => {
      const pct   = Math.round(cnt / total * 100);
      const color = PLAN_COLORS[plan] || '#6366f1';
      const nice  = plan.replace(/_/g, ' ');
      return `<div class="bar-row">
        <span class="bar-label" title="${plan}">${nice}</span>
        <div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:${color};"></div></div>
        <span class="bar-count">${cnt} <span style="color:#94a3b8;">(${pct}%)</span></span>
      </div>`;
    }).join('');
  }

  function _gradeBars(dist, total) {
    const entries = Object.entries(dist);
    if (!entries.length) return '<div class="empty-state" style="padding:20px;font-size:13px;">No data yet.</div>';
    const colors = ['#3b82f6','#6366f1','#8b5cf6','#a855f7','#ec4899','#ef4444','#f97316','#f59e0b','#10b981','#14b8a6'];
    return entries.map(([grade, cnt], i) => {
      const pct   = Math.round(cnt / total * 100);
      const color = colors[i % colors.length];
      return `<div class="bar-row">
        <span class="bar-label">Grade ${grade}</span>
        <div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:${color};"></div></div>
        <span class="bar-count">${cnt}</span>
      </div>`;
    }).join('');
  }

  function _spendersTable(spenders) {
    if (!spenders || !spenders.length) return '<div class="empty-state" style="padding:20px;font-size:13px;">No LLM usage data.</div>';
    return `<table>
      <thead><tr>
        <th>#</th>
        <th>User ID</th>
        <th>Total Cost (USD)</th>
        <th>Requests</th>
        <th>Avg / Request</th>
        <th>Cost Tier</th>
      </tr></thead>
      <tbody>
        ${spenders.map((s, i) => {
          const tier  = s.cost > 5 ? { label: 'High',   cls: 'badge-red'    }
                      : s.cost > 1 ? { label: 'Medium', cls: 'badge-yellow' }
                      :              { label: 'Low',    cls: 'badge-green'  };
          const avg   = s.requests > 0 ? '$' + (s.cost / s.requests).toFixed(5) : '—';
          return `<tr>
            <td style="color:var(--c-text-2);font-size:12px;">${i + 1}</td>
            <td style="font-size:12px;color:var(--c-text-2);max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${s.uid}</td>
            <td><strong>$${s.cost.toFixed(4)}</strong></td>
            <td>${s.requests.toLocaleString()}</td>
            <td style="font-size:12px;color:var(--c-text-2);">${avg}</td>
            <td><span class="badge ${tier.cls}">${tier.label}</span></td>
          </tr>`;
        }).join('')}
      </tbody>
    </table>`;
  }

  function _costEfficiencyCard(llm, totalUsers) {
    const total = llm.total_cost_usd || 0;
    if (total === 0) return '';
    const withUsage    = llm.users_with_usage || 0;
    const avgActive    = withUsage > 0 ? total / withUsage : 0;
    const avgAll       = totalUsers > 0 ? total / totalUsers : 0;
    const penetration  = totalUsers > 0 ? Math.round(withUsage / totalUsers * 100) : 0;

    return `
      <div class="card mt-4">
        <div class="card-header"><h4>📉 Cost Efficiency Summary</h4></div>
        <div class="card-body">
          <div class="analytics-kpi-grid">
            ${_kpi('Total LLM Spend',      '$' + total.toFixed(4),    '💵', '#ef4444')}
            ${_kpi('Avg (active users)',    '$' + avgActive.toFixed(4),'🎯', '#f97316')}
            ${_kpi('Avg (all users)',       '$' + avgAll.toFixed(5),   '📊', '#6366f1')}
            ${_kpi('Usage Adoption',        penetration + '%',         '📶', '#10b981')}
          </div>
          <div style="margin-top:16px;padding:12px;background:#f8fafc;border-radius:8px;font-size:13px;color:var(--c-text-2);">
            <strong style="color:var(--c-text);">Interpretation:</strong>
            ${penetration < 20 ? '⚠ Less than 20% of users have used LLM features. Consider engagement campaigns.' :
              penetration < 50 ? '✅ Moderate LLM adoption. Growing engagement.' :
              '🚀 High LLM adoption. Monitor costs as user base grows.'}
            &nbsp;·&nbsp;
            ${avgAll < 0.01 ? '💚 Very low cost per user — excellent efficiency.' :
              avgAll < 0.05 ? '✅ Reasonable cost per user.' :
              '⚠ High average cost per user — review model usage tiers.'}
          </div>
        </div>
      </div>`;
  }

  return { render };
})();

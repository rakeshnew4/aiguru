/**
 * dashboard.js — Statistics overview section.
 *
 * Renders stat cards fetched from GET /admin/api/stats.
 * Called by app.js when the "dashboard" section is activated.
 */

const Dashboard = (() => {

  const ICONS = {
    users:         '&#128100;',
    subjects:      '&#128218;',
    chapters:      '&#128196;',
    plans:         '&#128179;',
    payments:      '&#128181;',
    quizzes:       '&#128221;',
    schools:       '&#127979;',
    referral_codes:'&#128279;',
  };

  const LABELS = {
    users:         'Users',
    subjects:      'Subjects',
    chapters:      'Chapters',
    plans:         'Plans',
    payments:      'Payments',
    quizzes:       'Quizzes',
    schools:       'Schools',
    referral_codes:'Referral Codes',
  };

  async function render() {
    const el = document.getElementById('section-dashboard');
    el.innerHTML = `
      <div class="page-header">
        <h3>Dashboard</h3>
        <button class="btn btn-secondary btn-sm" id="dash-refresh">&#8635; Refresh</button>
      </div>
      <div class="stats-grid" id="stats-grid">
        <div class="loading"><div class="spinner"></div> Loading stats…</div>
      </div>
      <div class="card mt-4">
        <div class="card-header"><h4>Quick Actions</h4></div>
        <div class="card-body">
          <div class="flex gap-2" style="flex-wrap:wrap;">
            <button class="btn btn-primary btn-sm" onclick="App.navigate('users')">Manage Users</button>
            <button class="btn btn-primary btn-sm" onclick="App.navigate('subjects')">Manage Subjects</button>
            <button class="btn btn-primary btn-sm" onclick="App.navigate('plans')">Manage Plans</button>
            <button class="btn btn-primary btn-sm" onclick="App.navigate('models')">Model Config</button>
            <button class="btn btn-primary btn-sm" onclick="App.navigate('appconfig')">App Config</button>
          </div>
        </div>
      </div>`;

    document.getElementById('dash-refresh').addEventListener('click', loadStats);
    await loadStats();
  }

  async function loadStats() {
    const grid = document.getElementById('stats-grid');
    if (!grid) return;
    grid.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      const stats = await API.get('/stats');
      grid.innerHTML = Object.entries(stats)
        .map(([key, val]) => `
          <div class="stat-card">
            <div class="stat-icon">${ICONS[key] || '&#9632;'}</div>
            <div class="stat-num">${val.toLocaleString()}</div>
            <div class="stat-label">${LABELS[key] || key}</div>
          </div>`)
        .join('');
    } catch (err) {
      grid.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  return { render };
})();

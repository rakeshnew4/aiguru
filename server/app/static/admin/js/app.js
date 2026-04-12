/**
 * app.js — Main application router and initializer.
 *
 * Responsibilities:
 *  1. Login / logout flow (credentials stored in sessionStorage via api.js)
 *  2. Navigation between sections (sidebar nav links)
 *  3. Global Modal helper (window.Modal)
 *  4. Global Toast helper (window.Toast)
 *  5. Sidebar toggle for mobile
 *
 * This file MUST be loaded last (after all section modules).
 */

/* ══════════════════════════════════════════════════════════════
   TOAST helper
══════════════════════════════════════════════════════════════ */
const Toast = (() => {
  function show(msg, type = 'info', durationMs = 3500) {
    const container = document.getElementById('toast-container');
    if (!container) return;
    const el = document.createElement('div');
    el.className = `toast toast-${type}`;
    const icons = { success: '✓', error: '✕', info: 'ℹ' };
    el.innerHTML = `<span>${icons[type] || ''}</span> <span>${String(msg)}</span>`;
    container.appendChild(el);
    setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity .3s'; }, durationMs);
    setTimeout(() => el.remove(), durationMs + 350);
  }
  return {
    success: (m) => show(m, 'success'),
    error:   (m) => show(m, 'error'),
    info:    (m) => show(m, 'info'),
  };
})();

/* ══════════════════════════════════════════════════════════════
   MODAL helper
══════════════════════════════════════════════════════════════ */
const Modal = (() => {
  let _onSave = null;

  function open(title, bodyHtml, opts = {}) {
    /**
     * opts:
     *   onSave    — async function called when Save is clicked
     *   saveLabel — button label (default 'Save'), null = hide button
     *   wide      — boolean, use wider modal
     */
    _onSave = opts.onSave || null;

    document.getElementById('modal-title').textContent = title;
    document.getElementById('modal-body').innerHTML = bodyHtml;

    const saveBtn   = document.getElementById('modal-save');
    const cancelBtn = document.getElementById('modal-cancel');
    const footer    = document.getElementById('modal-footer');
    const modal     = document.getElementById('modal');

    if (opts.saveLabel === null) {
      saveBtn.classList.add('hidden');
      cancelBtn.textContent = 'Close';
    } else {
      saveBtn.classList.remove('hidden');
      saveBtn.textContent = opts.saveLabel || 'Save';
      cancelBtn.textContent = 'Cancel';
    }

    modal.classList.toggle('modal-wide', !!opts.wide);
    document.getElementById('modal-overlay').classList.remove('hidden');
  }

  function close() {
    document.getElementById('modal-overlay').classList.add('hidden');
    document.getElementById('modal-body').innerHTML = '';
    _onSave = null;
  }

  function setBody(html) {
    document.getElementById('modal-body').innerHTML = html;
  }

  function _init() {
    document.getElementById('modal-close').addEventListener('click', close);
    document.getElementById('modal-cancel').addEventListener('click', close);
    document.getElementById('modal-save').addEventListener('click', async () => {
      if (_onSave) await _onSave();
    });
    // Close on overlay click
    document.getElementById('modal-overlay').addEventListener('click', (e) => {
      if (e.target === document.getElementById('modal-overlay')) close();
    });
  }

  return { open, close, setBody, _init };
})();

/* ══════════════════════════════════════════════════════════════
   APP — main router
══════════════════════════════════════════════════════════════ */
const App = (() => {

  const SECTION_MAP = {
    dashboard:     { title: 'Dashboard',       render: () => Dashboard.render()      },
    users:         { title: 'Users',            render: () => Users.render()          },
    subjects:      { title: 'Subjects',         render: () => Subjects.render()       },
    chapters:      { title: 'Chapters',         render: () => Chapters.render()       },
    plans:         { title: 'Plans',            render: () => Plans.render()          },
    schools:       { title: 'Schools',          render: () => Schools.render()        },
    payments:      { title: 'Payments',         render: () => Payments.render()       },
    models:        { title: 'Model Config',     render: () => ModelConfig.render()    },
    appconfig:     { title: 'App Config',       render: () => AppConfig.render()      },
    offers:        { title: 'Offers',           render: () => Offers.render()         },
    notifications: { title: 'Notifications',   render: () => Notifications.render()  },
    referrals:     { title: 'Referral Codes',   render: () => Referrals.render()      },
  };

  let _currentSection = null;

  // ── Navigation ──────────────────────────────────────────────

  function navigate(section) {
    if (!SECTION_MAP[section]) return;
    if (_currentSection === section) return;
    _currentSection = section;

    // Update sidebar active state
    document.querySelectorAll('.nav-item').forEach(item => {
      item.classList.toggle('active', item.dataset.section === section);
    });

    // Show/hide sections
    Object.keys(SECTION_MAP).forEach(key => {
      const el = document.getElementById(`section-${key}`);
      if (el) el.classList.toggle('hidden', key !== section);
    });

    // Update page title
    document.getElementById('page-title').textContent = SECTION_MAP[section].title;

    // Close sidebar on mobile
    if (window.innerWidth < 768) {
      document.getElementById('sidebar').classList.remove('open');
    }

    // Render the section
    SECTION_MAP[section].render();
  }

  // ── Login ────────────────────────────────────────────────────

  async function handleLogin(e) {
    e.preventDefault();
    const user = document.getElementById('login-username').value.trim();
    const pass = document.getElementById('login-password').value;
    const btn  = document.getElementById('login-btn');
    const err  = document.getElementById('login-error');

    if (!user || !pass) { showLoginError('Please enter username and password.'); return; }

    btn.disabled = true; btn.textContent = 'Signing in…';
    err.classList.add('hidden');

    API.setCredentials(user, pass);
    try {
      // Validate credentials with a lightweight call
      await API.get('/stats');
      showApp(user);
    } catch (e) {
      API.clearCredentials();
      showLoginError(e.message.includes('Unauthorized')
        ? 'Invalid username or password.'
        : `Login failed: ${e.message}`);
    } finally {
      btn.disabled = false; btn.textContent = 'Sign In';
    }
  }

  function showLoginError(msg) {
    const el = document.getElementById('login-error');
    el.textContent = msg;
    el.classList.remove('hidden');
  }

  function showApp(username) {
    document.getElementById('login-screen').classList.add('hidden');
    document.getElementById('app').classList.remove('hidden');
    document.getElementById('sidebar-user').textContent = username;
    document.getElementById('topbar-user').textContent = username;
    navigate('dashboard');
  }

  function logout() {
    API.clearCredentials();
    document.getElementById('app').classList.add('hidden');
    document.getElementById('login-screen').classList.remove('hidden');
    document.getElementById('login-password').value = '';
    _currentSection = null;
  }

  // ── Init ─────────────────────────────────────────────────────

  function init() {
    Modal._init();

    // Login form
    document.getElementById('login-form').addEventListener('submit', handleLogin);

    // Logout
    document.getElementById('logout-btn').addEventListener('click', logout);

    // Sidebar nav
    document.querySelectorAll('.nav-item[data-section]').forEach(item => {
      item.addEventListener('click', () => navigate(item.dataset.section));
    });

    // Sidebar toggle (mobile)
    document.getElementById('sidebar-toggle').addEventListener('click', () => {
      document.getElementById('sidebar').classList.toggle('open');
    });

    // Handle unauthorized event from api.js
    window.addEventListener('admin:unauthorized', () => {
      Toast.error('Session expired — please sign in again.');
      logout();
    });

    // Auto-login if credentials are already in sessionStorage
    if (API.hasCredentials()) {
      const { user } = API.getCredentials();
      showApp(user);
    }
  }

  return { init, navigate };
})();

// Boot
document.addEventListener('DOMContentLoaded', () => App.init());

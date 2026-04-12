/**
 * models.js — AI Model Configuration viewer & editor.
 *
 * Shows the live environment tiers (read-only) and the Firestore
 * admin_config/global document (editable via JSON).
 *
 * API endpoints:
 *  GET /admin/api/model-config
 *  PUT /admin/api/model-config
 */

const ModelConfig = (() => {

  async function render() {
    const el = document.getElementById('section-models');
    el.innerHTML = `
      <div class="page-header">
        <h3>Model Configuration</h3>
        <button class="btn btn-secondary btn-sm" id="model-reload">&#8635; Reload</button>
      </div>

      <div id="model-content">
        <div class="loading"><div class="spinner"></div> Loading…</div>
      </div>`;

    document.getElementById('model-reload').addEventListener('click', load);
    await load();
  }

  async function load() {
    const container = document.getElementById('model-content');
    if (!container) return;
    container.innerHTML = '<div class="loading"><div class="spinner"></div> Loading…</div>';
    try {
      const data = await API.get('/model-config');
      renderContent(data, container);
    } catch (err) {
      container.innerHTML = `<div class="empty-state"><p>&#9888; ${err.message}</p></div>`;
    }
  }

  function renderContent(data, container) {
    const tiers = data.live_env_tiers || {};
    const fsConfig = data.firestore_admin_config || {};

    const tierColors = { power: 'tier-power', cheaper: 'tier-cheaper', faster: 'tier-faster' };
    const tierIcons  = { power: '&#9889;', cheaper: '&#128176;', faster: '&#128640;' };

    container.innerHTML = `
      <!-- Live Env Tiers (read-only) -->
      <div class="card mb-4">
        <div class="card-header">
          <h4>Live Environment Tiers <span class="badge badge-gray" style="margin-left:8px;">Read-only</span></h4>
        </div>
        <div class="card-body">
          <div class="tier-grid">
            ${Object.entries(tiers).map(([tier, cfg]) => `
              <div class="tier-card ${tierColors[tier] || ''}">
                <h4>
                  <span class="tier-dot"></span>
                  ${tierIcons[tier] || ''} ${tier.charAt(0).toUpperCase() + tier.slice(1)} Tier
                </h4>
                <div class="kv-grid">
                  <span class="kv-key">Provider</span>
                  <span class="kv-val"><code>${esc(cfg.provider || '—')}</code></span>
                  <span class="kv-key">Model ID</span>
                  <span class="kv-val"><code style="font-size:11px;">${esc(cfg.model_id || '—')}</code></span>
                  <span class="kv-key">Temperature</span>
                  <span class="kv-val">${cfg.temperature ?? '—'}</span>
                  <span class="kv-key">Max Tokens</span>
                  <span class="kv-val">${cfg.max_tokens ?? '—'}</span>
                </div>
              </div>`).join('')}
          </div>
          <p class="text-sm text-muted mt-4">
            &#8505; These values come from environment variables (POWER_PROVIDER, POWER_MODEL_ID, etc.)
            and cannot be changed from this portal. Edit the .env file and restart the server.
          </p>
        </div>
      </div>

      <!-- Firestore Admin Config (editable) -->
      <div class="card">
        <div class="card-header">
          <h4>Firestore Admin Config <span class="badge badge-blue" style="margin-left:8px;">Editable</span></h4>
        </div>
        <div class="card-body">
          <p class="text-sm text-muted mb-4">
            Stored in <code>admin_config/global</code>. Edit the JSON below and click Save.
            The app reads this at runtime for feature flags and overrides.
          </p>
          <div class="form-group">
            <label>admin_config/global (JSON)</label>
            <textarea class="json-editor" id="fs-config-json" style="min-height:280px;">${esc(JSON.stringify(fsConfig, null, 2))}</textarea>
          </div>
          <div class="flex gap-2 mt-4">
            <button class="btn btn-primary" id="model-save-btn">Save to Firestore</button>
            <button class="btn btn-secondary" id="model-format-btn">Format JSON</button>
          </div>
          <div id="model-save-status" class="text-sm mt-4"></div>
        </div>
      </div>`;

    document.getElementById('model-save-btn').addEventListener('click', saveConfig);
    document.getElementById('model-format-btn').addEventListener('click', () => {
      const ta = document.getElementById('fs-config-json');
      try {
        ta.value = JSON.stringify(JSON.parse(ta.value), null, 2);
      } catch (_) { Toast.error('Invalid JSON'); }
    });
  }

  async function saveConfig() {
    const raw = document.getElementById('fs-config-json')?.value;
    let payload;
    try { payload = JSON.parse(raw); } catch (_) { Toast.error('Invalid JSON — fix before saving.'); return; }

    const btn = document.getElementById('model-save-btn');
    btn.disabled = true; btn.textContent = 'Saving…';
    try {
      await API.put('/model-config', payload);
      Toast.success('Model config saved to Firestore');
    } catch (err) {
      Toast.error(err.message);
    } finally {
      btn.disabled = false; btn.textContent = 'Save to Firestore';
    }
  }

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  return { render };
})();

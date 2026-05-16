/**
 * serverconfig.js — Server & Environment Configuration viewer.
 *
 * Shows:
 *  - Environment variable presence (not values for secrets)
 *  - LLM model tiers (from /model-config)
 *  - TTS engine fallback chain config
 *  - Editable Firestore admin_config/global via JSON
 *
 * API endpoints:
 *  GET  /admin/api/env-status    (new)
 *  GET  /admin/api/model-config  (reuses existing)
 *  PUT  /admin/api/model-config  (reuses existing)
 */

const ServerConfig = (() => {

  // ── Shell ───────────────────────────────────────────────────────────────────

  async function render() {
    const el = document.getElementById('section-serverconfig');
    el.innerHTML = `
      <div class="page-header">
        <h3>Server Configuration</h3>
        <button class="btn btn-secondary btn-sm" id="sc-reload">&#8635; Reload</button>
      </div>

      <!-- ENV STATUS -->
      <div class="section-label">Environment Variables</div>
      <div id="sc-env-grid" class="mb-4">
        <div class="loading"><div class="spinner"></div> Loading…</div>
      </div>

      <!-- LLM TIERS -->
      <div class="section-label">LLM Model Tiers <span class="badge badge-gray" style="font-weight:400;text-transform:none;">from .env</span></div>
      <div id="sc-tiers" class="mb-4">
        <div class="loading"><div class="spinner"></div> Loading…</div>
      </div>

      <!-- TTS CHAIN -->
      <div class="section-label">TTS Engine Chain</div>
      <div id="sc-tts" class="mb-4">
        <div class="loading"><div class="spinner"></div> Loading…</div>
      </div>

      <!-- FIRESTORE ADMIN CONFIG -->
      <div class="card">
        <div class="card-header">
          <h4>Firestore Admin Config <code style="font-size:11px;color:var(--c-text-2);">admin_config/global</code></h4>
          <button class="btn btn-sm btn-primary" id="sc-save-btn">Save Changes</button>
        </div>
        <div class="card-body">
          <p class="text-sm text-muted mb-3">
            Stored in Firestore. Controls feature overrides, quota defaults, and routing rules
            that the server reads at runtime.
          </p>
          <div id="sc-fs-editor">
            <div class="loading"><div class="spinner"></div> Loading…</div>
          </div>
        </div>
      </div>`;

    document.getElementById('sc-reload').addEventListener('click', _loadAll);
    document.getElementById('sc-save-btn').addEventListener('click', _saveFirestoreConfig);
    await _loadAll();
  }

  // ── Load all ────────────────────────────────────────────────────────────────

  async function _loadAll() {
    await Promise.all([_loadEnvStatus(), _loadModelConfig()]);
  }

  // ── ENV STATUS ──────────────────────────────────────────────────────────────

  async function _loadEnvStatus() {
    const grid = document.getElementById('sc-env-grid');
    if (!grid) return;
    try {
      const data = await API.get('/env-status');
      _renderEnvGrid(data.env || {});
    } catch (err) {
      grid.innerHTML = `<div class="empty-state"><p>&#9888; ${esc(err.message)}</p></div>`;
    }
  }

  function _renderEnvGrid(env) {
    const grid = document.getElementById('sc-env-grid');
    if (!grid) return;

    const GROUPS = [
      { label: '🤖 LLM API Keys', keys: ['GEMINI_API_KEY', 'GROQ_API_KEY', 'OPENAI_API_KEY', 'ANTHROPIC_API_KEY', 'ELEVENLABS_API_KEY'] },
      { label: '☁️ AWS / Bedrock', keys: ['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_REGION'] },
      { label: '🔥 Firebase', keys: ['FIREBASE_SERVICE_ACCOUNT', 'GOOGLE_APPLICATION_CREDENTIALS'] },
      { label: '💳 Payments', keys: ['RAZORPAY_KEY_ID', 'RAZORPAY_KEY_SECRET'] },
      { label: '🔒 Auth', keys: ['ADMIN_USERNAME', 'ADMIN_PASSWORD', 'LITELLM_MASTER_KEY'] },
      { label: '⚙️ Config Values', keys: ['POWER_PROVIDER', 'POWER_MODEL_ID', 'CHEAPER_PROVIDER', 'CHEAPER_MODEL_ID', 'FASTER_PROVIDER', 'FASTER_MODEL_ID', 'TTS_DEFAULT_ENGINE', 'USE_LITELLM_PROXY', 'DEBUG'] },
      { label: '🔗 Services', keys: ['REDIS_URL', 'ELASTICSEARCH_URL', 'LITELLM_PROXY_URL'] },
    ];

    grid.innerHTML = GROUPS.map(group => {
      const items = group.keys.filter(k => env[k] !== undefined || true);
      return `
        <div class="card mb-3">
          <div class="card-header" style="padding:8px 16px;"><h4 style="font-size:13px;">${group.label}</h4></div>
          <div class="card-body" style="padding:8px 16px;">
            <div class="env-grid">
              ${group.keys.map(k => {
                const entry = env[k];
                const isSet = entry?.set ?? false;
                const val   = entry?.value ?? null;
                return `
                  <span class="env-key">${esc(k)}</span>
                  <span class="env-status">
                    <span class="badge ${isSet ? 'badge-green' : 'badge-red'}" style="font-size:10px;">
                      ${isSet ? '✓ Set' : '✗ Missing'}
                    </span>
                    ${val ? `<code style="margin-left:8px;font-size:11px;color:var(--c-text-2);">${esc(String(val))}</code>` : ''}
                  </span>`;
              }).join('')}
            </div>
          </div>
        </div>`;
    }).join('');
  }

  // ── MODEL CONFIG ────────────────────────────────────────────────────────────

  async function _loadModelConfig() {
    const tiersEl  = document.getElementById('sc-tiers');
    const ttsEl    = document.getElementById('sc-tts');
    const editorEl = document.getElementById('sc-fs-editor');
    if (!tiersEl) return;
    try {
      const data = await API.get('/model-config');
      _renderTiers(data.live_env_tiers || {}, tiersEl);
      _renderTTS(data.live_env_tiers || {}, ttsEl);
      _renderFsEditor(data.firestore_admin_config || {}, editorEl);
    } catch (err) {
      tiersEl.innerHTML = `<div class="empty-state"><p>&#9888; ${esc(err.message)}</p></div>`;
    }
  }

  function _renderTiers(tiers, el) {
    const tierMeta = {
      power:   { icon: '⚡', color: '#f59e0b', label: 'Power' },
      cheaper: { icon: '💰', color: '#3b82f6', label: 'Cheaper' },
      faster:  { icon: '🚀', color: '#10b981', label: 'Faster' },
    };
    el.innerHTML = `
      <div class="stats-grid">
        ${Object.entries(tiers).map(([tier, cfg]) => {
          const m = tierMeta[tier] || { icon: '?', color: '#6b7280', label: tier };
          return `
            <div class="stat-card" style="border-left:3px solid ${m.color};">
              <div class="stat-header">
                <span>${m.icon} ${m.label} Tier</span>
              </div>
              <div class="kv-grid" style="margin-top:8px;">
                <span class="kv-key">Provider</span>
                <span class="kv-val"><code>${esc(cfg.provider || '—')}</code></span>
                <span class="kv-key">Model ID</span>
                <span class="kv-val"><code style="font-size:10px;">${esc(cfg.model_id || '—')}</code></span>
                <span class="kv-key">Temp</span>
                <span class="kv-val">${cfg.temperature ?? '—'}</span>
                <span class="kv-key">Max Tokens</span>
                <span class="kv-val">${cfg.max_tokens ?? '—'}</span>
              </div>
            </div>`;
        }).join('')}
      </div>
      <p class="text-sm text-muted" style="margin-top:8px;">
        &#8505; Edit .env and restart the server to change these values.
      </p>`;
  }

  function _renderTTS(tiers, el) {
    el.innerHTML = `
      <div class="card">
        <div class="card-body">
          <div class="tts-chain">
            <div class="tts-step">
              <div class="tts-step-num">1</div>
              <div class="tts-step-body">
                <strong>Gemini TTS</strong>
                <span class="text-sm text-muted">gemini-2.5-flash-preview-tts · Voice: Kore · 24kHz WAV</span>
              </div>
            </div>
            <div class="tts-arrow">&#8595; fallback on error</div>
            <div class="tts-step">
              <div class="tts-step-num">2</div>
              <div class="tts-step-body">
                <strong>Google Cloud TTS</strong>
                <span class="text-sm text-muted">WaveNet voices · MP3 output · all languages</span>
              </div>
            </div>
            <div class="tts-arrow">&#8595; fallback (English only)</div>
            <div class="tts-step">
              <div class="tts-step-num">3</div>
              <div class="tts-step-body">
                <strong>ElevenLabs</strong>
                <span class="text-sm text-muted">English only · high quality</span>
              </div>
            </div>
            <div class="tts-arrow">&#8595; fallback (English only)</div>
            <div class="tts-step">
              <div class="tts-step-num">4</div>
              <div class="tts-step-body">
                <strong>OpenAI TTS</strong>
                <span class="text-sm text-muted">English only · last resort</span>
              </div>
            </div>
          </div>
          <p class="text-sm text-muted" style="margin-top:12px;">
            &#8505; Chain defined in <code>api/tts.py · synthesize()</code>.
            Default engine: <code>gemini</code> (set in TtsSynthesizeRequest model).
          </p>
        </div>
      </div>`;
  }

  function _renderFsEditor(config, el) {
    // Remove system fields
    const { _id, ...clean } = config;
    el.innerHTML = `
      <textarea id="sc-json-editor" rows="20"
        style="width:100%;font-family:monospace;font-size:12px;resize:vertical;
          background:var(--c-bg-2);color:var(--c-text-1);border:1px solid var(--c-border);
          border-radius:4px;padding:10px;">${esc(JSON.stringify(clean, null, 2))}</textarea>
      <p class="text-sm text-muted mt-2">
        This JSON is saved to Firestore <code>admin_config/global</code> on click "Save Changes".
        Changes take effect immediately (server reads this doc at request time).
      </p>`;
  }

  // ── Save Firestore config ────────────────────────────────────────────────────

  async function _saveFirestoreConfig() {
    const ta = document.getElementById('sc-json-editor');
    if (!ta) return;
    let parsed;
    try {
      parsed = JSON.parse(ta.value);
    } catch (e) {
      Toast.error('Invalid JSON — fix the syntax before saving.');
      return;
    }
    try {
      await API.put('/model-config', parsed);
      Toast.success('admin_config/global saved.');
    } catch (err) {
      Toast.error(err.message);
    }
  }

  // ── Utility ─────────────────────────────────────────────────────────────────

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  return { render };
})();

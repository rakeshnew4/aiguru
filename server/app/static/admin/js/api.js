/**
 * api.js — Central HTTP client for the Admin Portal.
 *
 * All admin API calls go through window.API.  Credentials are stored in
 * sessionStorage and injected as HTTP Basic Auth on every request.
 *
 * Exposed globally:
 *   API.setCredentials(user, pass)
 *   API.clearCredentials()
 *   API.get(path)          → Promise<any>
 *   API.post(path, body)   → Promise<any>
 *   API.put(path, body)    → Promise<any>
 *   API.del(path)          → Promise<any>
 */

const API = (() => {
  const BASE = '/admin/api';

  // ── Credential helpers ────────────────────────────────────────

  function setCredentials(user, pass) {
    sessionStorage.setItem('admin_user', user);
    sessionStorage.setItem('admin_pass', pass);
  }

  function clearCredentials() {
    sessionStorage.removeItem('admin_user');
    sessionStorage.removeItem('admin_pass');
  }

  function getCredentials() {
    return {
      user: sessionStorage.getItem('admin_user'),
      pass: sessionStorage.getItem('admin_pass'),
    };
  }

  function hasCredentials() {
    const { user, pass } = getCredentials();
    return !!(user && pass);
  }

  function _authHeader() {
    const { user, pass } = getCredentials();
    return 'Basic ' + btoa(`${user}:${pass}`);
  }

  // ── Core request ──────────────────────────────────────────────

  async function request(method, path, body) {
    const url = BASE + path;
    const opts = {
      method,
      headers: {
        'Authorization': _authHeader(),
        'Content-Type': 'application/json',
      },
    };
    if (body !== undefined) {
      opts.body = JSON.stringify(body);
    }

    let res;
    try {
      res = await fetch(url, opts);
    } catch (err) {
      throw new Error('Network error: ' + err.message);
    }

    if (res.status === 401) {
      clearCredentials();
      window.dispatchEvent(new Event('admin:unauthorized'));
      throw new Error('Unauthorized — please sign in again.');
    }

    if (!res.ok) {
      let detail = `HTTP ${res.status}`;
      try {
        const j = await res.json();
        detail = j.detail || JSON.stringify(j);
      } catch (_) { /* ignore */ }
      throw new Error(detail);
    }

    // 204 No Content
    if (res.status === 204) return null;

    return res.json();
  }

  // ── Public shortcuts ──────────────────────────────────────────

  return {
    setCredentials,
    clearCredentials,
    hasCredentials,
    getCredentials,

    get:  (path)        => request('GET',    path),
    post: (path, body)  => request('POST',   path, body),
    put:  (path, body)  => request('PUT',    path, body),
    del:  (path)        => request('DELETE', path),
  };
})();

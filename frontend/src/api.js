const BASE = '/api';

async function request(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(data?.error || `${method} ${path} failed (${res.status})`);
  return data;
}

export const api = {
  appointments: (status) => request('GET', '/appointments' + (status ? `?status=${status}` : '')),
  providers: () => request('GET', '/providers'),
  slots: () => request('GET', '/slots'),
  activity: () => request('GET', '/agent/activity'),
  strategy: () => request('GET', '/config/strategy'),
  setStrategy: (strategy) => request('PUT', '/config/strategy', { strategy }),
  book: (body) => request('POST', '/appointments', body),
  complete: (id) => request('PATCH', `/appointments/${id}/status`, { status: 'COMPLETED' }),
  suggest: (id) => request('POST', `/appointments/${id}/suggest`),
  decide: (id, status) => request('PATCH', `/suggestions/${id}`, { status }),
  setProviderStatus: (id, status) => request('PATCH', `/providers/${id}/status`, { status }),
  setSlotStatus: (id, status) => request('PATCH', `/slots/${id}/status`, { status }),
  reset: () => request('POST', '/admin/reset'),
};

/**
 * POST /appointments/{id}/suggest/stream. EventSource is GET-only, so read the SSE body via fetch.
 * Calls onToken(text) per chunk, resolves with the final suggestion, rejects on an error event.
 */
export async function streamSuggestion(id, onToken) {
  const res = await fetch(`${BASE}/appointments/${id}/suggest/stream`, { method: 'POST' });
  if (!res.ok || !res.body) throw new Error(`Stream failed (${res.status})`);
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let result = null;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const frame = buffer.slice(0, idx);
      buffer = buffer.slice(idx).replace(/^\r?\n\r?\n/, '');
      let event = 'message';
      const data = [];
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (!data.length) continue;
      const payload = JSON.parse(data.join('\n'));
      if (event === 'token') onToken(payload.text);
      else if (event === 'suggestion') result = payload;
      else if (event === 'error') throw new Error(payload.message);
    }
  }
  if (!result) throw new Error('Stream ended without a suggestion');
  return result;
}

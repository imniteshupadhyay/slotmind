import { useCallback, useEffect, useRef, useState } from 'react';
import { api, streamSuggestion } from './api.js';
import {
  ActivityFeed, AppointmentTable, BookForm, DayView, ProviderList, QueueCard, StreamPanel,
} from './components.jsx';

const POLL_MS = 3000;

export default function App() {
  const [data, setData] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const [toasts, setToasts] = useState([]);
  const [busyId, setBusyId] = useState(null);
  const [stream, setStream] = useState(null);
  const [lastSync, setLastSync] = useState(null);
  const toastId = useRef(0);

  const refresh = useCallback(async () => {
    try {
      const [appointments, providers, slots, activity, strategy] = await Promise.all([
        api.appointments(), api.providers(), api.slots(), api.activity(), api.strategy(),
      ]);
      setData({ appointments, providers, slots, activity, strategy });
      setLoadError(null);
      setLastSync(new Date());
    } catch (e) {
      setLoadError(e.message || 'Cannot reach the SlotMind API');
    }
  }, []);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, POLL_MS);
    return () => clearInterval(t);
  }, [refresh]);

  const toast = useCallback((message, kind = 'info') => {
    const id = ++toastId.current;
    setToasts((ts) => [...ts, { id, message, kind }]);
    setTimeout(() => setToasts((ts) => ts.filter((x) => x.id !== id)), 4500);
  }, []);

  /** Run an action with a busy marker, toast the outcome, refresh. Returns true on success. */
  const run = useCallback(async (id, fn, success) => {
    setBusyId(id);
    try {
      await fn();
      if (success) toast(success, 'success');
      return true;
    } catch (e) {
      toast(e.message, 'error');
      return false;
    } finally {
      setBusyId(null);
      refresh();
    }
  }, [refresh, toast]);

  const decide = (suggestionId, status) =>
    run(suggestionId, () => api.decide(suggestionId, status),
      status === 'ACCEPTED' ? 'Rebooked — slot updated' : 'Suggestion rejected');

  const startStream = async (appt) => {
    setStream({ appt, text: '', done: false, result: null, error: null });
    try {
      const result = await streamSuggestion(appt.id, (chunk) =>
        setStream((s) => (s && s.appt.id === appt.id ? { ...s, text: s.text + chunk } : s)));
      setStream((s) => (s ? { ...s, done: true, result } : s));
    } catch (e) {
      setStream((s) => (s ? { ...s, done: true, error: e.message } : s));
    }
    refresh();
  };

  const decideFromStream = async (id, status) => {
    if (await decide(id, status)) setStream(null);
  };

  if (!data) {
    return (
      <div className="boot">
        {loadError ? (
          <div className="error-banner">⚠ {loadError}. Is the backend running on :8080? Retrying…</div>
        ) : (
          <div className="spinner">Loading SlotMind…</div>
        )}
      </div>
    );
  }

  const { appointments, providers, slots, activity, strategy } = data;
  const queue = appointments.filter((a) => a.status === 'RESCHEDULE_PENDING');

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo">◷</span>
          <div>
            <div className="brand-name">SlotMind</div>
            <div className="muted small">CareBridge front desk · AI re-scheduling</div>
          </div>
        </div>
        <div className="topbar-right">
          <div className="segmented" title="Active scheduling strategy (applies to agent + manual suggestions)">
            {strategy.available.map((name) => (
              <button
                key={name}
                className={strategy.active === name ? 'on' : ''}
                onClick={() => run('strategy', () => api.setStrategy(name), `Strategy: ${name}`)}
              >
                {name === 'ai' ? '✦ AI' : name === 'rule' ? '⚙ Rule-based' : name}
              </button>
            ))}
          </div>
          <span className={`llm-status ${strategy.llmConfigured ? 'ok' : 'off'}`}>
            LLM {strategy.llmConfigured ? strategy.llmModel : 'not configured'}
          </span>
          <button className="btn ghost tiny" onClick={() => run('reset', api.reset, 'Demo data reset')}>Reset demo</button>
          <span className={`sync ${loadError ? 'bad' : ''}`}>
            {loadError ? '⚠ offline' : `● live · ${lastSync?.toLocaleTimeString()}`}
          </span>
        </div>
      </header>

      {loadError && <div className="error-banner">⚠ Lost connection to the API: {loadError}. Showing last known data.</div>}

      <main className="grid">
        <section className="col-main">
          <div className="panel">
            <div className="panel-head">
              <h2>Reschedule queue <span className="count">{queue.length}</span></h2>
              <span className="muted small">Confirm with the patient, then accept to rebook atomically.</span>
            </div>
            {queue.length === 0 ? (
              <div className="empty">
                <div className="empty-icon">✓</div>
                Nothing needs attention. Set a provider <b>Offline</b> or cancel a slot to see the agent react.
              </div>
            ) : (
              <div className="queue">
                {queue.map((a) => (
                  <QueueCard
                    key={a.id}
                    appt={a}
                    busy={busyId === a.pendingSuggestion?.id || busyId === a.id}
                    onDecide={decide}
                    onSuggest={(id) => run(id, () => api.suggest(id), 'New suggestion ready')}
                    onStream={startStream}
                  />
                ))}
              </div>
            )}
          </div>

          <div className="panel">
            <div className="panel-head">
              <h2>Day view</h2>
              <div className="legend small">
                <span className="lg OPEN">open</span><span className="lg BOOKED">booked</span><span className="lg CANCELLED">cancelled</span>
              </div>
            </div>
            <DayView
              providers={providers}
              slots={slots}
              busyId={busyId}
              onCancel={(s) => run(s.id, () => api.setSlotStatus(s.id, 'CANCELLED'),
                s.patientName ? `Slot cancelled — agent is rescheduling ${s.patientName}` : 'Slot cancelled')}
            />
          </div>

          <div className="panel">
            <div className="panel-head"><h2>All appointments</h2></div>
            <AppointmentTable
              appointments={appointments}
              busyId={busyId}
              onSuggest={(id) => run(id, () => api.suggest(id), 'Suggestion added to the queue')}
              onStream={startStream}
              onComplete={(id) => run(id, () => api.complete(id), 'Marked completed')}
            />
          </div>
        </section>

        <aside className="col-side">
          <div className="panel">
            <div className="panel-head"><h2>Providers</h2></div>
            <ProviderList
              providers={providers}
              appointments={appointments}
              busyId={busyId}
              onStatus={(id, status) => run(id, () => api.setProviderStatus(id, status),
                status === 'OFFLINE' ? 'Provider offline — agent is rescheduling affected patients' : `Status: ${status}`)}
            />
          </div>

          <div className="panel">
            <div className="panel-head"><h2>Agent activity</h2></div>
            <ActivityFeed entries={activity} />
          </div>

          <div className="panel">
            <div className="panel-head"><h2>Book appointment</h2></div>
            <BookForm
              providers={providers}
              slots={slots}
              onBook={(form) => run('book', () => api.book(form), `Booked ${form.patientName}`)}
            />
          </div>
        </aside>
      </main>

      <StreamPanel stream={stream} onClose={() => setStream(null)} onDecide={decideFromStream} />

      <div className="toasts">
        {toasts.map((t) => <div key={t.id} className={`toast ${t.kind}`}>{t.message}</div>)}
      </div>
    </div>
  );
}

import { useState } from 'react';
import { fmtAgo, fmtTime, fmtWhen, TRIGGER_LABEL, TYPE_LABEL } from './format.js';

export function TypeTag({ type }) {
  return <span className={`tag type-${type}`}>{TYPE_LABEL[type] || type}</span>;
}

export function TriggerBadge({ trigger }) {
  return <span className={`badge trigger-${trigger}`}>{TRIGGER_LABEL[trigger] || trigger}</span>;
}

export function StatusPill({ status }) {
  return <span className={`pill status-${status}`}>{status.replace(/_/g, ' ')}</span>;
}

function Confidence({ value }) {
  const pct = Math.round(value * 100);
  const level = pct >= 80 ? 'high' : pct >= 60 ? 'mid' : 'low';
  return (
    <div className="confidence" title={`Confidence ${pct}%`}>
      <div className="confidence-bar">
        <div className={`confidence-fill ${level}`} style={{ width: `${pct}%` }} />
      </div>
      <span className="mono">{pct}%</span>
    </div>
  );
}

/** One appointment awaiting a decision, with its pending suggestion inline. */
export function QueueCard({ appt, busy, onDecide, onSuggest, onStream }) {
  const s = appt.pendingSuggestion;
  const isAi = s && s.strategy === 'ai';
  return (
    <div className="queue-card">
      <div className="queue-head">
        <div>
          <div className="patient">{appt.patientName}</div>
          <div className="muted small">
            <TypeTag type={appt.appointmentType} /> was with <b>{appt.providerName}</b> · {fmtWhen(appt.slotStartTime)}
            {appt.slotStatus === 'CANCELLED' && <span className="strike-note"> (slot cancelled)</span>}
            {appt.providerStatus === 'OFFLINE' && <span className="strike-note"> (provider offline)</span>}
          </div>
        </div>
        {s && <TriggerBadge trigger={s.triggerReason} />}
      </div>

      {s ? (
        <>
          <div className="proposal">
            <div className="proposal-arrow">→</div>
            <div className="proposal-body">
              <div className="proposal-when">{fmtWhen(s.slotStartTime)} <span className="muted">({s.slotDurationMinutes} min)</span></div>
              <div className="proposal-who">
                {s.slotProviderName}
                {s.alternateProviderId && <span className="tag alt">alternate provider</span>}
              </div>
            </div>
            <Confidence value={s.confidence} />
          </div>
          <blockquote className="reasoning">“{s.reasoning}”</blockquote>
          <div className="queue-foot">
            <span className={`source ${isAi ? 'ai' : 'rule'}`} title={s.strategy}>
              {isAi ? '✦ AI' : s.fallback ? '⚙ Rule (AI fallback)' : '⚙ Rule'} · {fmtAgo(s.createdAt)}
            </span>
            <div className="actions">
              <button className="btn ghost" disabled={busy} onClick={() => onDecide(s.id, 'REJECTED')}>Reject</button>
              <button className="btn primary" disabled={busy} onClick={() => onDecide(s.id, 'ACCEPTED')}>
                {busy ? 'Working…' : 'Accept & rebook'}
              </button>
            </div>
          </div>
        </>
      ) : (
        <div className="queue-empty-suggestion">
          <span className="muted">No pending suggestion (previous one rejected).</span>
          <div className="actions">
            <button className="btn ghost" disabled={busy} onClick={() => onSuggest(appt.id)}>Suggest another</button>
            <button className="btn ai" disabled={busy} onClick={() => onStream(appt)}>✦ Stream AI</button>
          </div>
        </div>
      )}
    </div>
  );
}

export function ProviderList({ providers, appointments, busyId, onStatus }) {
  const load = (id) => appointments.filter((a) => a.providerId === id && a.status !== 'COMPLETED').length;
  return (
    <ul className="provider-list">
      {providers.map((p) => (
        <li key={p.id} className={`provider p-${p.status}`}>
          <span className={`dot ${p.status}`} />
          <div className="provider-main">
            <div className="provider-name">{p.name}</div>
            <div className="muted small">{p.specialty.toLowerCase()} · {load(p.id)} booked</div>
          </div>
          <select
            value={p.status}
            disabled={busyId === p.id}
            onChange={(e) => onStatus(p.id, e.target.value)}
            className={`status-select ${p.status}`}
          >
            <option value="AVAILABLE">Available</option>
            <option value="BUSY">Busy</option>
            <option value="OFFLINE">Offline</option>
          </select>
        </li>
      ))}
    </ul>
  );
}

/** Day view: one row per provider, slots as chips in time order. */
export function DayView({ providers, slots, onCancel, busyId }) {
  return (
    <div className="dayview">
      {providers.map((p) => {
        const mine = slots.filter((s) => s.providerId === p.id);
        return (
          <div key={p.id} className={`day-row ${p.status === 'OFFLINE' ? 'offline' : ''}`}>
            <div className="day-provider">
              <span className={`dot ${p.status}`} /> {p.name.replace('Dr. ', 'Dr. ')}
            </div>
            <div className="day-slots">
              {mine.length === 0 && <span className="muted small">No slots</span>}
              {mine.map((s) => (
                <div key={s.id} className={`slot-chip slot-${s.status}`} title={`${s.id} · ${s.durationMinutes} min`}>
                  <div className="slot-time">{fmtWhen(s.startTime)}</div>
                  <div className="slot-sub">{s.patientName || s.status.toLowerCase()}</div>
                  {s.status !== 'CANCELLED' && (
                    <button className="slot-x" disabled={busyId === s.id} title="Cancel slot" onClick={() => onCancel(s)}>
                      ✕
                    </button>
                  )}
                </div>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

export function AppointmentTable({ appointments, busyId, onSuggest, onStream, onComplete }) {
  return (
    <div className="table-wrap">
    <table className="table">
      <thead>
        <tr><th>Patient</th><th>Type</th><th>Provider</th><th>When</th><th>Status</th><th /></tr>
      </thead>
      <tbody>
        {appointments.map((a) => (
          <tr key={a.id} className={`row-${a.appointmentType}`}>
            <td><b>{a.patientName}</b><div className="muted small mono">{a.id}</div></td>
            <td><TypeTag type={a.appointmentType} /></td>
            <td>{a.providerName}{a.providerStatus === 'OFFLINE' && <span className="tag danger">offline</span>}</td>
            <td>{fmtWhen(a.slotStartTime)}{a.slotStatus === 'CANCELLED' && <span className="tag danger">cancelled</span>}</td>
            <td><StatusPill status={a.status} /></td>
            <td>
              {(a.status === 'CONFIRMED' || a.status === 'RESCHEDULED') && (
                <div className="row-actions">
                  <button className="btn tiny ghost" disabled={busyId === a.id} onClick={() => onSuggest(a.id)}>Suggest</button>
                  <button className="btn tiny ai" disabled={busyId === a.id} onClick={() => onStream(a)}>✦ Stream</button>
                  <button className="btn tiny ghost" disabled={busyId === a.id} onClick={() => onComplete(a.id)}>Complete</button>
                </div>
              )}
              {a.status === 'RESCHEDULE_PENDING' && <div className="row-actions muted small">Awaiting decision in queue ↑</div>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
    </div>
  );
}

export function ActivityFeed({ entries }) {
  if (!entries.length) return <div className="muted small">No agent activity yet.</div>;
  return (
    <ul className="activity">
      {entries.slice(0, 25).map((e, i) => (
        <li key={i} className={`act act-${e.level}`}>
          <span className="act-time mono">{fmtTime(e.at)}</span>
          <span>{e.message}</span>
        </li>
      ))}
    </ul>
  );
}

export function BookForm({ providers, slots, onBook }) {
  const [form, setForm] = useState({ patientName: '', appointmentType: 'ROUTINE', providerId: '', slotId: '' });
  const [busy, setBusy] = useState(false);
  const openSlots = slots.filter((s) => s.status === 'OPEN' && s.providerId === form.providerId);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value, ...(k === 'providerId' ? { slotId: '' } : {}) }));

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    const ok = await onBook(form);
    setBusy(false);
    if (ok) setForm((f) => ({ ...f, patientName: '', slotId: '' }));
  }

  return (
    <form className="book-form" onSubmit={submit}>
      <input placeholder="Patient name" value={form.patientName} onChange={set('patientName')} required />
      <select value={form.appointmentType} onChange={set('appointmentType')}>
        <option value="ROUTINE">Routine</option>
        <option value="URGENT">Urgent</option>
        <option value="FOLLOW_UP">Follow-up</option>
      </select>
      <select value={form.providerId} onChange={set('providerId')} required>
        <option value="">Provider…</option>
        {providers.filter((p) => p.status !== 'OFFLINE').map((p) => (
          <option key={p.id} value={p.id}>{p.name} ({p.specialty.toLowerCase()})</option>
        ))}
      </select>
      <select value={form.slotId} onChange={set('slotId')} required disabled={!form.providerId}>
        <option value="">{form.providerId && !openSlots.length ? 'No open slots' : 'Slot…'}</option>
        {openSlots.map((s) => <option key={s.id} value={s.id}>{fmtWhen(s.startTime)} · {s.durationMinutes}m</option>)}
      </select>
      <button className="btn primary" disabled={busy}>{busy ? 'Booking…' : 'Book appointment'}</button>
    </form>
  );
}

/** Live view of the SSE reasoning stream. */
export function StreamPanel({ stream, onClose, onDecide }) {
  if (!stream) return null;
  const s = stream.result;
  return (
    <div className="overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <div>
            <div className="eyebrow">✦ Live AI reasoning</div>
            <div className="patient">{stream.appt.patientName}</div>
          </div>
          <button className="btn ghost tiny" onClick={onClose}>Close</button>
        </div>
        <pre className="stream-box">{stream.text || (stream.done ? '' : 'Waiting for the model…')}{!stream.done && <span className="caret">▍</span>}</pre>
        {stream.error && <div className="error-inline">{stream.error}</div>}
        {s && (
          <div className="stream-result">
            <div className="proposal-when">{fmtWhen(s.slotStartTime)} with {s.slotProviderName}</div>
            <blockquote className="reasoning">“{s.reasoning}”</blockquote>
            <div className="queue-foot">
              <span className="source">{s.strategy} · {Math.round(s.confidence * 100)}%</span>
              {s.status === 'PENDING' && (
                <div className="actions">
                  <button className="btn ghost" onClick={() => onDecide(s.id, 'REJECTED')}>Reject</button>
                  <button className="btn primary" onClick={() => onDecide(s.id, 'ACCEPTED')}>Accept & rebook</button>
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

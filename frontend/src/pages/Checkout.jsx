import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api.js';

// Polling-based booking status. No WebSockets — the spec says polling is
// acceptable and we are not allowed to invent endpoints or contracts.
const POLL_MS = 1500;
const OTP_POLL_MS = 2000;
// The gateway delivers OTP after 2-15s and silently drops ~10%. Give it
// ~20s before we tell the user the code never arrived.
const OTP_TIMEOUT_MS = 20000;

export default function Checkout() {
  const { bookingRef } = useParams();
  const navigate = useNavigate();
  const [booking, setBooking] = useState(null);
  const [err, setErr] = useState(null);

  // OTP step state
  const [phone, setPhone] = useState('');
  const [otpSent, setOtpSent] = useState(false);
  const [otpDelivered, setOtpDelivered] = useState(null); // { code, attempts, ... }
  const [otpWaiting, setOtpWaiting] = useState(false);
  const [otpTimedOut, setOtpTimedOut] = useState(false);
  const [otpInput, setOtpInput] = useState('');
  const [otpVerified, setOtpVerified] = useState(false);
  const [otpErr, setOtpErr] = useState(null);
  const [busy, setBusy] = useState(false);
  const [paying, setPaying] = useState(false);

  async function refresh() {
    const r = await api.getBooking(bookingRef);
    if (!r.ok) {
      setErr(`Failed to load booking (${r.status})`);
      return;
    }
    setBooking(r.body);
    return r.body;
  }

  useEffect(() => {
    let cancelled = false;
    let timer = null;

    async function poll() {
      if (cancelled) return;
      const b = await refresh();
      if (cancelled) return;
      if (b && (b.status === 'CONFIRMED' || b.status === 'PAYMENT_FAILED' || b.status === 'EXPIRED')) {
        if (b.status === 'CONFIRMED') {
          navigate(`/bookings/${bookingRef}/confirmed`);
          return;
        }
        return;
      }
      timer = setTimeout(poll, POLL_MS);
    }
    poll();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [bookingRef, navigate]);

  // Poll for the OTP code while we're waiting for the gateway to deliver it.
  useEffect(() => {
    if (!otpSent || otpDelivered || otpTimedOut) return;
    let cancelled = false;
    let timer = null;
    const startedAt = Date.now();

    async function tick() {
      if (cancelled) return;
      if (Date.now() - startedAt > OTP_TIMEOUT_MS) {
        setOtpTimedOut(true);
        setOtpWaiting(false);
        setOtpErr('OTP was never delivered. Try sending it again.');
        return;
      }
      const r = await api.getOtpForBooking(bookingRef);
      if (!cancelled && r.ok && r.body && r.body.code) {
        setOtpDelivered(r.body);
        setOtpWaiting(false);
        return;
      }
      timer = setTimeout(tick, OTP_POLL_MS);
    }
    tick();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [otpSent, otpDelivered, otpTimedOut, bookingRef]);

  async function sendOtp() {
    setErr(null);
    setOtpErr(null);
    setOtpTimedOut(false);
    setOtpDelivered(null);
    setOtpVerified(false);
    setBusy(true);
    const r = await api.sendOtp(bookingRef, phone);
    setBusy(false);
    if (!r.ok) {
      setOtpErr(`Could not send OTP (${r.status})`);
      return;
    }
    setOtpSent(true);
    setOtpWaiting(true);
  }

  async function verifyOtp() {
    setOtpErr(null);
    setBusy(true);
    const r = await api.verifyOtp(bookingRef, otpInput.trim());
    setBusy(false);
    if (!r.ok || !r.body || !r.body.verified) {
      setOtpErr('Wrong or expired code. Try again.');
      // refresh attempts display
      const rr = await api.getOtpForBooking(bookingRef);
      if (rr.ok && rr.body) setOtpDelivered(rr.body);
      return;
    }
    setOtpVerified(true);
    setOtpDelivered((prev) => prev ? { ...prev, verified: true } : prev);
  }

  async function pay() {
    setErr(null);
    setPaying(true);
    const r = await api.pay(bookingRef);
    setPaying(false);
    if (!r.ok) {
      setErr(`Payment initiation failed (${r.status})`);
      return;
    }
    // The /pay endpoint returns 202 quickly. We rely on the booking-status
    // poller above to pick up the final state from the callback.
  }

  const showOtpStep = booking && booking.status === 'PENDING_PAYMENT' && !otpVerified;
  const showPayStep = otpVerified && booking && booking.status === 'PENDING_PAYMENT';

  return (
    <div>
      <h1>Checkout</h1>
      {err && <p className="error">{err}</p>}
      {booking && (
        <div className="card">
          <p>Booking ref: <strong>{booking.bookingRef}</strong></p>
          <p>Show: {booking.showId} • Seat: {booking.seatId}</p>
          <p>Amount: {booking.amount}</p>
          <p>
            Booking status:{' '}
            <span className={`status ${booking.status}`}>{booking.status}</span>
            {booking.paymentStatus && (
              <>
                {' '}• Payment:{' '}
                <span className={`status ${booking.paymentStatus}`}>{booking.paymentStatus}</span>
              </>
            )}
          </p>
        </div>
      )}

      {showOtpStep && (
        <div className="card">
          <h3>Verify your phone</h3>
          {!otpSent && (
            <>
              <p>We will text you a one-time code to confirm payment.</p>
              <input
                type="tel"
                placeholder="01XXXXXXXXX"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                style={inputStyle}
                disabled={busy}
              />
              <button onClick={sendOtp} disabled={busy || phone.trim().length < 6}>
                {busy ? 'Sending…' : 'Send OTP'}
              </button>
            </>
          )}

          {otpSent && (
            <>
              <p className="muted">
                Code sent to <strong>{phone}</strong>.
                {otpWaiting && ' Waiting for delivery…'}
              </p>

              {otpDelivered && (
                <p>
                  Delivered code (dev preview):{' '}
                  <strong>{otpDelivered.code}</strong>
                  {typeof otpDelivered.attempts === 'number' && (
                    <span className="muted"> • attempts: {otpDelivered.attempts}</span>
                  )}
                </p>
              )}

              {otpTimedOut && (
                <button className="secondary" onClick={sendOtp} disabled={busy}>
                  Resend OTP
                </button>
              )}

              {!otpTimedOut && (
                <div style={{ marginTop: '0.5rem' }}>
                  <input
                    type="text"
                    inputMode="numeric"
                    placeholder="Enter 6-digit code"
                    value={otpInput}
                    onChange={(e) => setOtpInput(e.target.value)}
                    style={inputStyle}
                    disabled={busy}
                  />
                  <button onClick={verifyOtp} disabled={busy || otpInput.trim().length < 4}>
                    {busy ? 'Verifying…' : 'Verify'}
                  </button>
                </div>
              )}

              {otpErr && <p className="error">{otpErr}</p>}
            </>
          )}
        </div>
      )}

      {showPayStep && (
        <div className="card">
          <h3>Confirm payment</h3>
          <p>Phone verified. You can now complete the payment.</p>
          <button onClick={pay} disabled={paying}>
            {paying ? 'Starting…' : 'Pay now'}
          </button>
        </div>
      )}

      {!booking && !err && <p className="muted">Loading…</p>}
    </div>
  );
}

const inputStyle = {
  padding: '0.5rem 0.7rem',
  border: '1px solid #ccc',
  borderRadius: 6,
  marginRight: '0.5rem',
  minWidth: 180,
};
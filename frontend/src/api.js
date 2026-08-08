// Thin API client. Endpoints and shapes are pinned to docs/API_CONTRACT.md.
//
// VITE_API_BASE_URL controls the API origin baked at build time:
//   - Docker build:        "/api"  -> nginx in this same image reverse-proxies /api/*
//                                     to the backend on the Compose network
//                                     (same origin, no CORS).
//   - Local dev (Vite):    "/api"  -> vite.config.js dev-server proxy forwards to
//                                     http://localhost:8080
//   - Standalone preview:  "http://localhost:8080"  -> absolute origin
//
// Anything that starts with "/" is treated as same-origin and joined with
// the current page URL (so it works whether the SPA is served from "/" or
// "/frontend/dist/" via Live Server).

const RAW_BASE =
  (typeof import.meta !== 'undefined' && import.meta.env && import.meta.env.VITE_API_BASE_URL) ||
  (typeof __API_BASE_URL__ !== 'undefined' ? __API_BASE_URL__ : '/api');

function buildBase(raw) {
  if (!raw) return '';
  // Absolute URL (http://...) — use as-is.
  if (/^https?:\/\//i.test(raw)) return raw.replace(/\/$/, '');
  // Anything else is treated as same-origin: join with current location,
  // preserving trailing slash semantics for direct concatenation.
  const here = typeof window !== 'undefined' && window.location
    ? window.location.origin
    : '';
  return (here + raw).replace(/\/$/, '');
}

const BASE = buildBase(RAW_BASE);

async function jsonFetch(url, options = {}) {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  });

  // Try to parse JSON regardless of status; some endpoints (e.g. 409) return
  // a structured error body that we want to surface.
  let body = null;
  try {
    body = await res.json();
  } catch (_) {
    body = null;
  }
  return { ok: res.ok, status: res.status, body };
}

export const api = {
  base: BASE,
  url(path) {
    const cleanBase = BASE.replace(/\/$/, '');
    if (cleanBase.endsWith('/api') && path.startsWith('/api/')) {
      return cleanBase.slice(0, -4) + path;
    }
    return cleanBase + path;
  },

  getMovies() {
    return jsonFetch(this.url('/api/movies'));
  },

  getMovieShows(movieId) {
    return jsonFetch(this.url(`/api/movies/${movieId}/shows`));
  },

  getSeatMap(showId) {
    return jsonFetch(this.url(`/api/shows/${showId}/seats`));
  },

  holdSeat(showId, showSeatId, userId) {
    return jsonFetch(this.url(`/api/shows/${showId}/seats/${showSeatId}/hold`), {
      method: 'POST',
      body: JSON.stringify({ userId }),
    });
  },

  getBooking(bookingRef) {
    return jsonFetch(this.url(`/api/bookings/${bookingRef}`));
  },

  pay(bookingRef) {
    return jsonFetch(this.url(`/api/bookings/${bookingRef}/pay`), {
      method: 'POST',
    });
  },

  sendOtp(bookingRef, phone) {
    return jsonFetch(this.url('/api/otp/send'), {
      method: 'POST',
      body: JSON.stringify({ ref: bookingRef, phone }),
    });
  },

  verifyOtp(bookingRef, code) {
    return jsonFetch(this.url('/api/otp/verify'), {
      method: 'POST',
      body: JSON.stringify({ ref: bookingRef, code }),
    });
  },

  getOtpForBooking(bookingRef) {
    return jsonFetch(this.url(`/api/otp/booking/${bookingRef}`));
  },

  getHealth() {
    return jsonFetch(this.url('/health'));
  },
};
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App.jsx';
import './styles.css';

// import.meta.env.BASE_URL is Vite's resolved base. It can be "./" (default)
// or "/some/subpath/" — both valid. BrowserRouter's `basename` prop requires
// a leading slash and no trailing slash, so we normalize it. If BASE_URL is
// missing or just "./", we fall back to "/".
function resolveBasename(rawBase) {
  if (!rawBase || rawBase === './' || rawBase === '.') return '/';
  // Strip trailing slash, ensure leading slash.
  let b = rawBase.replace(/\/+$/, '');
  if (!b.startsWith('/')) b = '/' + b;
  return b || '/';
}

const basename = resolveBasename(
  (typeof import.meta !== 'undefined' &&
    import.meta.env &&
    import.meta.env.BASE_URL) || '/'
);

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter basename={basename}>
      <App />
    </BrowserRouter>
  </React.StrictMode>
);
// Single source of truth for this deploy's URL prefix.
//
// Most deploys serve from the domain root ('/'). Some (e.g. a university
// server that only gives out a path under a shared host, like
// https://host/mrx/) need every asset path, router route, REST call, and
// WebSocket connection consistently prefixed. That prefix is controlled by
// one build-time flag — the BASE_PATH env var read in vite.config.ts — and
// exposed back to app code by Vite as import.meta.env.BASE_URL (always with
// a leading AND trailing slash, e.g. '/' or '/mrx/'). Nothing in this file
// needs to change when BASE_PATH changes; only the build-time env var does.
export const BASE_URL = import.meta.env.BASE_URL

// e.g. '/api' or '/mrx/api' — no trailing slash, callers append their own
// leading-slash path segment.
export const API_BASE = `${BASE_URL}api`

// e.g. '/ws' or '/mrx/ws'
export const WS_PATH = `${BASE_URL}ws`

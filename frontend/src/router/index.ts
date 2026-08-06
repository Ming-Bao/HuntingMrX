import { createRouter, createWebHistory } from 'vue-router'

// Lazy-loaded per route: GameBoardView pulls in maplibre-gl, which is the
// single biggest dependency in this app (see the Vite "chunk larger than
// 500 kB" build warning) — static imports here would put it in every page's
// initial load, including the landing/create/join pages that never touch a
// map. Dynamic import() gives each route its own chunk, fetched only when
// actually navigated to.
const LandingView   = () => import('../views/LandingView.vue')
const CreateGameView = () => import('../views/CreateGameView.vue')
const JoinGameView  = () => import('../views/JoinGameView.vue')
const LobbyView     = () => import('../views/LobbyView.vue')
const GameBoardView = () => import('../views/GameBoardView.vue')
const GameEndView   = () => import('../views/GameEndView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/',             component: LandingView },
    { path: '/create',       component: CreateGameView },
    { path: '/join',         component: JoinGameView },
    { path: '/lobby/:id',    component: LobbyView },
    { path: '/game/:id',     component: GameBoardView },
    { path: '/game/:id/end', component: GameEndView },
    // Shareable join link, e.g. https://vuw-mrx.xyz/WXYZ12 — same view as
    // /join, just pre-filled from the URL. Constrained to the 6-char join
    // code alphabet so it can't shadow any other single-segment route
    // (vue-router ranks static paths like /create above this anyway, but the
    // constraint also stops it swallowing unrelated typos/404s).
    { path: '/:code([A-Za-z0-9]{6})', component: JoinGameView },
  ]
})

export default router

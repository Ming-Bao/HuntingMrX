import { createRouter, createWebHistory } from 'vue-router'
import LandingView from '../views/LandingView.vue'
import CreateGameView from '../views/CreateGameView.vue'
import JoinGameView from '../views/JoinGameView.vue'
import LobbyView from '../views/LobbyView.vue'
import GameBoardView from '../views/GameBoardView.vue'
import GameEndView from '../views/GameEndView.vue'

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

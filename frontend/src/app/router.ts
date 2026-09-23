import { createRouter, createWebHistory } from 'vue-router'

// Each view is lazy-loaded so the landing, create and join pages don't
// download maplibre-gl, which only GameBoardPage needs.
export default createRouter({
  // '/' or the BASE_PATH sub-folder (e.g. '/mrx/'); the paths below don't need
  // to know which.
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/',             component: () => import('../pages/HomePage.vue') },
    { path: '/create',       component: () => import('../pages/CreateGamePage.vue') },
    { path: '/join',         component: () => import('../pages/JoinGamePage.vue') },
    { path: '/lobby/:id',    component: () => import('../pages/LobbyPage.vue') },
    { path: '/game/:id',     component: () => import('../pages/GameBoardPage.vue') },
    { path: '/game/:id/end', component: () => import('../pages/GameOverPage.vue') },
    // Shareable join link, e.g. https://vuw-mrx.xyz/WXYZ12: the join form with
    // the code filled in. Limited to 6 alphanumerics so it doesn't swallow typos.
    { path: '/:code([A-Za-z0-9]{6})', component: () => import('../pages/JoinGamePage.vue') },
  ],
})

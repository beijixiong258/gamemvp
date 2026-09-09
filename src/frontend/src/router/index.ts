import { createRouter, createWebHashHistory } from 'vue-router'
import { useGameStore } from '../stores/game'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'saves', component: () => import('../views/SavesView.vue') },
    { path: '/new', name: 'new', component: () => import('../views/CreateView.vue') },
    { path: '/save/:saveId', component: () => import('../views/SaveShell.vue'), children: [
      { path: '', name: 'game', component: () => import('../views/GameView.vue') },
      { path: 'exam/:examId', name: 'exam', component: () => import('../views/ExamView.vue') },
      { path: 'result', name: 'result', component: () => import('../views/ResultView.vue') },
    ] },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
router.beforeEach(() => !useGameStore().busy)

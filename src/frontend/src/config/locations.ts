import { assets } from './assets'
import type { Character } from '../types/game'

export interface Location {
  id: string; label: string; parentId: string | null; image: string; sceneCode?: string
  description: string; enabled: boolean
}
export const scenes = __GAME_SCENES__
export const locations: Location[] = [
  { id: 'county', label: '县城', parentId: null, image: assets.county, description: '街巷之间，书声与烟火相伴。选一处地方，继续今天的生活。', enabled: true },
  { id: 'home', label: '家中庭院', parentId: 'county', image: assets.home, description: '一方庭院，几间旧屋。你的人生，从这里开始。', enabled: true },
  { id: 'bedroom', label: '卧室', parentId: 'home', image: assets.bedroom, sceneCode: 'SCENE_JIA_WOSHI', description: '放下书卷，歇一歇。明日还有新的功课。', enabled: true },
  { id: 'study', label: '书房', parentId: 'home', image: assets.study, sceneCode: 'SCENE_JIA_SHUFANG', description: '窗前有笔墨，案上有书卷。父亲正在这里。', enabled: true },
  { id: 'parents-bedroom', label: '主卧', parentId: 'home', image: assets.parents, sceneCode: 'SCENE_JIA_ZHUWO', description: '这是父母的房间。母亲总有些日常的话想问你。', enabled: true },
  { id: 'school', label: '知微私塾', parentId: 'county', image: assets.school, description: '知微而见远。从识字明理，慢慢读懂这方天地。', enabled: true },
  { id: 'classroom', label: '讲堂', parentId: 'school', image: assets.classroom, sceneCode: 'SCENE_SISHU_JIANGTANG', description: '窦苞在讲堂授课，同窗嘉豪也在。先领好教材，再开始用功。', enabled: true },
  { id: 'library', label: '藏书阁', parentId: 'school', image: assets.library, sceneCode: 'SCENE_SISHU_CANGSHUGE', description: '书页有旧香。管书人沈砚在此，为你介绍书目与阅读条件。', enabled: true },
  { id: 'bookshop', label: '陆记书铺', parentId: 'county', image: assets.bookshop, sceneCode: 'SCENE_JISHI', description: '陆掌柜做现钱现货的买卖，偶尔也收来几本离奇的书。', enabled: true },
  { id: 'yamen', label: '县衙', parentId: 'county', image: assets.county, description: '县衙尚未开放。', enabled: false },
]
export const getLocation = (id?: string) => locations.find(l => l.id === id && l.enabled) ?? locations[1]!
export const getScene = (location: Location) => scenes.find(s => s.sceneCode === location.sceneCode)
export const allows = (location: Location, action: string) => getScene(location)?.availableActionCode.includes(action) ?? false
export const childrenOf = (id: string) => locations.filter(l => l.parentId === id)
export const npcLocation = (npc: Character) => locations.find(l => getScene(l)?.availableNpcCode.includes(npc.npcCode ?? ''))
export const roleName = (code: string | null) => ({
  NPC_XIANSHENG: '塾师', NPC_GUANSHU: '管书人', NPC_JIAHAO: '同窗',
  NPC_FUQIN: '父亲', NPC_MUQIN: '母亲', NPC_SHANGREN: '书商', NPC_XIANSHI_KAOGUAN: '考官',
}[code ?? ''] ?? '人物')
export const examName = (type: string) => ({ EXAM_MENGXUE: '蒙学考', EXAM_JINGYI: '经义考', EXAM_PRE_COUNTY: '县试预考', EXAM_XIANSHI: '县试' }[type] ?? '考试')
export const statusName = (status: string) => ({ STUDYING: '求学中', STAGE_EXAM_READY: '待阶段考试', EXAM_READY: '待县试', COMPLETED: '本局已结束' }[status] ?? status)

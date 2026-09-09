import county from '@images/scenes/county-street.png'
import home from '@images/scenes/home-courtyard.png'
import bedroom from '@images/scenes/bedroom.png'
import study from '@images/scenes/study.png'
import parents from '@images/scenes/parents-bedroom.png'
import school from '@images/scenes/school-courtyard.png'
import classroom from '@images/scenes/classroom.png'
import library from '@images/scenes/library.png'
import bookshop from '@images/scenes/bookshop.png'
import exam from '@images/scenes/county-exam.png'
import douBao from '@images/characters/dou-bao.png'
import shenYan from '@images/characters/shen-yan.png'
import avatar from '@images/characters/default-avatar.svg'
import book from '@images/equipment/threadbound-book.png'

export const assets = { county, home, bedroom, study, parents, school, classroom, library, bookshop, exam, douBao, shenYan, avatar, book }
export const portrait = (npcCode?: string | null) => npcCode === 'NPC_XIANSHENG' ? douBao : npcCode === 'NPC_GUANSHU' ? shenYan : avatar

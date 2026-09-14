import type { ReadingReward } from '../types/game'

const labels: Record<keyof ReadingReward, string> = {
  characterZhili: '智力', characterDaode: '道德', characterZhengzhi: '政治', characterJiaoji: '交际', characterTineng: '体能',
  abilityShizi: '识字', abilityJingyi: '经义', abilityWenzhang: '文章', abilityCelun: '策论', abilityWenxue: '文学',
}
export function readingRewardEntries(reward?: ReadingReward) {
  return (Object.keys(labels) as (keyof ReadingReward)[])
    .map(key => ({ label: labels[key], value: reward?.[key] ?? 0 })).filter(entry => entry.value > 0)
}
export function formatReadingReward(reward?: ReadingReward) {
  return readingRewardEntries(reward).map(entry => entry.label + ' +' + entry.value).join('、')
}
# 豪杰成长计划plus · 前端

Vue 3 + TypeScript 网页客户端，与 Java 后端共用当前仓库。日常手动启动见[启动与部署](../../docs/开发者手册/启动与部署.md)，页面规则见[前端页面与交互](../../docs/MVP/前端页面与交互.md)。

## 技术与源码

Composition API、Vite、Vue Router、Pinia、原生 CSS 与 fetch；npm 包名 `haojie-chengzhang-jihua-plus-frontend`，依赖及 Node 要求以 [package.json](package.json) 和锁文件为准，不另设项目版本。

| 文件 | 职责 |
| --- | --- |
| [vite.config.ts](vite.config.ts) | 5173 开发服务、4173 本机预览、8080 API 转发、图片别名与场景注入 |
| [api/http.ts](src/api/http.ts) | JSON 请求、超时、业务与连接错误 |
| [types/game.ts](src/types/game.ts) | 按 Java record／实体映射响应，不假定统一 code/data 包装 |
| [stores/game.ts](src/stores/game.ts)、[draft.ts](src/stores/draft.ts) | 存档、业务请求、恢复和按用途保存草稿 |
| [config/locations.ts](src/config/locations.ts)、[assets.ts](src/config/assets.ts) | 地点树、NPC 房间及正式素材导入 |
| [router](src/router/index.ts)、[views](src/views) | 路由、存档加载及五类页面 |
| [components](src/components)、[styles](src/styles/main.css) | 面板、共用请求提示和响应式水墨界面 |

`npm.cmd run dev` 启动开发服务，`type-check` 做 Vue/TS 与 Vite 配置静态检查，`build` 先检查再生成 dist，`preview` 预览已有产物。构建不清空旧 dist；干净交付前将旧产物移入指定垃圾桶。Vite 转译不替代类型检查。[Vue TypeScript](https://cn.vuejs.org/guide/typescript/overview)

## 场景与图片

`regionId` 是行政地区，`locationId` 是界面地点，`sceneCode` 是业务场景。县城和庭院只导航；具体房间、书铺提交行动，县试随考试流程进入，县衙未开放。

Vite 从 `../main/resources/game/scene.json` 读取并注入 `__GAME_SCENES__`，页面据此显示行动和 NPC，不另维护业务许可表；改资源后重启 Vite，发布时前后端使用同一源码状态。服务端仍独立校验，旧场景仅保留历史兼容。

`@images` 指向唯一的 [images](../main/resources/images/README.md) 目录，通过静态导入取得浏览器 URL。该目录不是后端默认公开静态目录，不能直接使用本机路径或假定存在 `/images` 接口。

## 路由与状态

使用 [Hash 路由](https://router.vuejs.org/guide/essentials/history-mode.html#hash-mode)，刷新不要求服务端逐路由回退：

| 路由 | 内容 |
| --- | --- |
| `/#/`、`/#/new` | 存档列表、开局 |
| `/#/save/:saveId?place=home` | 成长与地点 |
| `/#/save/:saveId/exam/:examId` | 待考与已完成试卷 |
| `/#/save/:saveId/result` | 本局结束 |

SaveShell 进入存档先 POST content 再 GET 详情，同步固定目录、补缺失人物与已识别的旧模板姓名，保留定义 ID 和存档历史，不发书或重置成长。状态以服务端为准：玩家 READY 考试优先，COMPLETED 进入结果；写请求结束后重新判断，地点记录不能覆盖业务状态。考试通过与否直接读 `COMPLETED_PASS/COMPLETED_FAIL`。

浏览不耗回合，重病回合由后端结算。弹层支持焦点约束、Esc 与恢复焦点，收起对话不发送结束；主动结束发送空文本，未发送草稿不进入发言历史；草稿、地点、对话 ID、同回合体会题和待确认请求保存在当前浏览器。

## 人物与物品面板

详情的 `npcs` 已由后端过滤到当前有效人物；固定人物按场景配置分组，动态人物使用 `currentSceneCode`，不由页面猜测名字或身份。L0／L1 的到期和活跃对话保护均由后端决定，页面刷新和查询不延长保留时间。

`sceneItems` 提供本局有效场景物件，主界面按当前 `sceneCode` 显示“身边小物”。拾取调用 `/equipment/{saveId}/{characterId}/pickup`，传原 `itemId`、场景和最后读取的回合；成功后同一实例从场景进入行囊。`backpack` 中公共物品按定义汇总，动态小物保留独立 `itemId` 与说明。

`supplies` 使用 `SupplyOffer`：`equipment` 为公共定义，`sceneCode` 为交易地点，`ownedQuantity` 为真实持有数，`canAcquire` 与 `blockedReasons` 表示规则和资金限制。书铺杂物面板显示清茶，免费教材入口依据后端限领状态。前端不维护一套独立价格或库存。

行囊按 `useEffectCode` 展示固定用途和使用按钮，`usable` 决定当前是否可用；清茶疲劳为 0 时保留用途说明、禁用按钮并显示“此刻精神充足”，服务端也会拒绝。使用时传 `itemId`、具体场景和当前回合到 `/use`，单次只消费一份，成功后重读详情。未进入具体房间或书铺时禁用使用并提示先选择地点；动态小物的叙事文字不生成数值效果按钮。

## 请求恢复

- 业务写入前持久化完整路径、正文及生成一次的 requestId。未确认时禁止新写入，网络失败、超时、408、429 或服务错误保留原请求，重试原样发送；不自动重发全部 POST。
- 拾取、使用和目录获取复用同一业务请求恢复流程。提交读书体会沿用服务端 questionId，考试沿用 examId；接口签名见[后端开发](../../docs/开发者手册/后端开发.md#http-接口)。
- POST 成功即清除待确认请求，再读最新进度；后续 GET 失败只重读。409 重新读取存档和对话，保留草稿；明确的参数错误允许修改再提交。过期读档响应不覆盖随后选中的存档。
- 开局接口没有 requestId 去重，发送前记录结果待确认；结果不明时先核对存档列表，玩家明确核对后才允许再次开局。本地存储不可写时拒绝发起无法记录的写操作。
- GET 上限 20 秒，POST 与 Vite 转发上限 180 秒。浏览器超时不代表后端撤销；显示等待、错误及适用的原请求重试／刷新入口，弹层内也能操作。
- 刷新后按 ID 重读对话；体会题只恢复同回合缓存。换浏览器或清空本地存储后，当前没有查询全部未结束对话的接口，完整游戏事实及记忆正文仍在后端。

前端开发／预览仅绑定回环地址，端口占用直接报错；`/api` 原样转发到 `http://127.0.0.1:8080`。[Vite proxy](https://vite.dev/config/server-options.html#server-proxy)

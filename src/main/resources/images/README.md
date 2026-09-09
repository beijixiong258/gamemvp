# 豪杰成长计划plus · 图片素材

正式原图只存本目录，通过前端 `@images` 静态导入；路径、用途和完整生成提示词在 [manifest.json](manifest.json)。场景及人物规则见[前端页面与交互](../../../../docs/MVP/前端页面与交互.md)，接入方式见[前端说明](../../../frontend/README.md#场景与图片)。

| 场景文件 | 地点 |
| --- | --- |
| [county-street.png](scenes/county-street.png) | 县城 |
| [home-courtyard.png](scenes/home-courtyard.png) | 家中庭院 |
| [bedroom.png](scenes/bedroom.png) | 玩家卧室 |
| [study.png](scenes/study.png) | 书房 |
| [parents-bedroom.png](scenes/parents-bedroom.png) | 父母主卧 |
| [school-courtyard.png](scenes/school-courtyard.png) | 知微私塾庭院 |
| [classroom.png](scenes/classroom.png) | 讲堂、阶段考试 |
| [library.png](scenes/library.png) | 藏书阁 |
| [bookshop.png](scenes/bookshop.png) | 陆记书铺 |
| [county-exam.png](scenes/county-exam.png) | 县试考场 |

| 人物／装备文件 | 对应内容 |
| --- | --- |
| [dou-bao.png](characters/dou-bao.png) | 窦苞，`NPC_XIANSHENG` |
| [shen-yan.png](characters/shen-yan.png) | 沈砚，`NPC_GUANSHU` |
| [default-avatar.svg](characters/default-avatar.svg) | 玩家及无专属画像者 |
| [threadbound-book.png](equipment/threadbound-book.png) | 通用线装书，书名与品质由界面显示 |

共 14 份：10 张 1672×941 场景 PNG、3 张 1254×1254 人物／书籍 PNG、1 个 256×256 视口 SVG。人物为米白底半身像，非透明站立图；默认头像是项目 SVG，其余由图像工具生成。

采用明代广东生活场景的淡彩水墨风格，暖米白、青灰、木色、靛蓝。固定背景无人物、按钮或文字，等比覆盖裁切；实际县名由界面显示。人物独立展示，正文面板提供对比度，运行时不重新生成或依赖外部图片站。县衙尚未开放，不制作内部场景。

窦苞头像参考[豆包产品形象](https://m.baike.com/wikiid/7293085049381060619)，源地址保留在清单中，下载参考原图不作运行素材。新图沿用小写英文名及 scenes／characters／equipment 分类；场景以家中庭院为画风参考，人物参照窦苞笔触但保留各自外貌。

清单仅索引素材，实际权限来自 game/scene.json。新增或替换图片时同步用途与提示词，前端不复制另一套正式图片。

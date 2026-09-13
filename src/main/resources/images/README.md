# 豪杰成长计划plus · 图片素材

正式原图只存本目录，通过前端 `@images` 静态导入；文件路径与用途见下表。场景及人物规则见[前端页面与交互](../../../../docs/MVP/前端页面与交互.md)，接入方式见[前端说明](../../../frontend/README.md#场景与图片)。

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
| [dou-bao.png](characters/dou-bao.png) | 窦苞，`NPC_DOUBAO` |
| [shen-yan.png](characters/shen-yan.png) | 沈砚，`NPC_GUANSHU` |
| [unnamed-mechanist.png](characters/unnamed-mechanist.png) | 未命名机关师，最终立绘；尚未接入 |
| [default-avatar.svg](characters/default-avatar.svg) | 玩家及无专属画像者 |
| [threadbound-book.png](equipment/threadbound-book.png) | 通用线装书，书名与品质由界面显示 |

未命名机关师的作品集存于 [equipment/unnamed-mechanist](equipment/unnamed-mechanist/)，与人物立绘一起收录，尚未配置 NPC、地点或道具绑定。前六件作品文件名使用不带连字符的拼音，镇店之宝为 `masterpiece.png`。

| 作品文件 | 内容 |
| --- | --- |
| [jiguanque.png](equipment/unnamed-mechanist/jiguanque.png) | 机关雀 |
| [kaiheliandeng.png](equipment/unnamed-mechanist/kaiheliandeng.png) | 开合莲灯 |
| [zhankaizhuangxia.png](equipment/unnamed-mechanist/zhankaizhuangxia.png) | 展开妆匣 |
| [houyuehuachuang.png](equipment/unnamed-mechanist/houyuehuachuang.png) | 候月花窗 |
| [fengchamuhe.png](equipment/unnamed-mechanist/fengchamuhe.png) | 奉茶木鹤 |
| [guizhoushuijing.png](equipment/unnamed-mechanist/guizhoushuijing.png) | 归舟水景 |
| [masterpiece.png](equipment/unnamed-mechanist/masterpiece.png) | 镇店之宝合图：短剑与歼-20；原造型、古漆木纹与旧铜质感、木制起落架轮子 |

人物暂未起名，`unnamed-mechanist` 仅为目录和文件占位标识。设定为明代宁波的机关术大师、神秘小店老板，带隐士属性，不参与政治、不接军工订单、不涉江湖争斗，店铺只卖有缘人。立绘采用黑色长卷发、双紫瞳、轻盈华丽紫衣与白丝袜，保留略带高冷的神态；收录的是自然站姿、脚部色调精修后的最后一张图。

共 22 份：原有 14 份游戏素材，加 1 张 1024×1536 人物立绘、6 张 1254×1254 作品图与 1 张 1672×941 镇店之宝合图。已接入人物为米白底半身像；新增立绘含小店背景，未裁成头像。默认头像是项目 SVG，其余由图像工具生成。

现有游戏场景采用明代广东生活场景的淡彩水墨风格，暖米白、青灰、木色、靛蓝；新增宁波人物与作品沿用手绘笔触，并增加紫色、古漆、木纹与旧铜细节。固定游戏背景无人物、按钮或文字，等比覆盖裁切；实际县名由界面显示。人物独立展示，正文面板提供对比度，运行时不重新生成或依赖外部图片站。县衙尚未开放，不制作内部场景。

窦苞头像参考[豆包产品形象](https://m.baike.com/wikiid/7293085049381060619)，下载的参考原图不作运行素材。新图沿用小写拉丁字母文件名及 scenes／characters／equipment 分类；场景以家中庭院为画风参考，人物参照窦苞笔触但保留各自外貌。

实际场景权限来自 [game/scene.json](../game/scene.json)。新增或替换图片时同步本页的文件路径与用途，前端不复制另一套正式图片。

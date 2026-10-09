package mvp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import mvp.engine.CharacterEngine;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName("game_character")
public class Character {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 人物ID
    private String saveId; // 人物所属的游戏存档ID
    private String name; // 人物姓名
    private int type;//1为人类玩家，0为NPC。只要不是人类玩家控制的就都是NPC
    private String npcCode; // NPC模板编码；玩家和动态NPC为空
    private String retentionLevel = "L2"; // L0临时身份缓存，L1短期保留，L2长期身份
    private Long expiresAtTurn; // 到此累计回合退出活跃；L2为空
    private String currentSceneCode; // 动态NPC所在场景，固定NPC使用场景配置
    private Boolean archived = false; // 归档后保留身份和历史引用，不再日常出场
    private String retentionReason; // 最近一次实际互动确认的保留依据
    private Integer wallet; // 当前可支配资金，单位为文，与初始家庭背景独立
    private Integer sickTurnsRemaining; // 重病尚需强制经过的回合
    private String officialPosition; // 当前官职名称，尚无任职时为空
    private String officialRank; // 当前官职级别
    private String degree; // 已取得的学位或科举功名
    private String titlesJson; // 可同时持有的地位称号与头衔JSON数组
    private String birthRegionId; // 出生地区ID
    private String currentRegionId; // 当前所在地区ID
    private Integer characterZhili; //智力
    private Integer characterDaode; //道德
    private Integer characterZhengzhi; //政治
    private Integer characterJiaoji; //交际
    private Integer characterTineng; //体能
    private Integer characterJiankang; // 角色当前健康值
    private Integer characterPilao; // 已消耗体力；当前体力由上限减去此值计算
    private Long majorActionTurn; // 最近使用主要行动额度的累计回合
    private Long aiGrowthTurn; // 最近领取AI正向成长的累计回合
    // Hutool按字段发现Bean属性；保留展示字段以便缓存回执也输出动态体力，数据库不存储。
    @TableField(exist = false)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    private int stamina;
    @TableField(exist = false)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    private int maxStamina;
    private String birthday; // 人物在游戏纪年中的生日
    private String personalitySummary; // 人物性格与行为倾向摘要
    private String currentState; // 人物当前可供游戏逻辑和AI读取的状态摘要
    private String availableStageCodeJson; // 人物可以参与的成长阶段编码JSON数组
    private Boolean enabled; // 人物当前是否启用

    public int getMaxStamina() {
        return CharacterEngine.maxStamina(characterTineng == null ? 0 : characterTineng);
    }

    public int getStamina() {
        return Math.max(0, getMaxStamina() - Math.max(0, characterPilao == null ? 0 : characterPilao));
    }
}

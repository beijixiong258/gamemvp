package mvp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName("equipment_definition")
public class Equipment {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id; // 装备定义ID
    private String equipmentCode; // 装备在程序和接口中使用的稳定编码
    private String equipmentName; // 装备显示名称
    private String equipmentType; // 装备类型编码，例如书籍
    private String rarityCode; // 全装备通用品质编码，取值见Rarity
    private Integer price; // 装备价格，单位为文
    private String supplierNpcCode; // 当前提供获取行为的NPC模板编码
    private String description; // 装备基础介绍

    public String getRarityName() {
        return Rarity.valueOf(rarityCode).displayName;
    }

    public String getRarityColor() {
        return Rarity.valueOf(rarityCode).color;
    }

    /** 品质名称与显示颜色由编码统一推导，不在数据库中重复保存。 */
    public enum Rarity {
        COMMON("普通", "#FFFFFF"),
        UNCOMMON("优良", "#22C55E"),
        RARE("稀有", "#3B82F6"),
        EPIC("史诗", "#A855F7"),
        LEGENDARY("传说", "#FFD700");

        private final String displayName;
        private final String color;

        Rarity(String displayName, String color) {
            this.displayName = displayName;
            this.color = color;
        }
    }
}

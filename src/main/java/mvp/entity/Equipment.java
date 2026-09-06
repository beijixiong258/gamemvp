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
    private String rarityCode; // 装备稀有度编码
    private Integer price; // 装备价格，单位为文
    private String supplierNpcCode; // 当前提供获取行为的NPC模板编码
    private String description; // 装备基础介绍
}

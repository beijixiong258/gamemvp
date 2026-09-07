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
@TableName("region")
public class Region {
    @TableId(type = IdType.INPUT)
    private String id; // 地区ID
    private String parentId; // 上级行政区ID，根节点为空
    private String regionName; // 地区显示名称
    private Boolean enabled; // 是否启用
}

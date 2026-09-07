package mvp.utils;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.stereotype.Component;

@Component
public class ClasspathJsonLoader {

    /**
     * 把classpath下的JSON资源读取为指定类型。
     *
     * @param resourcePath 相对于classpath根目录的资源路径
     * @param type 目标Java类型
     * @param <T> 目标类型
     * @return JSON反序列化后的对象
     */
    public <T> T load(String resourcePath, Class<T> type) {
        try {
            String json = ResourceUtil.readUtf8Str(resourcePath);
            return JSONUtil.toBean(json, type);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("无法读取JSON资源：" + resourcePath, exception);
        }
    }
}

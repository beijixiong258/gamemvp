package mvp.ai;

import mvp.utils.ClasspathJsonLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class GameClient {

    private static final String PROMPT_RESOURCE_PATH = "prompt/prompt.json";

    private final ChatClient chatClient;
    private final Map<String, PromptDefinition> prompts;

    public GameClient(ChatClient.Builder builder, ClasspathJsonLoader jsonLoader) {
        this.chatClient = builder.build();
        PromptResource resource = jsonLoader.load(PROMPT_RESOURCE_PATH, PromptResource.class);
        this.prompts = resource.prompt().stream()
                .collect(Collectors.toUnmodifiableMap(PromptDefinition::promptCode, Function.identity()));
    }

    /**
     * 按提示词编码执行一次结构化模型调用，供自由行动、对话、读书和考试服务复用。
     *
     * @param promptCode prompt.json中的提示词编码
     * @param userText 本次任务的动态输入和游戏上下文
     * @param type 期望模型映射成的结构化结果类型
     * @param <T> 结构化结果类型
     * @return 模型响应映射后的结构化结果
     */
    public <T> T chat(String promptCode, String userText, Class<T> type) {
        PromptDefinition definition = getPrompt(promptCode);
        Prompt prompt = new Prompt(
                new SystemMessage(definition.systemText()),
                new UserMessage(userText)
        );
        try {
            T result = chatClient.prompt(prompt).call().entity(type);
            if (result == null) {
                throw new IllegalStateException("模型未返回有效内容");
            }
            return result;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI调用失败，请检查网络或模型账户余额后重试", exception);
        }
    }

    private PromptDefinition getPrompt(String promptCode) {
        PromptDefinition prompt = prompts.get(promptCode);
        if (prompt == null) {
            throw new IllegalArgumentException("不存在提示词配置：" + promptCode);
        }
        return prompt;
    }

    private record PromptResource(List<PromptDefinition> prompt) {
    }

    private record PromptDefinition(
            String promptCode,
            String systemText
    ) {
    }
}

package mvp.ai;

import mvp.utils.ClasspathJsonLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class GameClient {

    private static final String PROMPT_RESOURCE_PATH = "prompt/prompt.json";

    private final ChatClient chatClient;
    private final Map<String, String> prompts;

    public GameClient(ChatClient.Builder builder, ClasspathJsonLoader jsonLoader) {
        this.chatClient = builder.build();
        PromptResource resource = jsonLoader.load(PROMPT_RESOURCE_PATH, PromptResource.class);
        this.prompts = resource.prompt().stream()
                .collect(Collectors.toUnmodifiableMap(PromptDefinition::promptCode,
                        definition -> buildSystemText(resource, definition)));
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
        Prompt prompt = new Prompt(
                new SystemMessage(getPrompt(promptCode)),
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

    private String getPrompt(String promptCode) {
        String prompt = prompts.get(promptCode);
        if (prompt == null) {
            throw new IllegalArgumentException("不存在提示词配置：" + promptCode);
        }
        return prompt;
    }

    /** 启动时复用公共规则组，按背景、角色、任务、指示组装完整提示词。 */
    private static String buildSystemText(PromptResource resource, PromptDefinition definition) {
        List<String> rules = new ArrayList<>(resource.commonRules());
        for (String ref : definition.ruleRefs()) {
            List<String> sharedRules = resource.ruleGroups().get(ref);
            if (sharedRules == null) {
                throw new IllegalArgumentException("提示词" + definition.promptCode() + "引用了不存在的规则组：" + ref);
            }
            rules.addAll(sharedRules);
        }
        rules.addAll(definition.rules());
        return String.join("\n\n",
                "一、游戏背景\n" + resource.gameBackground(),
                "二、智能体角色\n" + definition.role(),
                "三、任务\n" + definition.task(),
                "四、具体指示\n- " + String.join("\n- ", rules));
    }

    private record PromptResource(
            String gameBackground,
            List<String> commonRules,
            Map<String, List<String>> ruleGroups,
            List<PromptDefinition> prompt
    ) {
    }

    private record PromptDefinition(
            String promptCode,
            String role,
            String task,
            List<String> ruleRefs,
            List<String> rules
    ) {
    }
}

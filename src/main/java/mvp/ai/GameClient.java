package mvp.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
public class GameClient {

    private final ChatClient chatClient;

    public GameClient(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public <T> T chat(Prompt prompt, Class<T> type, Object... tool) {
        return chatClient.prompt(prompt)
                .tools(tool)
                .call()
                .entity(type);
    }
}

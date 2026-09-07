package mvp.ai;

import mvp.service.impl.ExamRecordServiceImpl.ThoughtOutput;
import mvp.utils.ClasspathJsonLoader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GameClientTest {
    @Test
    void structuredOutputRetainsSystemRulesAndPlayerContext() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("{\"thought\":\"先想清题意。\"}")))));
        GameClient client = new GameClient(ChatClient.builder(model), new ClasspathJsonLoader());

        assertEquals("先想清题意。", client.chat("PROMPT_EXAM_THOUGHT", "{\"learnedBooks\":[]}", ThoughtOutput.class).thought());

        var sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(sent.capture());
        String system = sent.getValue().getInstructions().stream()
                .filter(message -> message.getMessageType() == MessageType.SYSTEM)
                .map(message -> message.getText()).reduce("", (left, right) -> left + right);
        assertTrue(system.contains("用考生第一人称自然地思考这道题"), system);
        assertTrue(system.contains("没有已读书籍时"), system);
        assertTrue(sent.getValue().getInstructions().stream()
                .anyMatch(message -> message.getText().contains("\"learnedBooks\":[]")));
    }
}

package mvp.controller;

import mvp.service.GameSaveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ApiExceptionHandlerTest {
    private GameSaveService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(GameSaveService.class);
        mvc = standaloneSetup(new TurnController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void conflictReturnsSpecificReasonInsteadOfGenericStatusOnly() throws Exception {
        when(service.endTurn(eq("save"), any())).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "请先结束或离开当前对话"));
        mvc.perform(post("/api/turn/save/end-turn").accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"next\",\"expectedTurnNumber\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("请先结束或离开当前对话"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void rejectedAiResultCanBeDiscardedWithoutLeakingItsCause() throws Exception {
        when(service.endTurn(eq("save"), any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "AI行动处理失败，本次未结算，请稍后重试", new IllegalStateException("internal-secret")));
        mvc.perform(post("/api/turn/save/end-turn").accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"next\",\"expectedTurnNumber\":0}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("AI行动处理失败，本次未结算，请稍后重试"))
                .andExpect(jsonPath("$.requestRejected").value(true))
                .andExpect(content().string(not(containsString("internal-secret"))));
    }

    @Test
    void unknownServerFailureIsNotMarkedAsRejected() throws Exception {
        when(service.endTurn(eq("save"), any())).thenThrow(
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
        mvc.perform(post("/api/turn/save/end-turn").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"next\",\"expectedTurnNumber\":0}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.requestRejected").doesNotExist());
    }
}

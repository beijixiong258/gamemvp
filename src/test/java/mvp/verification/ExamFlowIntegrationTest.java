package mvp.verification;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import mvp.MvpApplication;
import mvp.ai.FreeActionResolver.FreeActionResolution;
import mvp.ai.GameClient;
import mvp.engine.CharacterEngine.DriverPatch;
import mvp.entity.GameSave;
import mvp.service.EquipmentRecordService.AcquisitionIntent;
import mvp.service.ExamRecordService;
import mvp.service.GameSaveService;
import mvp.service.impl.ExamRecordServiceImpl.EvaluationOutput;
import mvp.service.impl.ExamRecordServiceImpl.NarrativeOutput;
import mvp.service.impl.ExamRecordServiceImpl.ThoughtOutput;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 在独立临时库内运行真实HTTP与事务验证；关闭应用后删除该库。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "MVP_INTEGRATION_TEST", matches = "true")
class ExamFlowIntegrationTest {
    private ConfigurableApplicationContext context;
    private GameClient ai;
    private JdbcTemplate jdbc;
    private String serverUrl;
    private String rootJdbcUrl;
    private String user;
    private String password;
    private String database;
    private String birthRegionId;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @TestConfiguration(proxyBeanMethods = false)
    static class AiControl {
        @Bean
        @Primary
        GameClient verificationGameClient() {
            return mock(GameClient.class);
        }
    }

    @BeforeAll
    void startApplicationWithIsolatedDatabase() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        var environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new PropertiesPropertySource("application", yaml.getObject()));
        var dotenv = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) {
            dotenv.load(reader);
        }
        environment.getPropertySources().addFirst(new PropertiesPropertySource("dotenv", dotenv));
        String configuredUrl = environment.getRequiredProperty("spring.datasource.url");
        user = environment.getRequiredProperty("spring.datasource.username");
        password = environment.getRequiredProperty("spring.datasource.password");
        database = "mvp_verify_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String prefix = configuredUrl.substring(0, configuredUrl.indexOf("/mvp"));
        String query = configuredUrl.substring(configuredUrl.indexOf('?'));
        rootJdbcUrl = prefix + "/" + query + "&connectTimeout=10000";
        String testUrl = prefix + "/" + database + query;
        String quote = Character.toString(96);
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"), StandardCharsets.UTF_8)
                .replace(quote + "mvp" + quote, quote + database + quote);
        try (var connection = DriverManager.getConnection(rootJdbcUrl, user, password)) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(schema.getBytes(StandardCharsets.UTF_8)));
        }
        try {
            context = new SpringApplication(MvpApplication.class, AiControl.class).run(
                    "--server.port=0", "--spring.datasource.url=" + testUrl,
                    "--spring.main.banner-mode=off", "--logging.level.root=WARN", "--server.error.include-message=always",
                    "--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl");
            ai = context.getBean("verificationGameClient", GameClient.class);
            jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            serverUrl = "http://127.0.0.1:" + context.getEnvironment().getRequiredProperty("local.server.port");
            birthRegionId = JSONUtil.parseArray(get("/api/save/birth-regions").body()).getJSONObject(0).getStr("id");
            System.out.println("INTEGRATION_DATABASE=" + database);
        } catch (Throwable failure) {
            cleanup();
            throw failure;
        }
    }

    @BeforeEach
    void reportTest(TestInfo info) {
        System.out.println("VERIFYING=" + info.getDisplayName());
    }

    @BeforeEach
    void configurePredictableModel() {
        reset(ai);
        doAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "PROMPT_EXAM_THOUGHT" -> new ThoughtOutput("先从所读篇目的大意入手，再联系生活中的道理。");
            case "PROMPT_EXAM_EVALUATION" -> new EvaluationOutput(new BigDecimal("30"), "切题，表述清楚。");
            case "PROMPT_EXAM_ANSWER" -> new NarrativeOutput("读书使人明理，修身应当落实在日常待人接物之中。", "本次表现依照程序确定的成绩结算。");
            default -> throw new AssertionError("Unexpected prompt: " + invocation.getArgument(0));
        }).when(ai).chat(anyString(), anyString(), any());
    }

    @AfterAll
    void cleanup() throws Exception {
        if (context != null) {
            context.close();
            context = null;
        }
        if (database != null) {
            assertTrue(database.matches("mvp_verify_[a-f0-9]{12}"));
            try (var connection = DriverManager.getConnection(rootJdbcUrl, user, password);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE IF EXISTS " + database);
            }
            System.out.println("CLEANED_DATABASE=" + database);
            database = null;
        }
    }

    @Test
    @Order(1)
    void thoughtIsCachedAndDoesNotSettleOrAdvance() throws Exception {
        var fixture = readyExam();
        var first = success(post(examPath(fixture, "thought"), null));
        var second = success(post(examPath(fixture, "thought"), null));
        assertEquals(first.getStr("aiThoughtBubble"), second.getStr("aiThoughtBubble"));
        assertEquals(96L, turn(fixture.saveId()));
        assertEquals("READY", examStatus(fixture.examId()));
        verify(ai, times(1)).chat(eq("PROMPT_EXAM_THOUGHT"), anyString(), eq(ThoughtOutput.class));
    }

    @Test
    @Order(2)
    void playerSubmissionClampsModifierReplaysAndRejectsReplacement() throws Exception {
        var fixture = readyExam();
        var command = Map.of("requestId", UUID.randomUUID().toString(), "text", "读书使人明理，学问还应落实为善行。");
        var first = success(post(examPath(fixture, "player"), command));
        var exam = first.getJSONObject("exam");
        assertEquals(10, exam.getInt("aiPlayerContentModifier"));
        assertEquals(60, exam.getInt("finalScore"));
        assertEquals(50, exam.getInt("diceRoll"));
        assertEquals(command.get("text"), exam.getStr("playerInput"));
        assertTrue(first.getBool("newlySettled"));
        assertEquals("STUDYING", first.getJSONObject("detail").getJSONObject("save").getStr("status"));
        JSONAssert.assertEquals(first.toString(), success(post(examPath(fixture, "player"), command)).toString(), true);
        assertStatus(409, post(examPath(fixture, "player"), Map.of("requestId", command.get("requestId"), "text", "改写答案")));
        assertStatus(409, post(examPath(fixture, "player"), Map.of("requestId", UUID.randomUUID().toString(), "text", "另一份答案")));
        assertStatus(409, post(examPath(fixture, "auto"), null));
        verify(ai, times(1)).chat(eq("PROMPT_EXAM_EVALUATION"), anyString(), eq(EvaluationOutput.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM event_record WHERE save_id=? AND life_milestone=1",
                Integer.class, fixture.saveId()));
    }

    @Test
    @Order(3)
    void invalidInputAndModelFailureLeaveNoPartialSettlement() throws Exception {
        var fixture = readyExam();
        assertStatus(400, post(examPath(fixture, "player"), Map.of("requestId", "blank", "text", " ")));
        assertStatus(400, post(examPath(fixture, "player"), Map.of("requestId", "long", "text", "字".repeat(8001))));
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模拟结果生成失败"))
                .when(ai).chat(eq("PROMPT_EXAM_ANSWER"), anyString(), eq(NarrativeOutput.class));
        var command = Map.of("requestId", UUID.randomUUID().toString(), "text", "读书要明理，也要实行。");
        assertStatus(502, post(examPath(fixture, "player"), command));
        assertEquals("READY", examStatus(fixture.examId()));
        assertEquals(96L, turn(fixture.saveId()));
        assertNull(jdbc.queryForObject("SELECT player_input FROM exam_record WHERE id=?", String.class, fixture.examId()));
        assertNull(jdbc.queryForObject("SELECT final_score FROM exam_record WHERE id=?", Integer.class, fixture.examId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM event_record WHERE save_id=? AND request_id=?",
                Integer.class, fixture.saveId(), command.get("requestId")));
        configurePredictableModel();
        success(post(examPath(fixture, "player"), command));
    }

    @Test
    @Order(4)
    void concurrentAnswerModesOnlySettleOnce() throws Exception {
        var fixture = readyExam();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return new EvaluationOutput(BigDecimal.ONE, "回答基本切题。");
        }).when(ai).chat(eq("PROMPT_EXAM_EVALUATION"), anyString(), eq(EvaluationOutput.class));
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return new NarrativeOutput("读书当明理并躬行。", "考试已按确定成绩结束。");
        }).when(ai).chat(eq("PROMPT_EXAM_ANSWER"), anyString(), eq(NarrativeOutput.class));
        var auto = http.sendAsync(request(examPath(fixture, "auto"), null), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var player = http.sendAsync(request(examPath(fixture, "player"),
                Map.of("requestId", UUID.randomUUID().toString(), "text", "读书当明理。")),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        var statuses = new int[]{auto.get(30, TimeUnit.SECONDS).statusCode(), player.get(30, TimeUnit.SECONDS).statusCode()};
        Arrays.sort(statuses);
        assertArrayEquals(new int[]{200, 409}, statuses);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM event_record WHERE save_id=? AND life_milestone=1",
                Integer.class, fixture.saveId()));
    }

    @Test
    @Order(5)
    void invalidAcquisitionListDoesNotApplyEarlierValidTrade() throws Exception {
        var life = newLife();
        String saveId = life.getJSONObject("save").getStr("id");
        String actorId = life.getJSONObject("character").getStr("id");
        BigDecimal zero = BigDecimal.ZERO;
        var patch = new DriverPatch(zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero);
        var acquisition = new AcquisitionIntent("NPC_XIANSHENG", "BOOK_SANZIJING", 1);
        doReturn(new FreeActionResolution(patch, "领取教材", false, Arrays.asList(acquisition, null)))
                .when(ai).chat(eq("PROMPT_FREE_ACTION"), anyString(), eq(FreeActionResolution.class));
        assertStatus(502, post("/api/turn/" + saveId + "/actor/" + actorId + "/free",
                Map.of("requestId", "invalid-list", "sceneCode", "SCENE_SISHU", "text", "领取三字经", "expectedTurnNumber", 0)));
        assertEquals(0L, turn(saveId));
        assertTrue(JSONUtil.parseArray(get("/api/equipment/" + saveId + "/" + actorId).body()).isEmpty());
    }

    @Test
    @Order(6)
    void completeLifeTraversesAllFourExamsAndEndsAtTurn480() throws Exception {
        String saveId = newLife().getJSONObject("save").getStr("id");
        GameSaveService saves = context.getBean(GameSaveService.class);
        ExamRecordService exams = context.getBean(ExamRecordService.class);
        int completedExams = 0;
        while (true) {
            GameSave save = saves.getById(saveId);
            if ("COMPLETED".equals(save.getStatus())) {
                break;
            }
            assertTrue(save.getTotalTurnNumber() <= 480);
            if ("STUDYING".equals(save.getStatus())) {
                saves.executeFixedAction(saveId, new GameSaveService.FixedActionCommand(
                        "journey-" + save.getTotalTurnNumber(), "REST", "SCENE_JIA", null, save.getTotalTurnNumber()));
            } else {
                var exam = exams.listForSave(saveId).stream().filter(item -> "READY".equals(item.getStatus())).findFirst().orElseThrow();
                String path = "/api/exam/" + saveId + "/" + exam.getId();
                if (completedExams % 2 == 0) {
                    success(post(path + "/auto", null));
                } else {
                    success(post(path + "/player", Map.of("requestId", "journey-exam-" + completedExams, "text", "读书当明理、修身、善待乡里。")));
                }
                completedExams++;
            }
        }
        assertEquals(4, completedExams);
        assertEquals(480L, turn(saveId));
        assertEquals(16, saves.getById(saveId).getAge());
        assertEquals(4, exams.listForSave(saveId).size());
    }

    @Test
    @Order(7)
    @EnabledIfEnvironmentVariable(named = "MVP_LIVE_AI_VERIFY", matches = "true")
    void realModelCompletesThoughtPlayerAnswerAndAutoPaper() throws Exception {
        GameClient realClient = context.getBean("gameClient", GameClient.class);
        doAnswer(invocation -> {
            String promptCode = invocation.getArgument(0);
            System.out.println("LIVE_MODEL_CALL=" + promptCode);
            try {
                Object output = realClient.chat(promptCode, invocation.getArgument(1), invocation.getArgument(2));
                System.out.println("LIVE_MODEL_OK=" + promptCode);
                return output;
            } catch (RuntimeException failure) {
                String apiKey = context.getEnvironment().getProperty("MIMO_API_KEY", "");
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    String message = String.valueOf(cause.getMessage());
                    if (!apiKey.isBlank()) {
                        message = message.replace(apiKey, "[REDACTED]");
                    }
                    System.out.println("LIVE_MODEL_FAILURE=" + cause.getClass().getName() + ": " + message);
                }
                throw failure;
            }
        }).when(ai).chat(anyString(), anyString(), any());
        var playerFixture = readyExam();
        String thought = success(post(examPath(playerFixture, "thought"), null)).getStr("aiThoughtBubble");
        assertNotNull(thought);
        assertFalse(thought.isBlank());
        System.out.println("LIVE_THOUGHT=" + thought);
        var player = success(post(examPath(playerFixture, "player"),
                Map.of("requestId", UUID.randomUUID().toString(), "text", "我近日读《三字经》。“人之初，性本善”讲人的本性向善；还须学习、亲近良师，才能把善念落实到行动，不能因为本性向善就放弃教养。")))
                .getJSONObject("exam");
        assertTrue(Math.abs(player.getInt("aiPlayerContentModifier")) <= 10);
        assertFalse(player.getStr("aiContent").isBlank());
        var autoFixture = readyExam();
        var auto = success(post(examPath(autoFixture, "auto"), null)).getJSONObject("exam");
        assertFalse(auto.getStr("aiAnswerText").isBlank());
        assertFalse(auto.getStr("aiContent").isBlank());
        System.out.println("LIVE_PLAYER_MODIFIER=" + player.getInt("aiPlayerContentModifier") + " SCORE=" + player.getInt("finalScore"));
        System.out.println("LIVE_PLAYER_COMMENT=" + player.getStr("aiContent"));
        System.out.println("LIVE_AUTO_SCORE=" + auto.getInt("finalScore") + " PAPER=" + auto.getStr("aiAnswerText"));
    }

    @Test
    @Order(8)
    void fixedActionReplayPreservesDatesAndDoesNotAdvanceTwice() throws Exception {
        String saveId = newLife().getJSONObject("save").getStr("id");
        var command = Map.of("requestId", "replay-rest", "actionCode", "REST",
                "sceneCode", "SCENE_JIA", "expectedTurnNumber", 0);
        var first = post("/api/turn/" + saveId + "/action", command);
        var replayed = post("/api/turn/" + saveId + "/action", command);
        assertStatus(200, first);
        assertStatus(200, replayed);
        JSONAssert.assertEquals(first.body(), replayed.body(), true);
        assertEquals(1L, turn(saveId));
    }

    private Fixture readyExam() throws Exception {
        JSONObject life = newLife();
        String saveId = life.getJSONObject("save").getStr("id");
        String actorId = life.getJSONObject("character").getStr("id");
        jdbc.update("UPDATE game_save SET current_year=birth_year+7,age=7,current_month=12,turn_in_month=4,total_turn_number=95 WHERE id=?", saveId);
        var action = success(post("/api/turn/" + saveId + "/action",
                Map.of("requestId", "reach-exam", "actionCode", "REST", "sceneCode", "SCENE_JIA", "expectedTurnNumber", 95)));
        String examId = action.getJSONObject("detail").getJSONArray("exams").getJSONObject(0).getStr("id");
        jdbc.update("UPDATE exam_record SET base_ability_score=50,state_offset=0,dice_roll=50,luck_offset=0 WHERE id=?", examId);
        return new Fixture(saveId, actorId, examId);
    }

    private JSONObject newLife() throws Exception {
        return success(post("/api/save/start", Map.of("characterName", "验证角色", "birthRegionId", birthRegionId)));
    }

    private String examPath(Fixture fixture, String operation) {
        return "/api/exam/" + fixture.saveId() + "/" + fixture.examId() + "/" + operation;
    }

    private long turn(String saveId) {
        return jdbc.queryForObject("SELECT total_turn_number FROM game_save WHERE id=?", Long.class, saveId);
    }

    private String examStatus(String examId) {
        return jdbc.queryForObject("SELECT status FROM exam_record WHERE id=?", String.class, examId);
    }

    private HttpRequest request(String path, Object body) {
        return HttpRequest.newBuilder(URI.create(serverUrl + path)).timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(body), StandardCharsets.UTF_8)).build();
    }

    private HttpResponse<String> post(String path, Object body) throws Exception {
        return http.send(request(path, body), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(serverUrl + path)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertStatus(200, response);
        return response;
    }

    private JSONObject success(HttpResponse<String> response) {
        assertStatus(200, response);
        return JSONUtil.parseObj(response.body());
    }

    private void assertStatus(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), response.body());
    }

    private record Fixture(String saveId, String actorId, String examId) {
    }
}

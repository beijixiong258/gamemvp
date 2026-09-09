package mvp.controller;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import mvp.service.BookService.LibraryBook;
import mvp.service.GameSaveService;
import mvp.service.GameSaveService.PlayerReadingQuestionCommand;
import mvp.service.GameSaveService.PlayerReadingAnswerCommand;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/book")
public class BookController {
    private final GameSaveService gameSaveService;

    /**
     * 查询存档玩家的私塾书目、阅读进度和解锁条件。
     *
     * @return 书目与当前不可读原因
     */
    @GetMapping("/{saveId}")
    public List<LibraryBook> list(@PathVariable String saveId) {
        return gameSaveService.listBooks(saveId);
    }

    /** 只为允许以身入局且当前可读的科举书籍出题，同一本书同回合复用原题。 */
    @PostMapping("/{saveId}/{bookCode}/player/question")
    public JSONObject question(@PathVariable String saveId, @PathVariable String bookCode,
                               @RequestBody PlayerReadingQuestionCommand command) {
        return gameSaveService.preparePlayerReading(saveId, bookCode, command);
    }

    /** 提交亲自写下的体会；沿用服务端questionId重试，不能换答案重复获取进度。 */
    @PostMapping("/{saveId}/{bookCode}/player/answer")
    public JSONObject answer(@PathVariable String saveId, @PathVariable String bookCode,
                             @RequestBody PlayerReadingAnswerCommand command) {
        return gameSaveService.completePlayerReading(saveId, bookCode, command);
    }
}

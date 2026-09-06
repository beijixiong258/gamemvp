package mvp.controller;

import lombok.RequiredArgsConstructor;
import mvp.service.BookService.LibraryBook;
import mvp.service.GameSaveService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
     * @param saveId 存档ID
     * @return 书目与当前不可读原因
     */
    @GetMapping("/{saveId}")
    public List<LibraryBook> list(@PathVariable String saveId) {
        return gameSaveService.listBooks(saveId);
    }
}

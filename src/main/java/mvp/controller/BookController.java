package mvp.controller;

import lombok.RequiredArgsConstructor;
import mvp.service.BookRecordService;
import mvp.service.BookService;
import mvp.service.EquipmentRecordService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/book")
public class BookController {
    private final BookService bookService;
    private final BookRecordService bookRecordService;
    private final EquipmentRecordService equipmentRecordService;
}

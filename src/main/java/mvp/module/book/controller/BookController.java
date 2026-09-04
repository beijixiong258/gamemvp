package mvp.module.book.controller;

import mvp.module.book.service.BookService;
import mvp.module.book.service.BookRecordService;
import mvp.equipment.service.EquipmentRecordService;
import lombok.RequiredArgsConstructor;
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

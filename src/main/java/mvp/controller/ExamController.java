package mvp.controller;

import lombok.RequiredArgsConstructor;
import mvp.service.ExamRecordService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exam")
public class ExamController {
    private final ExamRecordService examRecordService;
}

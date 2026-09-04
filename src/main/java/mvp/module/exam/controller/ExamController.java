package mvp.module.exam.controller;

import mvp.module.exam.service.ExamRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exam")
public class ExamController {
    private final ExamRecordService examRecordService;
}

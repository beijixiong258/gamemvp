package mvp.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 将业务拒绝原因交给前端；不把异常堆栈或未确认的内部失败当作业务拒绝。 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handle(ResponseStatusException exception) {
        String reason = exception.getReason();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatusCode(),
                reason == null || reason.isBlank() ? "这次操作未能完成，请稍后重试。" : reason);
        if (exception.getStatusCode().value() == HttpStatus.BAD_GATEWAY.value()) {
            log.warn("AI请求未结算：{}", reason, exception);
            // 应用主动抛出的 502 均在 AI 结果落账前或事务回滚时产生。
            // 代理 502、连接中断和未处理异常不经过这里，不能据此放弃原请求。
            problem.setProperty("requestRejected", true);
        }
        return ResponseEntity.status(exception.getStatusCode()).headers(exception.getHeaders()).body(problem);
    }
}

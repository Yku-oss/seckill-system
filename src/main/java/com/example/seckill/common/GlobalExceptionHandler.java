package com.example.seckill.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器（面试 Q5 落地）
 * - 业务异常：给明确提示
 * - DuplicateKeyException：一人一单的唯一键兜底触发 → "每人限购一件"
 * - 兜底异常：返回"系统繁忙"，不泄露堆栈
 *
 * ⚠️ 兜底分支必须打日志：
 *   给用户返回"系统繁忙"是对的（不泄露内部细节），
 *   但【服务端一定得留下堆栈】，否则线上出问题你只知道"系统繁忙"，
 *   不知道是 Redis 挂了还是空指针，等于没有排查线索。
 *
 * @RestControllerAdvice = @ControllerAdvice + @ResponseBody
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        // 事务已回滚（包括库存扣减），返回明确提示
        return Result.error(400, "每人限购一件");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        // 用户看到的是"系统繁忙"，日志里留下完整的异常类型 + 堆栈
        log.error("接口异常（对外返回系统繁忙）: {}", e.getClass().getSimpleName(), e);
        return Result.error(500, "系统繁忙");
    }
}

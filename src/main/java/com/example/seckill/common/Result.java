package com.example.seckill.common;

import lombok.Data;

/**
 * 统一返回格式（面试 Q5：全局异常处理器的输出载体）
 * { code, message, data }
 */
@Data
public class Result<T> {

    private int code;

    private String message;

    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}

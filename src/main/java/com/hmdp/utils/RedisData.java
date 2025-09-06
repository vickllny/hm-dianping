package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;

    public RedisData() {

    }
    public RedisData(final Object data, final LocalDateTime expireTime) {
        this.data = data;
        this.expireTime = expireTime;
    }
}

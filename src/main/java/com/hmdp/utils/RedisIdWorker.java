package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1577836800L;
    /**
     * 左移的位数
     */
    private static final int COUNT_BIT = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(final String keyPrefix){
        //1.生成时间戳
        final LocalDateTime now = LocalDateTime.now();
        final long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        //2.生成序列号
        final String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        final String icrKey = "icr:" + keyPrefix + ":" + keySuffix;
        final long increment = stringRedisTemplate.opsForValue().increment(icrKey);
        //3.拼接
        return timestamp << COUNT_BIT | increment;
    }

}

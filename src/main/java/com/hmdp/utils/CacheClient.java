package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final ThreadPoolExecutor threadPoolExecutor;


    public CacheClient(StringRedisTemplate stringRedisTemplate, final ThreadPoolExecutor threadPoolExecutor) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.threadPoolExecutor = threadPoolExecutor;
    }

    public void set(final String key, final Object value, final long time, final TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setIfAbsent(final String key, final Object value, final long time, final TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().setIfAbsent(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogical(final String key, final Object value, final long time, final TimeUnit timeUnit){
        final RedisData redisData = new RedisData(value, LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R> R queryWithPassThrough(final Supplier<String> keySupplier,
                                      final Class<R> clazz,
                                      final Supplier<R> supplier,
                                      final long time,
                                      final TimeUnit timeUnit){
        final String key = keySupplier.get();
        //1.从redis中查询
        final String jsonString = stringRedisTemplate.opsForValue().get(key);
        //2.判断
        if(StrUtil.isNotBlank(jsonString)){
            return JSONUtil.toBean(jsonString, clazz);
        }
        //3.判断是否为null，如果不为null说明是设置的空字符串，用于处理缓存穿透
        if(jsonString != null){
            return null;
        }
        final R r = supplier.get();
        if(r == null){
            this.setIfAbsent(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, JSONUtil.toJsonStr(r), time, timeUnit);
        return r;
    }

    public <R> R queryWithLogicalExpire(final Supplier<String> keySupplier,
                                        final Supplier<String> lockKeySupplier,
                                        final Class<R> clazz,
                                        final Supplier<R> supplier,
                                        final long time,
                                        final TimeUnit timeUnit){
        final String cacheKey = keySupplier.get();
        String jsonString = stringRedisTemplate.opsForValue().get(cacheKey);
        if(StrUtil.isBlank(jsonString)){
            return null;
        }
        //1.反序列化为对象
        final RedisData redisData = JSONUtil.toBean(jsonString, RedisData.class);
        //2.判断是否过期
        final LocalDateTime expireTime = redisData.getExpireTime();
        final JSONObject jsonObject = (JSONObject)redisData.getData();
        final R r = JSONUtil.toBean(jsonObject, clazz);
        if(LocalDateTime.now().isBefore(expireTime)){
            //3.1 如果未过期，则返回
            return r;
        }
        //4 如果已过期，则缓存重建
        final String lockKey = lockKeySupplier.get();
        //5 获取互斥锁
        final Boolean setIfAbsent = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "", RedisConstants.CACHE_TTL, TimeUnit.SECONDS);
        if(setIfAbsent != null && setIfAbsent){
            //5.1 获取互斥锁成功，异步开启线程执行缓存重建
            threadPoolExecutor.execute(() -> {
                try {
                    log.debug("线程[{}]开始重建缓存", Thread.currentThread().getName());
                    //1.查询店铺数据
                    final R r1 = supplier.get();
                    this.setWithLogical(cacheKey, r1, time, timeUnit);
                }finally {
                    stringRedisTemplate.delete(lockKey);
                }
            });
        }
        //6 直接返回null
        return r;
    }

}

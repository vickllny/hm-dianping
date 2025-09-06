package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.Sleeper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ThreadPoolExecutor threadPoolExecutor;

    @Value("${shop.cache.mutex.retry.count:5}")
    private int mutexRetryCount;

    @Value("${shop.cache.mutex.sleep.mills:100}")
    private long sleepMills;

    @Override
    public Result findById(final Long id) {
        Shop shop;
        return (shop = findByIdWithLogicalExpire(id)) == null ? Result.fail("店铺信息不存在") : Result.ok(shop);
    }

    @Override
    public Shop findByIdWithLogicalExpire(final Long id) {
        final String cacheKey = RedisConstants.CACHE_SHOP_KEY + id;
        String jsonString = stringRedisTemplate.opsForValue().get(cacheKey);
        if(StrUtil.isBlank(jsonString)){
            return null;
        }
        //1.反序列化为对象
        final RedisData redisData = JSONUtil.toBean(jsonString, RedisData.class);
        //2.判断是否过期
        final LocalDateTime expireTime = redisData.getExpireTime();
        final JSONObject jsonObject = (JSONObject)redisData.getData();
        final Shop shop = JSONUtil.toBean(jsonObject, Shop.class);
        if(LocalDateTime.now().isBefore(expireTime)){
            //3.1 如果未过期，则返回
            return shop;
        }
        //4 如果已过期，则缓存重建
        final String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        final String lockValue = UUID.randomUUID().toString();
        //5 获取互斥锁
        final Boolean setIfAbsent = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, RedisConstants.CACHE_LOCK_SHOP_TTL, TimeUnit.SECONDS);
        if(setIfAbsent != null && setIfAbsent){
            //5.1 获取互斥锁成功，异步开启线程执行缓存重建
            threadPoolExecutor.execute(() -> {
                try {
                    saveShop2Redis(id, 30, TimeUnit.SECONDS);
                }finally {
                    stringRedisTemplate.delete(lockKey);
                }
            });
        }
        //6 直接返回null
        return shop;
    }

    public void saveShop2Redis(final Long id, final long time, final TimeUnit timeUnit){
        log.debug("线程[{}]开始重建缓存", Thread.currentThread().getName());
        //1.查询店铺数据
        final Shop shop = getById(id);
        Sleeper.sleepMillis(200);
        //2.逻辑过期时间
        final RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Shop findByIdWithMutexLock(final Long id) {
        final int count = this.mutexRetryCount;
        return _findByIdWithMutexLock(id, count);
    }

    private Shop _findByIdWithMutexLock(final Long id, int count) {
        final String cacheKey = RedisConstants.CACHE_SHOP_KEY + id;
        String jsonString = stringRedisTemplate.opsForValue().get(cacheKey);
        if(StrUtil.isNotBlank(jsonString)){
            return JSONUtil.toBean(jsonString, Shop.class);
        }
        if(jsonString != null){
            return null;
        }
        //获取锁重建缓存
        final String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        final String lockValue = UUID.randomUUID().toString();
        final Boolean setIfAbsent = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, RedisConstants.CACHE_LOCK_SHOP_TTL, TimeUnit.SECONDS);
        if(setIfAbsent != null && setIfAbsent){
            try {
                final Shop shop;
                log.debug("线程[{}]获取锁成功，开始查询数据库", Thread.currentThread().getId());
                if((shop = getById(id)) == null){
                    jsonString = "";
                    stringRedisTemplate.opsForValue().set(cacheKey, jsonString, RedisConstants.CACHE_SHOP_NULL_TTL, TimeUnit.MINUTES);
                }else {
                    jsonString = JSONUtil.toJsonStr(shop);
                    stringRedisTemplate.opsForValue().set(cacheKey, jsonString, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                }
                //模拟重建缓存延迟
                Sleeper.sleepMillis(200);
                return shop;
            }finally {
                stringRedisTemplate.delete(lockKey);
            }
        }
        if(count == 0){
            return null;
        }
        Sleeper.sleepMillis(this.sleepMills);
        return _findByIdWithMutexLock(id, --count);
    }

    @Override
    @Transactional
    public Result update(final Shop shop) {
        final Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        this.updateById(shop);
        final String key = RedisConstants.CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(key);
        return Result.ok();
    }
}

package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.Sleeper;
import com.hmdp.utils.SystemConstants;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService, CommandLineRunner {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ThreadPoolExecutor threadPoolExecutor;

    @Autowired
    private CacheClient cacheClient;

    @Value("${shop.cache.mutex.retry.count:5}")
    private int mutexRetryCount;

    @Value("${shop.cache.mutex.sleep.mills:100}")
    private long sleepMills;

    @Override
    public Result findById(final Long id) {
//        final Shop shop = cacheClient.queryWithPassThrough(() -> RedisConstants.CACHE_SHOP_KEY + id, Shop.class, () -> this.getById(id), RedisConstants.CACHE_SHOP_TTL, TimeUnit.SECONDS);
        final Shop shop = findByIdWithLogicalExpire(id);
        return shop == null ? Result.fail("店铺信息不存在") : Result.ok(shop);
    }

    @Override
    public Shop findByIdWithLogicalExpire(final Long id) {
        return cacheClient.queryWithLogicalExpire(
                () -> RedisConstants.CACHE_SHOP_KEY + id,
                () -> RedisConstants.LOCK_SHOP_KEY + id,
                Shop.class,
                () -> getById(id),
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.SECONDS
                );
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(typeId == null){
            
        }
        // 根据类型分页查询
        // Page<Shop> page = shopService.query()
        //         .eq("type_id", typeId)
        //         .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));

        return null;
    }

    @Override
    public void run(String... args) throws Exception {
        List<Shop> list = this.list();
        for (Shop shop : list) {
            final String key = RedisConstants.SHOP_GEO_KEY + shop.getTypeId();
            Point point = new Point(shop.getX(), shop.getY());
            String shopId = shop.getId().toString();
            stringRedisTemplate.opsForGeo().add(key, point, shopId);
            stringRedisTemplate.opsForGeo().add(RedisConstants.SHOP_GEO_ALL_KEY, point, shopId);
        }
        
    }

    
    
}

package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
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
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
        if(x == null || y == null){
            //非地理查询
             Page<Shop> page = lambdaQuery()
                     .eq(Shop::getTypeId, typeId)
                     .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
             return Result.ok(page.getRecords());
        }
        final int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        final int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        final String key;
        if(typeId == null){
            key = RedisConstants.SHOP_GEO_ALL_KEY;
        }else {
            key = RedisConstants.SHOP_GEO_KEY + typeId;
        }

        final GeoResults<GeoLocation<String>> geoResults = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        if(geoResults == null){
            return Result.ok(Collections.emptyList());
        }
        final List<GeoResult<GeoLocation<String>>> content = from == 0 ? geoResults.getContent() : geoResults.getContent().stream().skip(from).collect(Collectors.toList());
        if(CollUtil.isEmpty(content)){
            return Result.ok(Collections.emptyList());
        }
        final Map<String, Double> tempMap = new HashMap<>();
        for (final GeoResult<GeoLocation<String>> result : content) {
            final GeoLocation<String> geoLocation = result.getContent();
            final Distance distance = result.getDistance();
            tempMap.put(geoLocation.getName(), distance.getValue());
        }
        final List<Shop> shops = listByIds(tempMap.keySet().stream().map(Long::valueOf).collect(Collectors.toList()));
        //按照顺序排序
        shops.sort(Comparator.comparingDouble(o -> tempMap.get(o.getId().toString())));
        for (final Shop shop : shops) {
            shop.setDistance(tempMap.get(shop.getId().toString()));
        }
        return Result.ok(shops);
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

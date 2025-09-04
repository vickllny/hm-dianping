package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result findById(final Long id) {
        final String key = CACHE_SHOP_KEY + id;
        String jsonString = stringRedisTemplate.opsForValue().get(key);
        Shop shop = null;
        if(StrUtil.isNotBlank(jsonString)){
            shop = JSONUtil.toBean(jsonString, Shop.class);
        }else {
            //从数据库查询
            if((shop = getById(id)) == null){
                return Result.fail("店铺不存在");
            }
            jsonString = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(key, jsonString, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result update(final Shop shop) {
        final Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        this.updateById(shop);
        final String key = CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(key);
        return Result.ok();
    }
}

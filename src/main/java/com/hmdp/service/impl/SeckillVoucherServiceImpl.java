package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {

    @Autowired
    private CacheClient cacheClient;

    @Override
    public SeckillVoucher queryWithPassThrough(final Long voucherId) {

        return cacheClient.queryWithPassThrough(
                () -> RedisConstants.CACHE_SECKILL_VOUCHER_KEY + voucherId,
                SeckillVoucher.class,
                () -> this.getById(voucherId),
                RedisConstants.CACHE_SECKILL_VOUCHER_TTL,
                TimeUnit.MINUTES
        );
    }
}

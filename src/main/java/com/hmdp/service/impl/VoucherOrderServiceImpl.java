package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.*;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private RedissonClient redissonClient;

    @Override
    @Transactional
    public Result seckillVoucher(final Long voucherId) {
        //1.查询优惠券
        final SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
        if(seckillVoucher == null){
            return Result.fail("优惠券不存在!");
        }
        final LocalDateTime now = LocalDateTime.now();
        //2.判断秒杀是否开始
        final LocalDateTime beginTime = seckillVoucher.getBeginTime();
        if(now.isBefore(beginTime)){
            return Result.fail("秒杀未开始!");
        }
        //3.判断秒杀是否结束
        final LocalDateTime endTime = seckillVoucher.getEndTime();
        if(now.isAfter(endTime)){
            return Result.fail("秒杀已结束!");
        }
        //4.判断库存是否充足
        final Integer stock = seckillVoucher.getStock();
        if(stock < 1){
            return Result.fail("库存不足!");
        }
        final Long userId = UserHolder.getUser().getId();
        final String lockKey = RedisConstants.LOCK_VOUCHER_ORDER_KEY + voucherId + ":" + userId;
        final RLock lock = redissonClient.getLock(lockKey);
        if(!lock.tryLock()){
            return Result.fail("系统繁忙，请重试!");
        }
        try {
            return ContextUtils.getBean(IVoucherOrderService.class).getResult(voucherId, now, userId);
        }finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional
    public Result getResult(final Long voucherId, final LocalDateTime now, final Long userId) {

        final Integer count = lambdaQuery()
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, userId).count();
        if(count > 0){
            return Result.fail("不允许重复购买!");
        }
        //5.扣减库存
        final boolean success = iSeckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0).update();
        if(!success){
            return Result.fail("库存不足!");
        }

        //6.创建订单
        final VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(redisIdWorker.nextId(RedisConstants.VOUCHER_ORDER_KEY_PREFIX));
        voucherOrder.setCreateTime(now);
        voucherOrder.setUpdateTime(now);
        save(voucherOrder);

        return Result.ok(voucherOrder.getId());
    }

}

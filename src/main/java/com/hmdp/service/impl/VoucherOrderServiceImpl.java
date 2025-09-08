package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService, CommandLineRunner {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private RedissonClient redissonClient;

    @Resource
    private ThreadPoolExecutor seckillThreadPoolExecutor;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final BlockingQueue<VoucherOrder> VOUCHER_ORDER_BLOCKING_QUEUE = new ArrayBlockingQueue<>(1024);

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
        final Long userId = UserHolder.getUser().getId();
        final Long execute = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                SECKILL_STOCK_KEY + voucherId,
                RedisConstants.SECKILL_ORDER_KEY + voucherId,
                userId.toString()
        );
        final int value = execute.intValue();
        if(value == 1){
            return Result.fail("库存不足!");
        }else if(value == 2){
            return Result.fail("不允许重复购买!");
        }
        //TODO 异步下单保存到阻塞队列
        final long orderId = redisIdWorker.nextId("order");

        final VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(orderId);
        VOUCHER_ORDER_BLOCKING_QUEUE.add(voucherOrder);
        return Result.ok(orderId);
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

    @Override
    @Transactional
    public void handlerVoucherOrder(final VoucherOrder voucherOrder){
        final Long voucherId = voucherOrder.getVoucherId();
        //5.扣减库存
        final boolean success = iSeckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0).update();
        if(!success) {
            log.warn("库存不足: {}", JSONUtil.toJsonStr(voucherOrder));
            return;
        }
        final LocalDateTime now = LocalDateTime.now();
        //6.创建订单
        voucherOrder.setCreateTime(now);
        voucherOrder.setUpdateTime(now);
        ContextUtils.getBean(IVoucherOrderService.class).save(voucherOrder);
    }

    @Override
    public void run(final String... args) throws Exception {
        seckillThreadPoolExecutor.execute(() -> {
            while (true){
                VoucherOrder voucherOrder = null;
                try {
                    handlerVoucherOrder(voucherOrder = VOUCHER_ORDER_BLOCKING_QUEUE.take());
                }catch (Exception e){
                    log.error("创建订单异常", e);
                    if(voucherOrder != null){
                        //重新放入阻塞队列中
                        VOUCHER_ORDER_BLOCKING_QUEUE.add(voucherOrder);
                    }
                }
            }
        });
    }
}

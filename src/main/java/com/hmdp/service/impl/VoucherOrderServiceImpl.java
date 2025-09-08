package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        final long orderId = redisIdWorker.nextId("order");

        final Long execute = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                String.valueOf(orderId),
                userId.toString()
        );
        final int value = execute.intValue();
        if(value == 1){
            return Result.fail("库存不足!");
        }else if(value == 2){
            return Result.fail("不允许重复购买!");
        }
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

    final String queueName = "stream.orders";


    @Override
    public void run(final String... args) throws Exception {
        seckillThreadPoolExecutor.execute(() -> {
            while (true){
                try {
                    final List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if(list == null || list.isEmpty()){
                        continue;
                    }
                    //解析消息中的订单
                    final MapRecord<String, Object, Object> mapRecord = list.get(0);
                    final Map<Object, Object> recordValue = mapRecord.getValue();
                    handlerVoucherOrder(BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), true));
                    //确认消息
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", mapRecord.getId());
                }catch (Exception e){
                    log.error("创建订单异常", e);
                    handlePendingList();
                }
            }
        });
    }

    private void handlePendingList(){
        while (true){
            VoucherOrder voucherOrder = null;
            try {
                //pending-list
                final List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                if(list == null || list.isEmpty()){
                    break;
                }
                //解析消息中的订单
                final MapRecord<String, Object, Object> mapRecord = list.get(0);
                final Map<Object, Object> recordValue = mapRecord.getValue();
                handlerVoucherOrder(BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), true));
                //确认消息
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", mapRecord.getId());
            }catch (Exception e){
                log.error("处理pending-list异常", e);
                Sleeper.sleepMillis(20);
                break;
            }
        }

    }
}

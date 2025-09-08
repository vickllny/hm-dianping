package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    @Transactional
    Result getResult(Long voucherId, LocalDateTime now, Long userId);

    void handlerVoucherOrder(VoucherOrder voucherOrder);
}

package com.hmdp.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;

public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean flag);

    Result followOrNot(Long followUserId);

    Result common(Long userId);

    List<Follow> findByFollowUserId(Long id);
}

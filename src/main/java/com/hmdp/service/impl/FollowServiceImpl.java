package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService iUserService;

    @Override
    public Result follow(final Long followUserId, final Boolean flag) {

        final Long userId = UserHolder.getUser().getId();
        if(flag){
            //先查询
            final Integer count = lambdaQuery().eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId).count();
            if(count < 1){
                final Follow follow = new Follow();
                follow.setUserId(userId);
                follow.setFollowUserId(followUserId);
                follow.setCreateTime(LocalDateTime.now());
                final boolean saved = this.save(follow);
                if(saved){
                    final String key = RedisConstants.FOLLOW_USER_KEY + userId;
                    stringRedisTemplate.opsForSet().add(key, followUserId.toString());
                }

            }
        }else {
            final QueryWrapper<Follow> wrapper = new QueryWrapper<Follow>().eq("user_id", userId)
                    .eq("follow_user_id", followUserId);
            final boolean removed = remove(wrapper);
            if(removed){
                final String key = RedisConstants.FOLLOW_USER_KEY + userId;
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result followOrNot(final Long followUserId) {
        final Long userId = UserHolder.getUser().getId();
        final String key = RedisConstants.FOLLOW_USER_KEY + userId;
        return Result.ok(BooleanUtil.isTrue(stringRedisTemplate.opsForSet().isMember(key, followUserId.toString())));
    }

    @Override
    public Result common(final Long userId) {
        final Long currentUserId = UserHolder.getUser().getId();
        final String key = RedisConstants.FOLLOW_USER_KEY + currentUserId;
        final String key1 = RedisConstants.FOLLOW_USER_KEY + userId;
        final Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key1);
        if(CollUtil.isEmpty(intersect)){
            return Result.ok(Collections.emptyList());
        }
        final List<User> users = iUserService.listByIds(intersect.stream().map(Long::valueOf).collect(Collectors.toList()));
        return Result.ok(users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList()));
    }
}

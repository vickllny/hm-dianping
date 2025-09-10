package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private IUserService iUserService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(final Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(a -> {
            this.queryUserBlog(a);
            this.isBolgLiked(a);
        });

        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(final Long id) {
        final Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        this.queryUserBlog(blog);
        //查询blog是否被当前用户点赞
        this.isBolgLiked(blog);
        return Result.ok(blog);
    }

    private void isBolgLiked(final Blog blog) {
        final UserDTO user = UserHolder.getUser();
        if(user == null){
            return;
        }
        final String userId = user.getId().toString();
        final String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        blog.setIsLike(stringRedisTemplate.opsForZSet().score(key, userId) != null);
    }

    @Override
    @Transactional
    public Result likeBlog(final Long id) {
        final UserDTO user = UserHolder.getUser();
        final String key = RedisConstants.BLOG_LIKED_KEY + id;

        final String userIdStr = user.getId().toString();
        final Double score = stringRedisTemplate.opsForZSet().score(key, userIdStr);
        final long time = System.currentTimeMillis();
        if(score != null){
            //取消点赞
            final boolean updated = update().setSql("liked = liked - 1")
                    .eq("id", id)
                    .gt("liked", 0).update();
            if(updated){
                stringRedisTemplate.opsForZSet().remove(key, userIdStr);
            }
            return updated ? Result.ok() : Result.fail("操作失败，请重试！");
        }
        //增加点赞
        final boolean updated = update().setSql("liked = liked + 1")
                .eq("id", id).update();
        if(updated){
            stringRedisTemplate.opsForZSet().add(key, userIdStr, time);
        }
        return updated ? Result.ok() : Result.fail("操作失败，请重试！");
    }

    @Override
    public Result likesBlog(final Long id) {
        //List<UserDTO>
        final String key = RedisConstants.BLOG_LIKED_KEY + id;
        final Set<String> userIdSet = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(CollUtil.isEmpty(userIdSet)){
            return Result.ok(Collections.emptyList());
        }
        final Map<Long, Integer> tempMap = new HashMap<>();
        int count = 0;
        for (final String string : userIdSet) {
            tempMap.put(Long.valueOf(string), ++count);
        }
        final List<UserDTO> userList = iUserService.listByIds(tempMap.keySet())
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).sorted((o1, o2) -> {
                    final Integer i = tempMap.get(o1.getId());
                    final Integer j = tempMap.get(o2.getId());
                    return Integer.compare(i, j);
                }).collect(Collectors.toList());
        return Result.ok(userList);
    }

    void queryUserBlog(final Blog blog){
        Long userId = blog.getUserId();
        User user = iUserService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}

package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
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
    @Autowired
    private IFollowService iFollowService;
    

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean saved = save(blog);
        if(!saved){
            return Result.fail("发布笔记失败!");
        }
        final String blogIdString = blog.getId().toString();
        //查询粉丝
        final List<Follow> follows = iFollowService.findByFollowUserId(user.getId());
        if(CollUtil.isNotEmpty(follows)){
            final long timeMillis = System.currentTimeMillis();
            for(Follow follow : follows){
                final String key = RedisConstants.FOLLOW_FEED_KEY + follow.getUserId();
                stringRedisTemplate.opsForZSet().add(key, blogIdString, timeMillis);
            }
        }
        // 返回id
        return Result.ok(blog.getId());
    }

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

    

    @Override
    public Result queryBlogOfFollow(Long lastId, Integer offset) {
        UserDTO user = UserHolder.getUser();
        final String feedKey = RedisConstants.FOLLOW_FEED_KEY + user.getId();
        final Set<TypedTuple<String>> reverseRangeByScoreWithScores = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(feedKey, 0, lastId, offset, 5);
        if(CollUtil.isEmpty(reverseRangeByScoreWithScores)){
            return Result.ok();
        }
        final List<TypedTuple<String>> tempList = new ArrayList<>(reverseRangeByScoreWithScores);
        final Map<String, Double> tempMap = reverseRangeByScoreWithScores.stream().collect(Collectors.toMap(a -> a.getValue(), a -> a.getScore()));
        final List<String> blogIdList = tempList.stream().map(v -> v.getValue()).collect(Collectors.toList());
        final List<Blog> list = listByIds(blogIdList);
        final ScrollResult scrollResult = new ScrollResult();
        //排序
        list.sort(new Comparator<Blog>() {
            @Override
            public int compare(Blog o1, Blog o2) {
                return Double.compare(tempMap.get(o2.getId().toString()), tempMap.get(o1.getId().toString()));
            }
            
        });
        list.forEach(a -> {
            this.queryUserBlog(a);
            this.isBolgLiked(a);
        });
        scrollResult.setList(list);
        scrollResult.setMinTime(tempList.get(tempList.size() - 1).getScore().longValue());
        int newOffset;
        Double score;
        if(tempList.size() == 1 || (score = tempList.get(tempList.size() - 1).getScore()).longValue() != tempList.get(tempList.size() - 2).getScore().longValue()){
            newOffset = 0;
        }else {
            newOffset = Long.valueOf(tempList.stream().filter(v -> v.getScore().compareTo(score) == 1).count()).intValue();
        }
        scrollResult.setOffset(newOffset);
        return Result.ok(scrollResult);
    }

    void queryUserBlog(final Blog blog){
        Long userId = blog.getUserId();
        User user = iUserService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}

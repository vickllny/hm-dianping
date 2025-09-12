package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest(classes = HmDianPingApplication.class)
@RunWith(SpringRunner.class)
public class HmDianPingApplicationTests {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


//    @Test
    public void test1(){
        final Shop shop = shopService.getById(1L);
        cacheClient.setWithLogical(RedisConstants.CACHE_SHOP_KEY + 1, shop, 30, TimeUnit.SECONDS);
    }


    @Test
    public void test2() throws InterruptedException {
        final ExecutorService threadPool = Executors.newFixedThreadPool(500);
        final CountDownLatch countDownLatch = new CountDownLatch(300);
        final Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id=" + id);
            }
            countDownLatch.countDown();
        };
        final long beginTime = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            threadPool.execute(task);
        }
        countDownLatch.await();

        final long end = System.currentTimeMillis();
        System.out.println("耗时: " + (end - beginTime) + "ms");
    }

    @Test
    public void test3(){
        final List<Shop> list = shopService.list();
        for (final Shop shop : list) {
            cacheClient.setWithLogical(RedisConstants.CACHE_SHOP_KEY + shop.getId(), shop, 30, TimeUnit.MINUTES);
        }
    }

    @Test
    public void test4(){
        String[] users = new String[1000];
        for (int i = 0; i < 100_0000; i++) {
            int j = i % 1000;
            users[j] = "user_" + i;
            if(j == 999){
                stringRedisTemplate.opsForHyperLogLog().add("hl2", users);
            }
        }
        final Long hl2 = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println(hl2);
    }

}

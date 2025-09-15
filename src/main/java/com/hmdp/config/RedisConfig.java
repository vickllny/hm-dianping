package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    @Value("${spring.redis.host}")
    private String host;

    @Value("${spring.redis.port}")
    private int port;

    @Bean
    public RedissonClient redissonClient(){
        final Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }

//    @Bean
//    public LettuceClientConfigurationBuilderCustomizer clientConfigurationBuilderCustomizer(){
//        return configBuilder -> configBuilder.readFrom(ReadFrom.REPLICA_PREFERRED);//优先从slave节点读取，当所有的slave都不可用时，从master节点读取
//    }
}

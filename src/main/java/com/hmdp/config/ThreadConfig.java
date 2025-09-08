package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ThreadConfig {

    @Bean
    public ThreadPoolExecutor threadPoolExecutor(){
        return new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors(), 200, 300, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000), new NamedThreadFactory("async-task-"));
    }

    @Bean
    public ThreadPoolExecutor seckillThreadPoolExecutor(){
        return new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors(), 200, 300, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000), new NamedThreadFactory("seckill-task-"));
    }


    private static class NamedThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger COUNTER = new AtomicInteger(1);

        private NamedThreadFactory(final String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(final Runnable r) {
            final Thread thread = new Thread(r);
            thread.setName(namePrefix + COUNTER.getAndIncrement());
            return thread;
        }
    }

}

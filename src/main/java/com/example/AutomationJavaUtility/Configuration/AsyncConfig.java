package com.example.AutomationJavaUtility.Configuration;


import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);  // Minimum threads
        executor.setMaxPoolSize(10);  // Maximum threads
        executor.setQueueCapacity(500); // Queue size
        executor.setThreadNamePrefix("fvu-async-");
        executor.initialize();
        return executor;
    }
}

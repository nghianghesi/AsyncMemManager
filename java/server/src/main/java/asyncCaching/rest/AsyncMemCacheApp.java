package asyncCaching.rest;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

import asyncCaching.server.FilePersistence;
import asyncCaching.server.di.AsyncMemCache;
import asyncCaching.server.di.Persistence;

@SpringBootApplication()
@EnableAsync
public class AsyncMemCacheApp {
	public static void main(String[] args) {
		SpringApplication.run(AsyncMemCacheApp.class, args);
	}
	
	@Bean
	public AsyncMemCache AsyncMemCacheBean() {
		int capacity = 20 * 1024;
		int initialSize = 100;
		int cleanupInterval = 3600;
		int candelPoolSize = 4;
		Map<String, asyncMemManager.common.FlowKeyConfiguration> flowKeyConfig = new HashMap<>();
		asyncMemManager.common.Configuration config = new asyncMemManager.common.Configuration(capacity, initialSize, cleanupInterval, candelPoolSize, flowKeyConfig);
				
		Persistence filePersistence = new FilePersistence(System.getProperty("user.home") + "/async-caching/");
		return new asyncCaching.server.AsyncMemCache(config, filePersistence);
	}
}

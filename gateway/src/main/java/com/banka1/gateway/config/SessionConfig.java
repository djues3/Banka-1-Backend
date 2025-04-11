package com.banka1.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

//@Configuration
//public class SessionConfig {
//
//	@Bean
//	public RedisConnectionFactory redisConnectionFactory() {
//		return new LettuceConnectionFactory("localhost", 6379);
//	}
//
//	@Bean
//	public RedisOperationsSessionRepository sessionRepository(RedisConnectionFactory redisConnectionFactory) {
//		RedisSession
//		RedisOperationsSessionRepository repository = new RedisOperationsSessionRepository(redisConnectionFactory);
//		repository.setDefaultMaxInactiveInterval(1800); // 30 minutes
//		return repository;
//	}
//}

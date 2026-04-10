package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.cache.CacheProperties.Redis;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties.Jedis;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

@Configuration
class JedisConfiguration {
	@Bean
	public JedisConnectionFactory jedisConnectionFactory() {
		return new JedisConnectionFactory();
	}

	@Bean
	public RedisTemplate<String, String> redisTemplate(JedisConnectionFactory jedisConnectionFactory) {
		RedisTemplate<String, String> template = new RedisTemplate<>();
		template.setConnectionFactory(jedisConnectionFactory);
		return template;
	}
}

@SpringBootApplication
@RestController
public class DemoApplication {

	@Autowired
	private RedisTemplate<String, String> redisTemplate;

	@GetMapping("/hello")
	public String doHello() {
		String countStr = redisTemplate.opsForValue().get("helloCount");
		int count = countStr != null ? Integer.parseInt(countStr) : 0;
		count++;
		redisTemplate.opsForValue().set("helloCount", String.valueOf(count));
		return "Hello, World! This endpoint has been called " + count + " times.";
	}

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

}

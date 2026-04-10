package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.GetMapping;

@SpringBootApplication
@RestController
public class FooServiceApplication {

	private final RestTemplate restTemplate = new RestTemplate();

	@GetMapping("/foo")
	public String foo() {
		String host = "unknown";
		try {
			host = java.net.InetAddress.getLocalHost().getHostName();
		} catch (Exception e) {
			e.printStackTrace();
		}
		String barResponse = restTemplate.getForObject("http://bar-service:8080/bar", String.class);
		return "Hello from Foo Service running on " + host + ". + \n Bar Service says: " + barResponse;
	}

	public static void main(String[] args) {
		SpringApplication.run(FooServiceApplication.class, args);
	}

}

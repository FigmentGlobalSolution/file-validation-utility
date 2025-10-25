package com.example.AutomationJavaUtility.Configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


//will need to modify allowedOriginMethods based on frontend or ip where main application is deployed(which will call our backend api endpoints)
@Configuration
public class corsConfig {
	@Bean
	public WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				registry.addMapping("/api/**")
				.allowedOrigins("*") 
				.allowedMethods("GET","POST","PUT","DELETE","OPTIONS")
				.allowedHeaders("*")
				 .exposedHeaders("Content-Disposition")
				 .allowCredentials(false);
			}
		};
	}
}

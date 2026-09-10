package com.zhj.route;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class RouteDemoApplication {
    private static final Logger logger = LoggerFactory.getLogger(RouteDemoApplication.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(RouteDemoApplication.class, args);
        String port = context.getEnvironment().getProperty("server.port", "8088");
        String[] activeProfiles = context.getEnvironment().getActiveProfiles();
        String profiles = activeProfiles.length == 0 ? "default" : String.join(",", activeProfiles);
        logger.info("Spring Boot 启动完成，访问地址：http://localhost:{}，使用profile：{}", port, profiles);
    }
}

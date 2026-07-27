package com.faultlab.backend.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.faultlab.backend.**.mapper")
public class MyBatisPlusConfig {
}

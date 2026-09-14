package com.miuxuer.linkforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LinkForge 启动类。
 *
 * <p>必须放在 {@code com.miuxuer.linkforge} 根包下：{@code @SpringBootApplication} 的
 * 组件扫描以本类所在包为起点往下扫，放到子包里的话 config / interceptor / aspect
 * 这些兄弟包会扫不到，Bean 静默不注册。
 */
@SpringBootApplication
public class LinkForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkForgeApplication.class, args);
    }
}

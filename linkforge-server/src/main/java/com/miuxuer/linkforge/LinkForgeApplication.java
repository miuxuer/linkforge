package com.miuxuer.linkforge;

import org.mybatis.spring.annotation.MapperScan;
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
// MyBatis 的 Mapper 是接口，没有实现类，得靠这个注解让 MyBatis 生成代理对象注册成 Bean。
// 手写在一个个接口上打 @Mapper 也行，但 Mapper 一多就容易漏，统一扫描更稳。
@MapperScan("com.miuxuer.linkforge.mapper")
public class LinkForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkForgeApplication.class, args);
    }
}

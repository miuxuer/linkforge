package com.miuxuer.linkforge.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * 把"数据库连不上"这个启动失败的原因讲清楚。
 *
 * <p>默认的报错是 {@code Failed to obtain JDBC Connection} ——
 * 这句话本身没错，但它把<b>两种完全不同的原因</b>混成了一句：
 *
 * <ul>
 *   <li>数据库密码没通过环境变量传进来（本机开发最常见的情况）
 *   <li>密码传了但不对 / MySQL 没启动 / 库不存在
 * </ul>
 *
 * <p>这两种情况的排查方向完全不同，但报错一模一样。踩过一次的人知道要去看环境变量，
 * 没踩过的人会先怀疑 MySQL —— 而 MySQL 明明是好的（在 IDEA 的 Database 工具里连得通），
 * 于是陷入"数据库没问题啊"的困惑。
 *
 * <p><b>IDEA 的 Database 工具和应用是两回事</b>：前者用 IDEA 里保存的连接配置，
 * 后者用运行配置里的环境变量。前者连得上，不代表后者配好了。
 *
 * <p>注册方式见 {@code META-INF/spring.factories}。
 */
public class DatabaseConnectionFailureAnalyzer
        extends AbstractFailureAnalyzer<CannotGetJdbcConnectionException> {

    private static final String ENV_NAME = "DB_PASSWORD";

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, CannotGetJdbcConnectionException cause) {
        String password = System.getenv(ENV_NAME);

        // 注意只判断"有没有传"，绝不把密码本身（哪怕长度）打出来 ——
        // 启动日志经常被贴到 issue 里，长度也算一点点信息
        if (password == null || password.isEmpty()) {
            return new FailureAnalysis(
                    "数据库连不上：环境变量 " + ENV_NAME + " 没有传给应用。",
                    """
                    IDEA 里加：Run → Edit Configurations → 选中当前配置
                              → Environment variables 一栏填 DB_PASSWORD=你的MySQL密码
                              （是 Environment variables，不是 Program arguments）

                    命令行：DB_PASSWORD=你的MySQL密码 mvn -pl linkforge-server spring-boot:run

                    提醒：IDEA 自带的 Database 工具里连得通，不代表这里配好了 ——
                          那是两套彼此独立的连接配置。""",
                    cause);
        }

        return new FailureAnalysis(
                "数据库连不上：" + ENV_NAME + " 已经设置了，但连接仍然失败。",
                """
                按顺序检查：
                  1. 密码是不是 MySQL 的密码（不是系统密码，也不是 Redis 密码）
                  2. MySQL 服务在不在跑（Windows 服务里找 MySQL80）
                  3. 库建了没有：mysql -u root -p < schema.sql
                  4. application-dev.yml 里的 url 指向的端口是不是 3306""",
                cause);
    }
}

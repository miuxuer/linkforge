-- ============================================================
-- LinkForge 建库脚本
-- 执行：mysql -u root -p < schema.sql
--
-- 全部用 CREATE TABLE IF NOT EXISTS / INSERT IGNORE：
-- 重复执行不会报错，也不会把已有数据冲掉。开发阶段手滑多跑一次不至于丢数据。
-- ============================================================

CREATE DATABASE IF NOT EXISTS linkforge
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE linkforge;

-- ------------------------------------------------------------
-- t_user 用户表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_user
(
    id          BIGINT       NOT NULL COMMENT '主键，号段模式生成',
    username    VARCHAR(50)  NOT NULL COMMENT '登录名',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密后的密码，不存明文也不存 MD5',
    nickname    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    avatar      VARCHAR(500) DEFAULT NULL COMMENT '头像 URL（OSS）',
    role        TINYINT      NOT NULL DEFAULT 0 COMMENT '0=普通用户 1=管理员',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    create_time DATETIME     DEFAULT NULL,
    update_time DATETIME     DEFAULT NULL,
    create_user BIGINT       DEFAULT NULL,
    update_user BIGINT       DEFAULT NULL,
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删除',
    PRIMARY KEY (id),
    -- 唯一索引要带 deleted：逻辑删除后用户名应该能被重新注册
    UNIQUE KEY uk_username (username, deleted)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- ------------------------------------------------------------
-- t_link 短链表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_link
(
    id           BIGINT        NOT NULL COMMENT '主键，号段模式生成；Base62 编码后就是短码',
    short_code   VARCHAR(16)   NOT NULL COMMENT '短码，Base62 编码，全局唯一',
    original_url VARCHAR(2048) NOT NULL COMMENT '原始长链接',
    title        VARCHAR(100)  DEFAULT NULL COMMENT '标题，便于管理',
    remark       VARCHAR(255)  DEFAULT NULL COMMENT '备注',
    user_id      BIGINT        DEFAULT NULL COMMENT '归属用户，多租户隔离的关键字段',
    visit_count  BIGINT        NOT NULL DEFAULT 0 COMMENT '累计访问量，由定时任务从 Redis 回写',
    status       TINYINT       NOT NULL DEFAULT 1 COMMENT '0=停用（跳转返回 404）1=启用',
    expire_time  DATETIME      DEFAULT NULL COMMENT '过期时间，NULL 表示永不过期',
    qr_logo      VARCHAR(500)  DEFAULT NULL COMMENT '二维码中间的 logo URL',
    create_time  DATETIME      DEFAULT NULL,
    update_time  DATETIME      DEFAULT NULL,
    create_user  BIGINT        DEFAULT NULL,
    update_user  BIGINT        DEFAULT NULL,
    deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0=正常 1=已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_code (short_code),
    -- 用户端所有列表查询都带 user_id 条件，这个索引是必须的
    KEY idx_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='短链映射表';

-- ------------------------------------------------------------
-- t_visit_log 访问明细表
--
-- 注意：跳转链路绝不同步写这张表。写入走「Redis 计数 + 异步落明细」，
-- 详见 docs/design.md。这张表只用于看板的明细钻取。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_visit_log
(
    id         BIGINT       NOT NULL COMMENT '主键',
    link_id    BIGINT       NOT NULL COMMENT '关联短链',
    short_code VARCHAR(16)  NOT NULL COMMENT '冗余短码，避免关联查询',
    ip         VARCHAR(50)  DEFAULT NULL COMMENT '访问者 IP',
    user_agent VARCHAR(500) DEFAULT NULL COMMENT '浏览器信息',
    referer    VARCHAR(500) DEFAULT NULL COMMENT '来源页',
    province   VARCHAR(50)  DEFAULT NULL COMMENT '省份（IP 解析，可选）',
    visit_time DATETIME     NOT NULL COMMENT '访问时间',
    PRIMARY KEY (id),
    KEY idx_link_id (link_id),
    -- 看板按时间范围查趋势，时间必须有索引
    KEY idx_visit_time (visit_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='访问明细表';

-- ------------------------------------------------------------
-- t_operate_log 操作日志表（由 @OperateLog 切面写入）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_operate_log
(
    id            BIGINT       NOT NULL COMMENT '主键',
    operate_user  BIGINT       DEFAULT NULL COMMENT '操作人 id',
    operate_time  DATETIME     DEFAULT NULL COMMENT '操作时间',
    class_name    VARCHAR(200) DEFAULT NULL COMMENT '目标类',
    method_name   VARCHAR(100) DEFAULT NULL COMMENT '目标方法',
    method_params TEXT COMMENT '入参 JSON（截断）',
    return_value  TEXT COMMENT '返回值 JSON（截断）',
    cost_time     BIGINT       DEFAULT NULL COMMENT '耗时（毫秒）',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0=成功 1=失败',
    error_msg     VARCHAR(500) DEFAULT NULL COMMENT '异常信息',
    PRIMARY KEY (id),
    KEY idx_operate_time (operate_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='操作日志表';

-- ------------------------------------------------------------
-- t_id_segment 号段表
--
-- 一行就是一个业务方的发号状态。应用取号时执行
-- UPDATE ... SET max_id = max_id + step，把 [max_id+1, max_id+step] 这一段独占下来。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_id_segment
(
    biz_tag     VARCHAR(50) NOT NULL COMMENT '业务标识',
    max_id      BIGINT      NOT NULL DEFAULT 0 COMMENT '当前已分配出去的最大 id',
    step        INT         NOT NULL DEFAULT 1000 COMMENT '每次分配的号段长度',
    update_time DATETIME    DEFAULT NULL,
    PRIMARY KEY (biz_tag)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='号段发号表';

-- 初始化发号记录：用户和短链各取各的号，互不影响
-- （短链 id 要转 Base62 当短码，和用户 id 混在一起会让短码长度不好控制）
INSERT IGNORE INTO t_id_segment (biz_tag, max_id, step, update_time)
VALUES ('user', 0, 1000, NOW()),
       ('link', 0, 1000, NOW());

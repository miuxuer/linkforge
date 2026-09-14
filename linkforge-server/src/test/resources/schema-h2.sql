-- 集成测试用的 H2 建表脚本。
--
-- 为什么不直接复用根目录的 schema.sql：那份脚本里有 ENGINE=InnoDB、COMMENT='...'
-- 这些 MySQL 专有语法，H2 的 MySQL 兼容模式认不全。测试用的表结构只要字段够用就行，
-- 单独一份更省心 —— 代价是改表结构时两处都要改，所以这里只保留必要的列。

CREATE TABLE IF NOT EXISTS t_user
(
    id          BIGINT       NOT NULL,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    nickname    VARCHAR(50),
    avatar      VARCHAR(500),
    role        TINYINT      NOT NULL DEFAULT 0,
    status      TINYINT      NOT NULL DEFAULT 1,
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_user BIGINT,
    update_user BIGINT,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS t_link
(
    id           BIGINT        NOT NULL,
    short_code   VARCHAR(16)   NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    title        VARCHAR(100),
    remark       VARCHAR(255),
    user_id      BIGINT,
    visit_count  BIGINT        NOT NULL DEFAULT 0,
    status       TINYINT       NOT NULL DEFAULT 1,
    expire_time  TIMESTAMP,
    qr_logo      VARCHAR(500),
    create_time  TIMESTAMP,
    update_time  TIMESTAMP,
    create_user  BIGINT,
    update_user  BIGINT,
    deleted      TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS t_visit_log
(
    id         BIGINT      NOT NULL,
    link_id    BIGINT      NOT NULL,
    short_code VARCHAR(16) NOT NULL,
    ip         VARCHAR(50),
    user_agent VARCHAR(500),
    referer    VARCHAR(500),
    province   VARCHAR(50),
    visit_time TIMESTAMP   NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS t_operate_log
(
    id            BIGINT       NOT NULL,
    operate_user  BIGINT,
    operate_time  TIMESTAMP,
    class_name    VARCHAR(200),
    method_name   VARCHAR(100),
    method_params TEXT,
    return_value  TEXT,
    cost_time     BIGINT,
    status        TINYINT      NOT NULL DEFAULT 0,
    error_msg     VARCHAR(500),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS t_id_segment
(
    biz_tag     VARCHAR(50) NOT NULL,
    max_id      BIGINT      NOT NULL DEFAULT 0,
    step        INT         NOT NULL DEFAULT 1000,
    update_time TIMESTAMP,
    PRIMARY KEY (biz_tag)
);

-- 号段初始数据。没有这两行的话，注册和建链都会报"号段分配失败"
MERGE INTO t_id_segment (biz_tag, max_id, step, update_time)
    KEY (biz_tag) VALUES ('user', 0, 1000, CURRENT_TIMESTAMP);
MERGE INTO t_id_segment (biz_tag, max_id, step, update_time)
    KEY (biz_tag) VALUES ('link', 0, 1000, CURRENT_TIMESTAMP);

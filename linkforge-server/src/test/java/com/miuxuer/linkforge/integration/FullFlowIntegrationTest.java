package com.miuxuer.linkforge.integration;

// ★ 注意是 tools.jackson 而不是 com.fasterxml.jackson ——
// Boot 4 用的是 Jackson 3，容器里注册的 ObjectMapper 也是 Jackson 3 的那个。
// 写成 Jackson 2 的包名编译能过（jjwt-jackson 会把 Jackson 2 带进测试类路径），
// 但运行时找不到对应的 Bean，报的是"没有 ObjectMapper 类型的 Bean"，
// 乍一看完全想不到是包名的问题
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// 注意包名：Boot 4 把 MockMvc 相关的测试自动配置挪到了 webmvc.test 下面，
// 不再是 Boot 3 的 org.springframework.boot.test.autoconfigure.web.servlet
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全链路集成测试。
 *
 * <p><b>和单元测试的区别</b>：单元测试用手写的 mock 验证"某一段逻辑对不对"，
 * 这里把<b>整个 Spring 上下文真的启动起来</b>，走完整的 HTTP 链路 ——
 * 拦截器、参数校验、Service、Mapper、异常处理、JSON 序列化全都参与。
 * 它验证的是"这些零件装在一起能不能跑"，这正是单元测试覆盖不到的部分。
 *
 * <p>依赖：
 *
 * <ul>
 *   <li><b>H2 内存库</b>代替 MySQL（建表脚本见 {@code schema-h2.sql}），
 *       所以换台机器 clone 下来就能跑，不用先装数据库
 *   <li><b>Redis 用 mock 替换</b>（{@code @MockitoBean}）。不这么做的话，
 *       测试就要求本机开着 Redis —— 而 CI 环境不一定有
 * </ul>
 *
 * <p>用 {@code @TestMethodOrder} 按顺序执行：后面几步依赖前面建好的数据。
 * 这是集成测试的常见做法 —— 它本来就是"走一遍完整流程"，
 * 拆成互不相干的用例反而失去了意义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("全链路集成测试")
class FullFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Redis 用 mock 顶掉。
     *
     * <p>注意这会让"缓存命中"之类的分支走不到（mock 的 get 永远返回 null），
     * 所以本测试覆盖的是"缓存未命中 → 查库"这条路径。
     * 缓存本身的逻辑由单元测试覆盖。
     */
    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    private static String aliceToken;
    private static String bobbyToken;
    private static long aliceLinkId;

    /** 发一个带 token 的 GET，返回响应体里的 data 节点。 */
    private JsonNode getData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode postJson(String url, String body, String token) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header("token", token);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        return getData(result);
    }

    // ==================== 1. 注册登录 ====================

    @Test
    @Order(1)
    @DisplayName("注册 → 200，重复注册 → 400")
    void register() throws Exception {
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"test123456","nickname":"爱丽丝"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 同名再注册一次
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"test123456"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    @Order(2)
    @DisplayName("参数校验不通过 → 400 且带上具体字段的提示")
    void registerValidation() throws Exception {
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ab","password":"123"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("用户名长度")));
    }

    @Test
    @Order(3)
    @DisplayName("登录 → 拿到 token；密码错误 → 401")
    void login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"test123456"}"""))
                .andExpect(status().isOk())
                .andReturn();

        aliceToken = getData(result).get("token").asText();
        assertThat(aliceToken).isNotBlank();

        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"wrong-password"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @Order(4)
    @DisplayName("第二个用户，用于验证数据隔离")
    void registerSecondUser() throws Exception {
        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bobby","password":"test123456"}"""))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bobby","password":"test123456"}"""))
                .andExpect(status().isOk())
                .andReturn();

        bobbyToken = getData(result).get("token").asText();
    }

    // ==================== 2. 认证与鉴权 ====================

    @Test
    @Order(5)
    @DisplayName("不带 token 访问受保护接口 → 401")
    void withoutToken() throws Exception {
        mockMvc.perform(get("/api/user/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("未登录或登录已过期，请重新登录"));
    }

    @Test
    @Order(6)
    @DisplayName("token 被篡改 → 401，且不留下任何身份")
    void tamperedToken() throws Exception {
        mockMvc.perform(get("/api/user/profile").header("token", aliceToken + "xx"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(7)
    @DisplayName("★ 普通用户访问管理端 → 403（不是 401）")
    void normalUserAccessAdmin() throws Exception {
        // 403 和 401 必须分开：401 会让前端把用户踢去重新登录，
        // 而重新登录一万次也变不成管理员
        mockMvc.perform(get("/api/admin/user/page").header("token", aliceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("没有访问权限"));
    }

    @Test
    @Order(8)
    @DisplayName("带 token 访问 → 200，且响应里没有密码字段")
    void profile() throws Exception {
        mockMvc.perform(get("/api/user/profile").header("token", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.nickname").value("爱丽丝"))
                // 关键：密码哪怕是密文也不能出现在响应里
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    // ==================== 3. 短链 ====================

    @Test
    @Order(9)
    @DisplayName("创建短链 → 返回短码和完整短链接")
    void createLink() throws Exception {
        JsonNode data = postJson("/api/link",
                """
                {"originalUrl":"https://www.baidu.com","title":"集成测试"}""", aliceToken);

        aliceLinkId = data.get("id").asLong();
        assertThat(data.get("shortCode").asText()).isNotBlank();
        assertThat(data.get("shortUrl").asText()).startsWith("http://localhost:8080/");
        assertThat(data.get("status").asInt()).isEqualTo(1);
    }

    @Test
    @Order(10)
    @DisplayName("非法链接 → 400")
    void createLinkWithBadUrl() throws Exception {
        mockMvc.perform(post("/api/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("token", aliceToken)
                        .content("""
                                {"originalUrl":"not-a-url"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(11)
    @DisplayName("★ 数据隔离：bobby 看不到 alice 的短链，也改不动")
    void tenantIsolation() throws Exception {
        // 列表里看不到
        MvcResult result = mockMvc.perform(get("/api/link/page").header("token", bobbyToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(getData(result).get("total").asLong()).isZero();

        // 按 id 直接查别人的短链 → 403。
        // 这里项目选择的是"403 + 明确文案"而不是"假装不存在返回 404"：
        // 短链 id 是号段生成的、猜的成本不低，而 403 能让用户看懂
        // "这条链是别人的"而不是"我的链不见了"，体验更好
        mockMvc.perform(get("/api/link/" + aliceLinkId).header("token", bobbyToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无权操作该短链"));

        // 改别人的 → 403
        mockMvc.perform(put("/api/link/" + aliceLinkId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("token", bobbyToken)
                        .content("""
                                {"title":"我改了别人的"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(12)
    @DisplayName("★ 关键词搜索不会绕过租户隔离")
    void keywordSearchStaysIsolated() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/link/page").param("keyword", "集成").header("token", bobbyToken))
                .andExpect(status().isOk())
                .andReturn();

        // alice 的短链标题里确实有"集成"两个字，但那是她的数据
        assertThat(getData(result).get("total").asLong()).isZero();
    }

    @Test
    @Order(13)
    @DisplayName("二维码 → 返回合法的 PNG")
    void qrCode() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/link/" + aliceLinkId + "/qrcode")
                        .header("token", aliceToken))
                .andExpect(status().isOk())
                .andReturn();

        byte[] png = result.getResponse().getContentAsByteArray();
        // PNG 文件头：89 50 4E 47
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(png, 1, 3)).isEqualTo("PNG");
        assertThat(result.getResponse().getContentType()).contains("image/png");
    }

    @Test
    @Order(14)
    @DisplayName("看板 → 数据按用户隔离")
    void dashboard() throws Exception {
        MvcResult aliceResult = mockMvc.perform(get("/api/link/stat/overview")
                        .header("token", aliceToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(getData(aliceResult).get("totalLinks").asLong()).isEqualTo(1);

        MvcResult bobbyResult = mockMvc.perform(get("/api/link/stat/overview")
                        .header("token", bobbyToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(getData(bobbyResult).get("totalLinks").asLong()).isZero();
    }

    // ==================== 4. 异常处理 ====================

    @Test
    @Order(15)
    @DisplayName("请求体不是合法 JSON → 400 而不是 500")
    void malformedJson() throws Exception {
        mockMvc.perform(post("/api/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("token", aliceToken)
                        .content("{这不是 JSON}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(16)
    @DisplayName("访问不存在的接口 → 404 而不是 500")
    void unknownEndpoint() throws Exception {
        // 500 的意思是"服务端有 bug"，会把排查方向带偏，也会污染错误率指标
        mockMvc.perform(get("/api/does-not-exist").header("token", aliceToken))
                .andExpect(status().isNotFound());
    }
}

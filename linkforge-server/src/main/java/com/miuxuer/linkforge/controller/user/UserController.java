package com.miuxuer.linkforge.controller.user;

import com.miuxuer.linkforge.dto.UserLoginDTO;
import com.miuxuer.linkforge.dto.UserRegisterDTO;
import com.miuxuer.linkforge.result.Result;
import com.miuxuer.linkforge.service.UserService;
import com.miuxuer.linkforge.vo.UserLoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端接口。
 *
 * <p>Controller 只做三件事：接参数、触发校验、调 Service。不写业务逻辑，也不碰数据库 ——
 * 业务逻辑放进 Service 才能被其它入口（定时任务、管理端、消息消费）复用，
 * 而且测试时不用起 Web 容器。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 用户注册。
     *
     * <p>{@code @Valid} 触发 DTO 上的校验注解，不通过会抛
     * {@code MethodArgumentNotValidException}，由 GlobalExceptionHandler 统一转成 400。
     */
    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid UserRegisterDTO dto) {
        userService.register(dto);
        return Result.success();
    }

    /**
     * 登录，返回 token 和用户基本信息。
     *
     * <p>登录本身不需要 token（这正是用来换 token 的接口），
     * 拦截器里要把它放进白名单。
     */
    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody @Valid UserLoginDTO dto) {
        return Result.success(userService.login(dto));
    }
}

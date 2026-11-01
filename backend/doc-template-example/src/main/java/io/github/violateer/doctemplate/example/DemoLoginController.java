package io.github.violateer.doctemplate.example;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 示例宿主的极简登录模块：内存用户 + Sa-Token 会话，重启即失效。
 *
 * <p>这是「宿主自己的登录」，不属于 Starter。真实项目里换成你自己的登录接口即可，
 * 模块只关心 {@link DemoDocTemplateAuthorizer} 能不能拿到当前用户。</p>
 *
 * <p>登录成功后前端把 tokenValue 写进 {@code localStorage.token}，内嵌管理页默认就读这个键，
 * 并按 {@code Authorization: Bearer <token>} 发送，因此两边不需要额外配置。</p>
 */
@RestController
@RequestMapping("/demo")
public class DemoLoginController {

    /** 演示账号，密码明文，不要抄到生产。 */
    private static final Map<String, String> USERS = Map.of(
            "admin", "Admin@123",
            "demo", "Demo@123");

    /** 当前认证模式，登录页上直接显示出来，方便确认 profile 切换是否生效。 */
    private final String mode;

    public DemoLoginController(@Value("${doc-template.auth.mode:none}") String mode) {
        this.mode = mode;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        String expected = USERS.get(request.username());
        if (expected == null || !expected.equals(request.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "msg", "用户名或密码错误", "success", false));
        }
        StpUtil.login(request.username());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tokenName", "Authorization");
        body.put("tokenPrefix", "Bearer");
        body.put("tokenValue", StpUtil.getTokenValue());
        body.put("username", request.username());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        StpUtil.logout();
        return Map.of("success", true);
    }

    /** 登录页用它判断当前是否已登录；未登录返回 200 + authenticated=false，不抛 401。 */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode.toLowerCase(Locale.ROOT));
        Object loginId = StpUtil.getLoginIdDefaultNull();
        body.put("authenticated", loginId != null);
        if (loginId != null) {
            body.put("username", String.valueOf(loginId));
        }
        return body;
    }

    public record LoginRequest(String username, String password) {
    }
}

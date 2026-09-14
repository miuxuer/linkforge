package com.miuxuer.linkforge.controller.user;

import com.miuxuer.linkforge.annotation.OperateLog;
import com.miuxuer.linkforge.result.Result;
import com.miuxuer.linkforge.service.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文件上传接口。
 *
 * <p>路径在 {@code /api/**} 下，所以只有登录用户能上传 —— 否则就是一个公开的
 * 免费图床，任何人拿到地址都能往里塞东西，账单算在你头上。
 */
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    /**
     * 上传图片。
     *
     * <p>用 {@code multipart/form-data} 而不是把文件塞进 JSON 的 base64 字段：
     * base64 会把体积撑大 33%，而且要先把整个文件读进内存才能序列化。
     * multipart 是流式的，Tomcat 会按 {@code max-file-size} 边收边判断大小，
     * 超限时早早断开连接，不会把内存吃满。
     *
     * <p>参数名固定为 {@code file}，前端用 FormData 时也必须叫这个名字。
     *
     * @return 图片的访问 URL，形如 {@code {"url": "https://..."}}
     */
    @PostMapping
    @OperateLog
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        // 返回 Map 而不是裸字符串：以后要加"文件大小""缩略图地址"时，
        // 直接往 Map 里加字段就行，不用改返回结构让前端跟着改
        return Result.success(Map.of("url", uploadService.uploadImage(file)));
    }
}

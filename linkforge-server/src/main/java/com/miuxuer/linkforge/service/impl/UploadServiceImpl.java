package com.miuxuer.linkforge.service.impl;

import com.miuxuer.linkforge.exception.BusinessException;
import com.miuxuer.linkforge.result.ResultCode;
import com.miuxuer.linkforge.service.UploadService;
import com.miuxuer.oss.AliOssOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/**
 * 文件上传服务实现。
 *
 * <p>校验分三层，缺一层都不够：
 *
 * <ol>
 *   <li><b>大小</b> —— 由 {@code spring.servlet.multipart.max-file-size} 在 Tomcat
 *       解析请求时就拦掉，根本进不到这里。放在最外层是因为它最便宜：
 *       一个 2GB 的文件不该先读进内存再判断"太大了"
 *   <li><b>Content-Type</b> —— 浏览器/客户端给的类型，挡手滑有用，挡攻击没用
 *   <li><b>文件头魔数</b> —— 真正读几个字节看它到底是不是图片。
 *       把 .html 改名成 .png 再声明成 image/png，前两层都拦不住
 * </ol>
 *
 * <p><b>为什么这事在本项目里没那么致命</b>：文件最终落在 OSS 的独立域名上，
 * 不在应用服务器上，所以没有"上传一个 jsp 上去然后访问它就执行了"这种经典剧本。
 * 但放任任意内容进 bucket，等于给人家提供了一个免费图床，
 * 严重时 bucket 会因为违规内容被封。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadServiceImpl implements UploadService {

    /** 允许的图片类型。白名单而不是黑名单：黑名单永远列不全。 */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    /** 判定魔数需要的最少字节数（WEBP 要看到第 12 个字节）。 */
    private static final int MAGIC_BYTES_LENGTH = 12;

    private final AliOssOperator aliOssOperator;

    @Override
    public String uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传文件不能为空");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "只支持 jpg / png / gif / webp 格式的图片");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            // 读不出内容通常是客户端中途断开，不是服务端的问题，但也没法继续
            log.warn("读取上传文件失败: {}", file.getOriginalFilename(), e);
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件读取失败，请重新上传");
        }

        if (!looksLikeImage(content)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件内容不是有效的图片");
        }

        try {
            String url = aliOssOperator.upload(content, file.getOriginalFilename());
            log.info("图片上传成功: {}", url);
            return url;
        } catch (Exception e) {
            // OSS 抛出的底层异常信息可能带着 endpoint、bucket、内部错误码，
            // 不适合直接给前端看。日志里留全量，返回给用户一句能理解的
            log.error("图片上传到 OSS 失败", e);
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "图片上传失败，请稍后重试");
        }
    }

    /**
     * 按文件头魔数判断是不是图片。
     *
     * <p>只看前 12 个字节就够，不用把整个文件读一遍：
     *
     * <ul>
     *   <li>JPEG：{@code FF D8 FF}
     *   <li>PNG：{@code 89 50 4E 47}
     *   <li>GIF：{@code 47 49 46 38}（"GIF8"）
     *   <li>WEBP：前 4 字节是 "RIFF"，第 8~11 字节是 "WEBP"
     * </ul>
     *
     * <p>魔数同样可以伪造（在文件头塞几个字节而已），它挡不住铁了心要传恶意内容的人。
     * 它的价值在于挡住"改名党"和"手滑传错文件"，成本只有一次几字节的比较。
     * 要真正防住得靠服务端重新编码图片（把内容解码再编码，任何非图片数据都会在这一步丢掉），
     * 那样开销大得多，本项目不做。
     */
    private boolean looksLikeImage(byte[] bytes) {
        if (bytes == null || bytes.length < MAGIC_BYTES_LENGTH) {
            return false;
        }
        return isJpeg(bytes) || isPng(bytes) || isGif(bytes) || isWebp(bytes);
    }

    private boolean isJpeg(byte[] b) {
        return (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] b) {
        return (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private boolean isGif(byte[] b) {
        return b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8';
    }

    private boolean isWebp(byte[] b) {
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }
}

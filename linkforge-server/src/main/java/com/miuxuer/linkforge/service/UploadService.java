package com.miuxuer.linkforge.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传服务。
 */
public interface UploadService {

    /**
     * 上传一张图片，返回可公开访问的 URL。
     *
     * @param file 上传的文件
     * @return 图片的访问地址
     * @throws com.miuxuer.linkforge.exception.BusinessException 文件为空、格式不支持
     */
    String uploadImage(MultipartFile file);
}

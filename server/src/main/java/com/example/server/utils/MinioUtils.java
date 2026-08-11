package com.example.server.utils;

import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class MinioUtils {

    private final MinioClient minioClient;
    private final String bucketName;
    private final String endpoint;

    public MinioUtils(MinioClient minioClient,
                      @Value("${minio.bucketName}") String bucketName,
                      @Value("${minio.endpoint}") String endpoint) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
        this.endpoint = endpoint;
    }

    /** 获取 MinIO 访问端点 (供外部拼接文件 URL) */
    public String getEndpoint() { return endpoint; }

    /** 获取存储桶名称 */
    public String getBucketName() { return bucketName; }

    /**
     * 上传文件并返回访问 URL
     */
    public String uploadFile(MultipartFile file) throws Exception {
        // 1. 生成新文件名 (UUID防止重名)
        String originalFilename = file.getOriginalFilename();
        String suffix = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String newFilename = UUID.randomUUID().toString() + suffix;

        // 2. 上传到 MinIO
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(newFilename)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
        }

        // 3. 拼接返回 Public 访问地址
        return endpoint + "/" + bucketName + "/" + newFilename;
    }

    /**
     * 【新增】从 MinIO 删除文件
     * @param fileUrl 文件的完整 URL
     */
    public void removeFile(String fileUrl) {
        try {
            // 解析文件名
            String objectName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);

            // 调用 MinIO 删除
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );

            System.out.println(" MinIO 文件已删除: " + objectName);
        } catch (Exception e) {
            System.err.println(" MinIO 删除失败: " + e.getMessage());
        }
    }

    /**
     * 【新增】上传本地 File 对象到 MinIO
     */
    public String uploadLocalFile(java.io.File file) throws Exception {
        try (java.io.FileInputStream inputStream = new java.io.FileInputStream(file)) {
            minioClient.putObject(
                    io.minio.PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(file.getName()) // 文件名已经包含 UUID
                            .stream(inputStream, file.length(), -1)
                            .contentType("video/mp4") // 默认当 mp4 处理
                            .build()
            );
        }

        return endpoint + "/" + bucketName + "/" + file.getName();
    }

    // ============================================================
    // 分片上传重构：新增方法
    // ============================================================

    /** 分片对象的 MinIO 前缀 */
    private static final String CHUNK_PREFIX = "chunks/";

    /**
     * 【分片上传】上传单个分片到 MinIO
     * @param uploadId    上传任务会话ID
     * @param chunkIndex  分片序号 (0-based)
     * @param inputStream 分片数据流
     * @param size        分片字节数
     */
    public void uploadChunkObject(String uploadId, int chunkIndex, InputStream inputStream, long size) throws Exception {
        String objectName = CHUNK_PREFIX + uploadId + "/" + chunkIndex;
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .stream(inputStream, size, -1)
                        .contentType("application/octet-stream")
                        .build()
        );
    }

    /**
     * 【分片合并】将指定 uploadId 的所有分片在 MinIO 服务端合并为一个完整文件
     * 使用 composeObject API，零下载带宽，最多支持 1000 个源分片
     * @param uploadId          上传任务会话ID
     * @param totalChunks       总分片数
     * @param targetObjectName  合并后的目标对象名 (如 "{uuid}.mp4")
     */
    public void composeObjects(String uploadId, int totalChunks, String targetObjectName) throws Exception {
        if (totalChunks > 1000) {
            throw new IllegalArgumentException("单次合并最多支持 1000 个分片，当前: " + totalChunks);
        }

        List<ComposeSource> sources = new ArrayList<>(totalChunks);
        for (int i = 0; i < totalChunks; i++) {
            sources.add(
                    ComposeSource.builder()
                            .bucket(bucketName)
                            .object(CHUNK_PREFIX + uploadId + "/" + i)
                            .build()
            );
        }

        minioClient.composeObject(
                ComposeObjectArgs.builder()
                        .bucket(bucketName)
                        .object(targetObjectName)
                        .sources(sources)
                        .build()
        );
    }

    /**
     * 【分片清理】批量删除指定 uploadId 的所有分片对象
     * @param uploadId 上传任务会话ID
     */
    public void deleteChunkObjects(String uploadId) {
        String prefix = CHUNK_PREFIX + uploadId + "/";
        try {
            List<DeleteObject> toDelete = new ArrayList<>();
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .prefix(prefix)
                            .build()
            );

            for (Result<Item> result : results) {
                Item item = result.get();
                toDelete.add(new DeleteObject(item.objectName()));
            }

            if (!toDelete.isEmpty()) {
                minioClient.removeObjects(
                        RemoveObjectsArgs.builder()
                                .bucket(bucketName)
                                .objects(toDelete)
                                .build()
                );
                System.out.println("MinIO 分片已清理: " + prefix + " (" + toDelete.size() + " 个对象)");
            }
        } catch (Exception e) {
            System.err.println("MinIO 分片清理失败 [" + uploadId + "]: " + e.getMessage());
        }
    }

    /**
     * 【分片列表】列出指定 uploadId 下的所有分片对象名
     * @param uploadId 上传任务会话ID
     * @return 分片对象名列表
     */
    public List<String> listChunkObjects(String uploadId) throws Exception {
        String prefix = CHUNK_PREFIX + uploadId + "/";
        List<String> objectNames = new ArrayList<>();

        Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucketName)
                        .prefix(prefix)
                        .build()
        );

        for (Result<Item> result : results) {
            objectNames.add(result.get().objectName());
        }
        return objectNames;
    }

    /**
     * 【工具方法】从 MinIO 获取对象输入流(用于 MD5 计算等)
     * @param objectName 对象名
     * @return 对象输入流，调用方负责关闭
     */
    public InputStream getObjectStream(String objectName) throws Exception {
        return minioClient.getObject(
                io.minio.GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build()
        );
    }

}
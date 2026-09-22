package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.ChunkUploadDTO;
import com.example.server.service.ChunkUploadService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 分片上传控制器
 * <p>
 * 路径: /media/api/chunk/*，与现有 MediaController 共用 /media 前缀。
 * <p>
 * 异常统一由 {@link ApiExceptionHandler} 处理，Controller 层不需要 try-catch。
 */
@RestController
@RequestMapping("/media/api/chunk")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class ChunkController {

    private final ChunkUploadService chunkUploadService;

    public ChunkController(ChunkUploadService chunkUploadService) {
        this.chunkUploadService = chunkUploadService;
    }

    /**
     * 初始化分片上传
     */
    @PostMapping("/init")
    public Result<ChunkUploadDTO.InitResponse> initUpload(@RequestBody Map<String, Object> body) {
        ChunkUploadDTO.InitRequest request = new ChunkUploadDTO.InitRequest();
        request.setFileName((String) body.get("fileName"));
        request.setFileSize(toLong(body.get("fileSize")));
        request.setTotalChunks(toInt(body.get("totalChunks")));
        request.setUserId(toLong(body.get("userId")));
        request.setForce(Boolean.TRUE.equals(body.get("force")));
        return Result.ok(chunkUploadService.initUpload(request));
    }

    /**
     * 查询上传状态
     */
    @PostMapping("/check")
    public Result<ChunkUploadDTO.CheckResponse> checkStatus(@RequestBody Map<String, Object> body) {
        String uploadId = (String) body.get("uploadId");
        return Result.ok(chunkUploadService.checkStatus(uploadId));
    }

    /**
     * 上传单个分片
     */
    @PostMapping("/upload")
    public Result<Map<String, Object>> uploadChunk(@RequestParam("uploadId") String uploadId,
                                                    @RequestParam("chunkIndex") int chunkIndex,
                                                    @RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("分片文件为空");
        }
        chunkUploadService.uploadChunk(uploadId, chunkIndex, file);
        return Result.ok(Map.of("chunkIndex", chunkIndex, "status", "OK"));
    }

    /**
     * 合并分片
     */
    @PostMapping("/merge")
    public Result<ChunkUploadDTO.MergeResponse> mergeChunks(@RequestBody Map<String, Object> body) throws Exception {
        String uploadId = (String) body.get("uploadId");
        Long userId = toLong(body.get("userId"));
        ChunkUploadDTO.MergeResponse resp = chunkUploadService.mergeChunks(uploadId, userId);
        if ("MERGING".equals(resp.getStatus())) {
            throw new IllegalStateException("合并正在进行中，请稍后...");
        }
        return Result.ok(resp);
    }

    /**
     * 取消上传
     */
    @DeleteMapping("/cancel")
    public Result<Map<String, String>> cancelUpload(@RequestBody Map<String, Object> body) {
        String uploadId = (String) body.get("uploadId");
        chunkUploadService.cancelUpload(uploadId);
        return Result.ok(Map.of("status", "CANCELLED"));
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        return Long.valueOf(v.toString());
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.valueOf(v.toString());
    }
}

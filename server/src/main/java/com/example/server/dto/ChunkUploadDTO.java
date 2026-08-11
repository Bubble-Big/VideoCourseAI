package com.example.server.dto;

import java.util.List;
import java.util.Set;

/**
 * 分片上传相关的请求/响应 DTO
 */
public class ChunkUploadDTO {

    // ---------- init 接口 ----------

    public static class InitRequest {
        private String fileName;
        private Long fileSize;
        private Integer totalChunks;
        private Long userId;

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public Long getFileSize() { return fileSize; }
        public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
        public Integer getTotalChunks() { return totalChunks; }
        public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
    }

    public static class InitResponse {
        private String uploadId;
        private String status;       // NEW / RESUME / HINT_DUPLICATE
        private Long existingMediaId;
        private Set<Integer> completedChunks;

        public InitResponse() {}
        public InitResponse(String uploadId, String status) {
            this.uploadId = uploadId;
            this.status = status;
        }

        public String getUploadId() { return uploadId; }
        public void setUploadId(String uploadId) { this.uploadId = uploadId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getExistingMediaId() { return existingMediaId; }
        public void setExistingMediaId(Long existingMediaId) { this.existingMediaId = existingMediaId; }
        public Set<Integer> getCompletedChunks() { return completedChunks; }
        public void setCompletedChunks(Set<Integer> completedChunks) { this.completedChunks = completedChunks; }
    }

    // ---------- check 接口 ----------

    public static class CheckRequest {
        private String uploadId;
        public String getUploadId() { return uploadId; }
        public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    }

    public static class CheckResponse {
        private String uploadId;
        private String status;       // UPLOADING / COMPLETED / NOT_FOUND
        private String fileName;
        private Long fileSize;
        private Integer totalChunks;
        private Set<Integer> completedChunks;
        private Long mediaId;        // 合并完成后才有

        public String getUploadId() { return uploadId; }
        public void setUploadId(String uploadId) { this.uploadId = uploadId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public Long getFileSize() { return fileSize; }
        public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
        public Integer getTotalChunks() { return totalChunks; }
        public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }
        public Set<Integer> getCompletedChunks() { return completedChunks; }
        public void setCompletedChunks(Set<Integer> completedChunks) { this.completedChunks = completedChunks; }
        public Long getMediaId() { return mediaId; }
        public void setMediaId(Long mediaId) { this.mediaId = mediaId; }
    }

    // ---------- merge 接口 ----------

    public static class MergeRequest {
        private String uploadId;
        private Long userId;

        public String getUploadId() { return uploadId; }
        public void setUploadId(String uploadId) { this.uploadId = uploadId; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
    }

    public static class MergeResponse {
        private Long mediaId;
        private String filePath;
        private String status;

        public Long getMediaId() { return mediaId; }
        public void setMediaId(Long mediaId) { this.mediaId = mediaId; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    // ---------- cancel 接口 ----------

    public static class CancelRequest {
        private String uploadId;
        public String getUploadId() { return uploadId; }
        public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    }
}

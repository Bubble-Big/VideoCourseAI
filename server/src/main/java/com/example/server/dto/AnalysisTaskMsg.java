package com.example.server.dto;

import java.io.Serializable;

//必须实现Serializable接口，否则不能在网络上传输
public class AnalysisTaskMsg implements Serializable {
    private Long mediaId;
    private String action; //例如"START_ANALYSIS"
    private String contentHash; // 内容指纹（MD5 标准化），供消费侧内容级锁 / 幂等 / 复用使用
    private Boolean force; // 是否强制重新生成（跳过复用逻辑）

    public AnalysisTaskMsg() {}

    public AnalysisTaskMsg(Long mediaId, String action) {
        this.mediaId = mediaId;
        this.action = action;
    }

    public AnalysisTaskMsg(Long mediaId, String action, String contentHash) {
        this.mediaId = mediaId;
        this.action = action;
        this.contentHash = contentHash;
    }

    public AnalysisTaskMsg(Long mediaId, String action, String contentHash, Boolean force) {
        this.mediaId = mediaId;
        this.action = action;
        this.contentHash = contentHash;
        this.force = force;
    }

    public Long getMediaId() { return mediaId; }
    public void setMediaId(Long mediaId) { this.mediaId = mediaId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Boolean getForce() { return force; }
    public void setForce(Boolean force) { this.force = force; }
}

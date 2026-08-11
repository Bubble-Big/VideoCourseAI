package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("media_files")
public class MediaFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;          // 核心：记录是谁传的

    private String filename;
    private String status;        //UPLOADED, COMPLETED
    private String filePath;

    //下面这几个是新加的
    private String aiSummary;
    private String transcriptText;
    private String coverUrl;

    // 分片上传重构：新增文件大小和 MD5 字段
    private Long fileSize;
    private String fileMd5;

    //上传时间由数据库自动记录，Java 不插手，防止报错
    private LocalDateTime uploadTime;
}
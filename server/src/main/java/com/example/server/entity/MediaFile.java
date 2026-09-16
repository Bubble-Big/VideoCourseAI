package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("media_files")
public class MediaFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String filename;
    private String status;        // UPLOADED, COMPLETED
    private String filePath;
    private Long fileSize;
    private String fileMd5;
    private String coverUrl;
    private LocalDateTime uploadTime;

    @Version
    private Integer version;      // 乐观锁版本号
}
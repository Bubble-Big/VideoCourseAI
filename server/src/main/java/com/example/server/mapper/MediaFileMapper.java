package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.MediaFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {

    /**
     * 按内容 MD5 反查一条已成功分析的记录（排除自身），用于归属缓存失效时的 DB 兜底复用。
     */
    @Select("SELECT * FROM media_files WHERE file_md5 = #{md5} AND ai_status = 'SUCCESS' " +
            "AND ai_summary IS NOT NULL AND ai_summary <> '' AND id <> #{excludeId} " +
            "ORDER BY id DESC LIMIT 1")
    MediaFile selectCompletedAnalysisByMd5(@Param("md5") String md5, @Param("excludeId") Long excludeId);

    /**
     * 按内容 MD5 反查一条已成功转写的记录（排除自身），用于归属缓存失效时的 DB 兜底复用。
     */
    @Select("SELECT * FROM media_files WHERE file_md5 = #{md5} AND transcript_status = 'SUCCESS' " +
            "AND transcript_text IS NOT NULL AND transcript_text <> '' AND id <> #{excludeId} " +
            "ORDER BY id DESC LIMIT 1")
    MediaFile selectCompletedTranscriptByMd5(@Param("md5") String md5, @Param("excludeId") Long excludeId);

}
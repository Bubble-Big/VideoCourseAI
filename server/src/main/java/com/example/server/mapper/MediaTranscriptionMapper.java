package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.MediaTranscription;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MediaTranscriptionMapper extends BaseMapper<MediaTranscription> {

    /**
     * 按内容 MD5 反查一条已成功转写的记录（归属复用）
     */
    @Select("SELECT mt.* FROM media_transcription mt " +
            "JOIN media_files mf ON mt.media_id = mf.id " +
            "WHERE mf.file_md5 = #{md5} AND mt.status = 'SUCCESS' " +
            "AND mt.transcript_text IS NOT NULL AND mt.transcript_text <> '' " +
            "AND mt.media_id <> #{excludeMediaId} " +
            "ORDER BY mt.id DESC LIMIT 1")
    MediaTranscription selectCompletedTranscriptByMd5(@Param("md5") String md5,
                                                      @Param("excludeMediaId") Long excludeMediaId);

    /**
     * 查「卡死」的转写记录（补偿调度器专用）
     */
    @Select("SELECT * FROM media_transcription " +
            "WHERE status = 'PROCESSING' " +
            "AND process_at < #{threshold} " +
            "ORDER BY process_at ASC LIMIT #{limit}")
    List<MediaTranscription> selectStalledTranscription(@Param("threshold") LocalDateTime threshold,
                                                        @Param("limit") int limit);
}

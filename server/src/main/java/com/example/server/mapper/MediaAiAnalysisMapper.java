package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.MediaAiAnalysis;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MediaAiAnalysisMapper extends BaseMapper<MediaAiAnalysis> {

    /**
     * 按内容 MD5 反查一条已成功分析的记录（归属复用）
     */
    @Select("SELECT ma.* FROM media_ai_analysis ma " +
            "JOIN media_files mf ON ma.media_id = mf.id " +
            "WHERE mf.file_md5 = #{md5} AND ma.status = 'SUCCESS' " +
            "AND ma.summary IS NOT NULL AND ma.summary <> '' AND ma.media_id <> #{excludeMediaId} " +
            "ORDER BY ma.id DESC LIMIT 1")
    MediaAiAnalysis selectCompletedAnalysisByMd5(@Param("md5") String md5,
                                                  @Param("excludeMediaId") Long excludeMediaId);

    /**
     * 查「卡死」的 AI 分析记录（补偿调度器专用）
     */
    @Select("SELECT * FROM media_ai_analysis " +
            "WHERE status IN ('PENDING','PROCESSING') " +
            "AND process_at < #{threshold} " +
            "ORDER BY process_at ASC LIMIT #{limit}")
    List<MediaAiAnalysis> selectStalledAnalysis(@Param("threshold") LocalDateTime threshold,
                                                 @Param("limit") int limit);
}

package com.example.server.common;

/**
 * ContentTaskGate 执行结果枚举
 * <p>
 * 用于统一表达「内容级串行原语」的三种结果状态，强制不变量：
 * <strong>「跳过 ⇒ 复用或回滚」——任何非 PROCEED 路径都必须显式复用他人结果或回滚到可重试态，禁止静默返回成功。</strong>
 * </p>
 *
 * <ul>
 * <li>{@link #PROCEED} - 我持有锁并已执行完毕，结果已落库</li>
 * <li>{@link #REUSE} - 他人已完成，结果已在锁内回填（从 Redis 归属缓存或 DB 反查）</li>
 * <li>{@link #DEFER} - 他人进行中或锁超时，本次让位（<strong>调用方必须交补偿/回滚，禁止静默成功</strong>）</li>
 * </ul>
 *
 * <p>
 * 业务层使用 {@code switch} 穷举处理三种状态，缺少 {@code DEFER} 分支时编译器会告警。
 * </p>
 */
public enum GateOutcome {

    /**
     * 我持有锁并已执行完毕
     * <p>
     * 任务在锁保护下成功执行，结果已落库并登记归属缓存。
     * </p>
     */
    PROCEED,

    /**
     * 他人已完成，结果已回填
     * <p>
     * 从 Redis 归属缓存或 DB 反查到其他 mediaId 的已完成结果，
     * 已在锁内复用回填到当前记录。
     * </p>
     */
    REUSE,

    /**
     * 他人进行中或锁超时，本次让位
     * <p>
     * <strong>调用方必须处理：</strong>
     * <ul>
     * <li>保持状态为 PENDING/PROCESSING，交由补偿调度器兜底</li>
     * <li>或显式回滚状态到可重试态（如 transcriptStatus → NONE）</li>
     * <li>禁止静默返回成功，否则会造成永久卡 PENDING</li>
     * </ul>
     * </p>
     */
    DEFER
}

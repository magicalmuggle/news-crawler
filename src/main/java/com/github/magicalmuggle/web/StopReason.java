package com.github.magicalmuggle.web;

// 运行结束的原因，与状态分开记录。
public enum StopReason {
    COMPLETED,
    BUDGET_EXHAUSTED,
    CIRCUIT_TRIPPED,
    INTERRUPTED,
    FAILED
}

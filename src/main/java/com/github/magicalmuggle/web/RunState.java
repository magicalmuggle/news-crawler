package com.github.magicalmuggle.web;

// 一次抓取运行的状态。IDLE 表示还没有跑过。
public enum RunState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    FINISHED,
    FAILED;

    public boolean isActive() {
        return this == STARTING || this == RUNNING || this == STOPPING;
    }
}

package com.x3card.pet.ai;

/** 停掉一个请求：停掉以后不再回调（在回调用的 Executor 线程上调用才保证这一点） */
public interface Stop {
    void stop();
}

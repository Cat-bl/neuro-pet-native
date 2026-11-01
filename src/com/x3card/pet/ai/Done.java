package com.x3card.pet.ai;

/** 一次性的结果回调（对应 JS 的 Promise），在调用方给的 Executor 上调 */
public interface Done<T> {
    void ok(T v);
    void fail(Err e);
}

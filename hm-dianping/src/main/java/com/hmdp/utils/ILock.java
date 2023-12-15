package com.hmdp.utils;

/**
 * ClassName: ILock
 * Package: com.hmdp.utils
 * Description
 *
 * @Author HuanZ
 * @Create 2023/11/17 9:57
 * @Version 1.0
 */
public interface ILock {
    boolean tryLock(long timeoutSec);
    void  unlock();
}

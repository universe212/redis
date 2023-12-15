package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * ClassName: CacheClient
 * Package: com.hmdp.utils
 * Description
 *
 * @Author HuanZ
 * @Create 2023/11/15 9:51
 * @Version 1.0
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }//set 加入redis
    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,
                                         Long time, TimeUnit unit){
        String key = keyPrefix + id;
        //1.redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在 查询json存在redis直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);

        }
        //不存在如果 json不等于空说明错误
        if(json != null){
            //返回错误信息
            return null;
        }
        //不存在值为Null 说明需要查询加入到redis
        R r = dbFallback.apply(id);
        //存在返回
        if(r == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //数据库不存在把空值写进null
            return null;
        }
        this.set(key,r,time,unit);
       //不存在根据id查询数据库
        return r;
    }


    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,
                                       Long time, TimeUnit unit){
        String key = keyPrefix+ id;
        //1.redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if(StrUtil.isBlank(json)){
            return null;
        }
        //4.命中需要把json反序列化对象
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        R  r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        //5.1未过期，直接返回信息
        //5.2过期，需要缓存重建
        //缓存重建
//        获取互斥锁
        String lockKey = keyPrefix+ id;
        boolean isLock = tryLock(lockKey);
//        判断是否获取锁成功
        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                //重建缓存
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入REDIS
                    this.setWithLogicalExpire(key,r1,time,unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
//        成功，开启独立线程
        //失败直接返回过期商铺信息

        return r;
    }
}

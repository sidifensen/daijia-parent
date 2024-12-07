package com.atguigu.daijia.common.constant;

public class RedisConstant {

    //用户登录
    public static final String USER_LOGIN_KEY_PREFIX = "user:login:";
    public static final String USER_LOGIN_REFRESH_KEY_PREFIX = "user:login:refresh:";
    public static final int USER_LOGIN_KEY_TIMEOUT = 60 * 60 * 24 * 100; //100天
    public static final int USER_LOGIN_REFRESH_KEY_TIMEOUT = 60 * 60 * 24 * 365;

    //(下单前) 用于搜索司机GEO地址
    public static final String DRIVER_GEO_LOCATION = "driver:geo:location";
    //一个订单里有哪些司机(订单的临时推送给司机的集合set)
    public static final String DRIVER_ORDER_REPEAT_LIST = "driver:order:repeat:list:";
    public static final long DRIVER_ORDER_REPEAT_LIST_EXPIRES_TIME = 16;//1+15分钟
    //司机的手上的订单的临时队列
    public static final String DRIVER_ORDER_TEMP_LIST = "driver:order:temp:list:";
    public static final long DRIVER_ORDER_TEMP_LIST_EXPIRES_TIME = 1; // 设置为1分钟

//    //订单与任务关联
//    public static final String ORDER_JOB = "order:job:";
//    public static final long ORDER_JOB_EXPIRES_TIME = 15;

    //下单后,司机赶往代驾点.实时更新司机的位置
    public static final String UPDATE_ORDER_LOCATION = "update:order:location:";
    public static final long UPDATE_ORDER_LOCATION_EXPIRES_TIME = 15;

    //订单接单标识 用于判断是否在等待，标识不存在了说明不在等待接单状态了,默认超时时间为15分钟
    public static final String ORDER_ACCEPT_MARK = "order:accept:mark:";
    public static final long ORDER_ACCEPT_MARK_EXPIRES_TIME = 15;

    //抢新订单的分布式锁
    public static final String ROB_NEW_ORDER_LOCK = "rob:new:order:lock";
    //等待获取锁的时间
    public static final long ROB_NEW_ORDER_LOCK_WAIT_TIME = 1;
    //加锁的时间
    public static final long ROB_NEW_ORDER_LOCK_LEASE_TIME = 1;

    //优惠券信息
    public static final String COUPON_INFO = "coupon:info:";

    //获取优惠券的分布式锁
    public static final String COUPON_RECEIVE_LOCK = "coupon:receive:lock:";
    //等待获取锁的时间
    public static final long COUPON_LOCK_WAIT_TIME = 1;
    //加锁的时间
    public static final long COUPON_LOCK_LEASE_TIME = 1;

    //TODO 新增 使用优惠券的分布式锁
    public static final String COUPON_USE_LOCK = "coupon:use:lock:";
    public static final long COUPON_USE_LOCK_WAIT_TIME = 1;
    public static final long COUPON_USE_LOCK_LEASE_TIME = 1;
}

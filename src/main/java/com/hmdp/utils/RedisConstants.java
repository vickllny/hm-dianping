package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 1800L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final Long CACHE_SHOP_NULL_TTL = 10L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;
    public static final Long CACHE_LOCK_SHOP_TTL = 10L;

    public static final Long CACHE_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";

    public static final String CACHE_SECKILL_VOUCHER_KEY = "seckill:voucher:";
    public static final long CACHE_SECKILL_VOUCHER_TTL = 30L;

    public static final String CACHE_SHOP_TYPE_LIST_KEY = "cache:shop-type";

    public static final long CACHE_SHOP_TYPE_TTL = 30L;

    public static final String VOUCHER_ORDER_KEY_PREFIX = "voucher:order";

    public static final String LOCK_VOUCHER_ORDER_KEY = "lock:voucher:order";

    public static final String SECKILL_ORDER_KEY = "seckill:order:";

    public static final String BLOG_LIKED_KEY = "blog:liked:";

    public static final String FOLLOW_USER_KEY = "follow:user:";
    public static final String FOLLOW_FEED_KEY = "follow:feed:";
    public static final String SHOP_GEO_ALL_KEY = "shop:geo:all:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "user:sign:";
}

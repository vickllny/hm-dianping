if (redis.call('hexists', KEYS[1], ARGV[3]) == 0) then
    return nil;
end ;

local counter = redis.call('hincrby', KEYS[1], ARGV[3], -1);
if (counter > 0) then
    redis.call('pexpire', KEYS[1], ARGV[2]);
    return 0;
else
    redis.call('del', KEYS[1]);
    redis.call('publish', KEYS[2], ARGV[1]); --发布消息通知可能正在等待的线程
    return 1;
end ;
return nil;

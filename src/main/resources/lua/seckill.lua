--库存
-- 优惠券id
local stockKey = ARGV[1]
-- 订单key
local orderKey = ARGV[2]
-- 用户id
local userId = ARGV[3]

--判断库存
if tonumber(redis.call('get', stockKey)) < 1 then
    return 1
end

-- 一人一单
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

--
redis.call('sadd', orderKey, userId)
redis.call('incrby', stockKey, -1)
return 0
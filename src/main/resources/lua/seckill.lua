--库存
-- 优惠券id
local voucherId = ARGV[1]
-- 订单id
local orderId = ARGV[2]
-- 用户id
local userId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId

local orderKey = 'seckill:order:' .. voucherId

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
-- 发送消息
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0



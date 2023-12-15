--优惠劵ID
local voucherId = ARGV[1]
--用户id
local userId = ARGV[2]
--订单id
local orderId = ARGV[3]
--数据库key
--库存key
local stockKey = 'seckill:stock:' .. voucherId
--订单key
local orderKey = 'seckill:order:' .. voucherId
--判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <= 0) then
    return 1
end
--判断是否下单
if(redis.call('sismember',orderKey,userId) == 1) then
    return 2
end

redis.call('incrby',stockKey,-1)
--下单保存用户
redis.call('sadd',orderKey,userId)
--发送消息给队列
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0
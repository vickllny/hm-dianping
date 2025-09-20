# hm-dianping

### Redis主从
#### 1、 安装
在从节点配置文件中增加slaveof或者replicaof masterIp masterPort 即可
#### 2、 主从同步
##### 2.1、全量同步
当从节点第一次请求主节点同步数据时会发生全量同步，主节点会将内存中数据刷到RDB文件中，通过文件拷贝或者网络io传输RDB文件，从节点加载该RDB文件
##### 2.2、增量同步
从节点请求数据同步会携带replicaId和offset参数，主节点会将offset之后的记录传输给从节点，从节点收到更新命令后执行更新
##### 2.3、区别
全量使用RDB通过拷贝或者网络io流传输到从节点，从节点flush已有的数据并load新的RDB文件到自己内存中； 增量同步是从节点请求主节点进行同步，请求时带上replicaId和offset，主节点将repl_baklog中offset之后的命令传输给从节点，此外当
从节点因为其他原因offset被覆盖，则需要进行全量同步
#### 3、优化
##### 3.1.尽量避免全量同步 
##### 3.2.当磁盘io较慢或者网络带宽较大时，可以设置通过网络io进行全量同步 
##### 3.3存在多个从节点时，可以采用主-从-从的方式减轻主节点同步时的压力 
##### 3.4.可适当增大repl_baklog的大小，避免从节点的offset较容易被覆盖，从而产生全量同步

### 4. Redis-cluster
### 5. Redis数据结构
#### 5.1 SDS:Simple Dynamic String 简单动态字符串，是为了解决C中字符串存在的问题而封装的数据结构
##### 5.1.1 C中的字符串存在如下问题
###### 5.1.1.1 因为C中字符串底层是一个char数组，获取字符串长度需要遍历数组元素再减一操作，时间复杂度O(N)）
###### 5.1.1.2 二进制安全问题，char数组规定以字符`\0`作为结束标识，所以在数组非结束元素中不能使用`\0`字符
###### 5.1.1.3 字符数组不可修改
##### 5.1.2 SDS如何解决以上问题
###### 5.1.2.1 使用结构实现，结构中有`len`、`alloc`、`flags`、`char buf[]`，对字符串的增删改查都需要调用结构中的方法
###### 5.1.2.2 `len`类型有`uint8_t`、`uint16_t`、`uint32_t`、`uint64_t`，表示该结构体对象中的字符元素个数，在获取字符串长度时直接取`len`值即可，并且`char buf[]`中可以存储`\0`元素，因为不以`\0`为结束标识，但是为了兼容C，最后一个元素的值固定为`\0`
###### 5.1.2.3 `alloc`类型有`uint8_t`、`uint16_t`、`uint32_t`、`uint64_t`，表示该结构体对象中的`char buf[]`数组真实长度，包括空的元素
###### 5.1.2.4 `flags`是标识和控制SDS的头大小，直接读取该值就可知道当前SDS每个字符元素占用的空间
###### 5.1.2.5 `char buf[]`用于存储字符，该数组最后一个值固定为`\0`，同时在结构体中可修改该数组的长度，在新增字符时会进行扩容，并且是预扩容
#### 5.2 IntSet 有序无重复数组
##### 5.2.1 包含结构如下`uint32_t length`、`uint32_t encoding`、`int8_t contents[]`
###### 5.2.1.1 `uint32_t encoding`标识当前结构对象所支持的整数所占大小，可选值有`INTSET_ENC_16`、`INTSET_ENC_32`、`INTSET_ENC_64`
###### 5.2.1.2 `uint32_t length`标识当前结构对象元素个数
###### 5.2.1.3 `int8_t contents[]`真实存储元素，并且是有序存储，连续的内存空间
##### 5.2.2 数组扩容原理
###### 5.2.2.1 首先判断新增的值的`enc`，如果`enc`大于当前`enc`，则需要更新当前结构对象`enc`，并且将数组中的元素从后往前依次升级编码（也就是所占用的内存空间），最后将新元素插入到数组第一个位置或者最后
###### 5.2.2.2 如果`enc`不大于当前`enc`，则首先需要在已有元素中使用二分查找该元素是否存在，如果存在直接返回（同时在查找发方法中会计算新元素应该在数组中插入的位置`&pos`），否则将数组长度+1，再依次将`pos`位置后的元素往后移动一个位置，最后再将新元素插入到数组中

### 6. DICT
#### 6.1 `dict`是 redis 的字典结构实现，内部包含 2 个`dictht`哈希表，正常情况下是使用的第一个，在渐进式`rehash`时同时使用，新增时使用第二个`dichht`，查询、删除、更新时 2 个同时使用
#### 6.2 `dictEntry`是链表结构，用于存放每个一`kv`数据，当发生 hash 冲突时，会将新的 kv 放在链表头部
#### 6.3 `rehash`下的扩容：1、计算哈希表是否`used/size`>=1 && (`!(BGSAVE || BGREWRITEAOF)` || `used/size >=5`)，如果为 true，则将`rehashidx`的值设置为`0`，标识开始 rehash，计算`used+1`的最小 2 的 n 次方数作为第二个`dictht`的初始数组大小，rehash 不是一次就完成，是每次增删改查时去处理第一个`dictht`中的`rehashidx`元素的节点，直到处理完成每个`dictEntry`，释放第一个`dictht`的内存，再将第二个`dictht`的引用赋值给第一个`dictht`，将`rehashidx`设置为`-1`
#### 6.4 `rehash`下的收缩：1、计算哈希表是否`used*100/size` <= 0.1，如果为 true，则将`rehashidx`的值设置为`0`，标识开始 rehash，计算`used+1`的最小 2 的 n 次方数作为第二个`dictht`的初始数组大小，接下来的步骤同扩容时一致

### 7. ZipList
#### 7.1 zlbytes 4字节，标识当前 zipList 的总长度
#### 7.2 zltail 4字节，表示最后一个`entry` 的偏移量
#### 7.3 zllen 2字节，表示`entry`的个数
#### 7.4 entry 存放`ZipListEntry`
##### 7.4.1 previous_entry_length 2字节，前一个节点的长度，第一个节点该值为`0`
##### 7.4.2 encoding 编码规则，字符串类型的长度可选值有 1、2、5，整数固定为 1
###### 7.4.2.1 字符串 00,01,10
###### 7.4.2.1.1 标识符：`|00pppppp|`1字节，高 2 位固定为`00`，剩余 6 位表示 `content`长度，最大值为 63
###### 7.4.2.1.2 标识符：`|01pppppp|qqqqqqqq|`2字节，高 2 位固定为`01`，剩余 14 位表示 `content`长度，最大值为 1 << 14 -1
###### 7.4.2.1.3 标识符：`|01pppppp|qqqqqqqq|rrrrrrrr|uuuuuuuu|vvvvvvvv|`5字节，高 2 位固定为`10`，剩余 38 位表示 `content`长度，最大值为 1 << 38 -1
###### 7.4.2.1.4 示例：插入字符`ab`和`bc`
ab:字符长度为 2 ，使用标识符`00`表示
| previous_entry_length | encoding | entry |
| ----                  | ----    | ----    |
| 00000000              |00000010 => 0x02 | a => 01100001 => 0x61, b => 01100010 => 0x62 |

整个ZipList结构如下
| zlbytes | zltail  | zllen  | entry               |entry |
| ----    | ----    | ----   | ----                |----  |
|00001111 | 00001100|00000001|      ab和0xff        |----  |
|0x0f     | 0x0a    | 0x01   | 0x00 &#124; 0x02 &#124; 0x61 &#124; 0x62 | 0xff |

bc:字符串长度为 2，在放入 ab 后增加entry，entry长度为 4
|zlbytes  | zltail  | zllen  | entry               | entry               |entry |
| ----    | ----    | ----   | ----                | ----                |----  |
|00010011 | 00001110|00000010|                     |                     |结束符 |
|0x13     | 0x0e    | 0x02   | 0x00 &#124; 0x02 &#124; 0x61 &#124; 0x62 | 0x04 &#124; 0x02 &#124; 0x62 &#124; 0x63 | 0xff |


###### 7.4.2.2 整数
###### 7.4.2.2.1 encoding-> 11000000 int16_t(2字节)
###### 7.4.2.2.2 encoding-> 11010000 int32_t(4字节)
###### 7.4.2.2.3 encoding-> 11100000 int64_t(8字节)
###### 7.4.2.2.4 encoding-> 11110000 24位有符号整数(3字节)
###### 7.4.2.2.5 encoding-> 11111110 8位有符号整数(1字节)
###### 7.4.2.2.6 encoding-> 1111xxxx xxxx范围0001~1101,也就是 0～12范围内的数字不使用 content 记录，直接保存在`encoding`中的低4位
#### 7.5 0xff 固定结束标识

### 8. quicklist
#### 8.1 quicklistNode *head
#### 8.2 quicklistNode *tail
#### 8.3 long count 所有 zipList 的 entry 的个数
#### 8.4 long len zipList 的个数
#### 8.5 int fill ziplist中每个 entry 的限制，当为正数时是个数限制，当为负数时是大小限制 -1=>4kb, -2=>8kb, -3=>16kb
#### 8.6 int compress 首尾不压缩节点的数量
#### 8.7 quicklistNode
#### 8.7.1 quicklistNode *prev
#### 8.7.1 quicklistNode *next
#### 8.7.1 unsigned char *zl 当前节点的 ZipList 指针
#### 8.7.1 int sz 当前节点的ZipList大小
#### 8.7.1 int count 当前节点的ZipList的 entry 个数


### 9. SkipList
#### 9.1 zskiplistNode *header 头部节点指针
#### 9.2 zskiplistNode *tail 尾部节点指针
#### 9.3 length 跳表的长度
#### 9.4 level 层级
#### 9.5 zskiplistNode
##### 9.5.1 zskiplistNode *backward 前置节点指针
##### 9.5.2 zskiplistLevel level[]
###### 9.5.2.1 zskiplistNode *forward 后置节点指针
###### 9.5.2.2 long span 后置节点指针的跨度

### 10. RedisObject
#### 10.1 type 数据类型,占4bit，可选值如下
```
OBJ_STRING 0    /* String object. */
OBJ_LIST 1      /* List object. */
OBJ_SET 2       /* Set object. */
OBJ_ZSET 3      /* Sorted set object. */
OBJ_HASH 4      /* Hash object. */
```
#### 10.2 encoding 编码类型，站4bit，不同数据类型不同数据大小下的编码类型均不一致，可选值如下
```
OBJ_ENCODING_RAW 0     /* Raw representation */
OBJ_ENCODING_INT 1     /* Encoded as integer */
OBJ_ENCODING_HT 2      /* Encoded as hash table */
OBJ_ENCODING_ZIPMAP 3  /* Encoded as zipmap */
OBJ_ENCODING_LINKEDLIST 4 /* No longer used: old list encoding. */
OBJ_ENCODING_ZIPLIST 5 /* Encoded as ziplist */
OBJ_ENCODING_INTSET 6  /* Encoded as intset */
OBJ_ENCODING_SKIPLIST 7  /* Encoded as skiplist */
OBJ_ENCODING_EMBSTR 8  /* Embedded sds string encoding */
OBJ_ENCODING_QUICKLIST 9 /* Encoded as linked list of ziplists */
OBJ_ENCODING_STREAM 10 /* Encoded as a radix tree of listpacks */
```
#### 10.3 lru //TODO
### 11. String
#### 11.1 OBJ_ENCODING_RAW
#### 11.2 OBJ_ENCODING_EMBSTR
#### 11.3 OBJ_ENCODING_INT

### 12. List
#### 12.1 OBJ_ENCODING_LINKEDLIST
#### 12.2 OBJ_ENCODING_ZIPLIST
#### 12.3 OBJ_ENCODING_QUICKLIST

### 13. Set
#### 13.1 OBJ_ENCODING_HT
#### 13.1 OBJ_ENCODING_INTSET   添加元素时可从OBJ_ENCODING_INTSET 转换为 OBJ_ENCODING_HT

### 14. ZSet 问题1：使用 ht 和 skiplist 时覆盖插入如何保证插入时的性能  问题2：在使用ziplist时是如何存储的，因为ziplist是存储的一个一个元素，而zset是kv形式  
#### 14.1 OBJ_ENCODING_SKIPLIST 和 OBJ_ENCODING_HT
#### 14.2 OBJ_ENCODING_ZIPLIST

### 15. HASH
#### 15.1 OBJ_ENCODING_HT
#### 15.2 OBJ_ENCODING_ZIPLIST ziplist转换为ht的原理

### 16. CAP 理论
#### 16.1 Consistency(一致性)：用户访问分布式系统中的任意节点，得到的数据必须一致
#### 16.2 Availability(可用性)：用户访问集群中的任意健康节点，必须能得到响应，而不是超时或拒绝
#### 16.3 Partition tolerance(分区容错)：因为网络故障或其他原因导致分布式系统中的部分节点与其它节点失去连接，形成独立分区，当出现分区时，整个系统也要持续对外提供服务
#### 16.4 问题：分布式系统节点通过网络连接，一定会出现分区问题（P），当分区出现时，系统一致性和可用性无法同时满足，就只能选择AP或者CP

### 17. BASE 理论
#### 17.1 Basic Available 基本可用：分布式系统出现故障时，允许损失部分可用性，保证核心可用
#### 17.2 Soft State 软状态：在一定时间内，允许出现中间状态，比如临时的不一致
#### 17.3 Eventually Consistent 最终一致性：虽然无法达到强一致性，但是在软状态结束后，最终达到数据一致

### 18. Seata
#### 18.1 TC(Transaction Coordinator) - 事务协调者：维护全局和分支事务的状态，协调全局事务提交或者回滚
#### 18.2 TM(Transaction Manager) - 事务管理者：定义全局事务的范围、开始全局事务、提交货回滚全局事务
#### 18.3 RM(Resource Manager) - 资源管理器：管理哦分支事务处理的资源，与TC交谈以注册分支事务和报告分支事务的状态，并驱动分支事务提交或回滚
#### 18.4 XA模式：强一致性分阶段事务模式，牺牲了一定的可用性，无业务侵入
##### 18.4.1 优点
* 数据强一致性，适合数据安全的业务
* 实现较简单，因为大部分关系型数据库已经实现了XA事务
##### 18.4.2 缺点
* 多事务等待时会占用数据库的锁，会导致其它请求处于阻塞状态，性能较低
* 必须依赖实现了XA事务的关系型数据库
##### 18.4.3 使用方式
* 配置`seata.data-source-proxy-model=XA`
* 在事务的方法入口处添加`@GlobalTransaction`
#### 18.5 TCC模式：最终一致性的分阶段事务模式，有业务侵入
##### 18.5.1 Try：资源的检测和预留
##### 18.5.2 Confirm：完成资源操作业务；要求Try成功Confirm一定要成功
##### 18.5.3 Cancel：预留资源释放，可以理解为Try的反向操作 
##### 18.5.4 优点
* 一阶段提交事务后会释放数据库锁，性能较好
* 没有引入全局锁，较`AT`性能较高
* 不依赖于关系型数据库，因为是自己实现事务的补偿，所以也可应用在事务型数据库
##### 18.5.5 缺点
* 对业务代码侵入较大，并且实现较复杂
* 存在软状态，事务是最终一致
* 需考虑`confirm`和`cancel`操作，做好幂等处理
##### 18.5.6 空回滚：当某个分支事务`try`阶段时阻塞，导致`TC`认为全局事务应该回滚，继而调用各分支事务执行`cancel`。但是事务并没有执行`try`就执行了`cancel`操作,这时`cancel`不能做回滚，就叫做空回滚
##### 18.5.7 业务悬挂：在当分支事务执行了空回滚后，阻塞的事务不阻塞，开始执行`try`操作，但是永远不会执行`confirm`或者`cancel`操作，因为整个事务已经结束。此时应当阻止执行空回滚后的`try`操作，避免业务悬挂
##### 18.5.8 如何实现空回滚和避免业务悬挂：在数据库表中增加`状态`字段，标识当前`RM`所处于状态

#### 18.6 AT模式：最终一致性的分阶段事务模式，无业务侵入，也是Seata的默认模式
##### 18.6.1 原理
###### 18.6.1.1 一阶段
* 注册分支事务
* 记录数据快照`undo-log`，实际上是记录的是执行SQL前后的数据快照
* 执行业务SQL
* 提交、报告事务状态
###### 18.6.1.2 二阶段
* 分支事务都执行完成后， `TC`根据各分支事务状态判断是否恢复快照
* 最后删除快照 
##### 18.6.2 优点
* 不等待其它事务提交，性能较高 ，由`TC`决定是否根据数据快照恢复数据
* 利用全局锁实现了读写隔离
* 没有代码入侵，框架自动完成回滚和提交
##### 18.6.3 缺点
* 软状态，中间状态时可能可能会被其它线程读取
* 快照的生成、回滚或者删除会耗费一些性能，但是性能还是比`XA`高很多
##### 18.6.4 脏写问题的解决原理
* 在一阶段执行SQL后， `RM`会获取`全局锁`，在该锁释放前表示只能由该`RM`对该行数据有写的权限
* 在二阶段提交或者回滚之后会释放该`全局锁`
##### 18.6.5 使用方式
* 配置`seata.data-source-proxy-model=AT`
* 在事务的方法入口处添加`@GlobalTransaction`
#### 18.7 SAGA模式：长事务模式，有业务侵入
#### 18.7.1 一阶段：直接提交事务
#### 18.7.2 二阶段：成功则什么都不做；失败则通过编写业务代码回滚
#### 18.7.3 优点
* 事务参与者可以基于事件驱动实现异步调用，吞吐量高
* 一阶段直接提交事务，释放锁，性能较高
* 不用编写TCC的三个阶段，实现简单
#### 18.7.4 缺点
* 软状态持续时间不问题，时效性差
* 没有锁和事务隔离，会出现脏写

### 19. RabbitMQ
#### 19.1 消息可靠性
##### 19.1.1 生产者确认机制(Publish Confirm)(确认机制发送消息时，必须设置一个全局的id，用于区分不同消息的确认)
###### 19.1.1.1 publish-confirm，发送者确认
* 消息成功投递到交换机，返回ack
* 消息未成功投递到交换机，返回nack
###### 19.1.1.2 publish-return，发送者回执
* 消息投递到交换机了，但是没有路由到队列。返回ACK，及路由失败原因

#### 19.2 消息持久化
##### 19.2.1 交换机持久化
* 创建交换机时将`durable`参数设置true，即可让交换机持久化
##### 19.2.2 队列持久化
* 创建队列时可使用`QueueBuilder.durable(${queueName}).build()`让队列持久化
##### 19.2.3 消息持久化
* 可以指定`MessageProperties`中的`DeliveryMode`来指定
* ``` 
  MessageBuilder.withBody(message.getBytes(StandardCharsets.UTF_8))
      .setDeliveryMode(MessageDeliveryMode.PRESISTENT) //持久化
      .build();
  ```

#### 19.3 消费者消息确认：消费者消息处理完成后向MQ发送ack回执，MQ收到ack后才会删除该消息
##### 19.3.1 `manual`：手动`ack`，需要再业务代码结束后，调用api发送`ack`
##### 19.3.2 `auto`：自动`ack`，由`spring`检测`listener`代码是否出现异常，没有异常则返回`ack`；抛出异常则返回`nack`
##### 19.3.3 `none`：关闭`ack`，MQ假定消费者获取消息后会处理成功，因此消息投递后立即被删除

#### 19.4 失败重试机制
##### 19.4.1 可以利用`spring`的`retry`机制，在消费者出现异常时利用本地重试，而不是无限制的`requeue`到MQ队列，配置如下 
* spring.rabbitmq.listener.simple.retry.enabled=true //启用消费者失败重试
* spring.rabbitmq.listener.simple.retry.initial-interval=1000 //初始的失败等待时长为1秒
* spring.rabbitmq.listener.simple.retry.multiplier=1 //下次失败的等待时长倍数，下次等待时长 = multiplier * initial-interval
* spring.rabbitmq.listener.simple.retry.max-attempts=1 //最大重试次数
* spring.rabbitmq.listener.simple.retry.stateless=true //true无状态，false有状态。如果业务中包含事务，这里改为false
##### 19.4.2 消费者消费消息失败，并且重试也失败，则需要MessageRecoverer接口来处理，包含三种不同的实现
* RejectAndDontRequeueRecoverer：重试耗尽后，直接reject，丢弃消息。默认就是这种方式
* ImmediateRequeueRecoverer：重试耗尽后，返回nack，消息重新入队
* RepublishRequeueRecoverer：重试耗尽后，将失败消息投递到指定的交换机
#### 19.4 死信交换机
##### 19.4.1 成为死信消息许满足以下情况之一
* 消费者使用`basic.reject`或者`basic.nack`声明消费失败，并且消息的requeue属性设置为`false`
* 消息是一个过期消息，超时也没有被消费
* 要投递的队列消息堆积满了，最早的消息可能成为死信
##### 19.4.2 死信投递：当该队列配置了`dead-letter-exchange`属性指定了一个交换机，那么队列中的死信消息就会投递到这个交换机中，而这个交换机成为死信交换机(Dead Letter Exchange,简称DLX)
##### 19.4.3 如何给队列绑定死信交换机
* 给队列设置dead-letter-exchange属性，指定一个交换机
* 给队列设置dead-letter-routing-key属性，设置死信交换机与死信队列的RoutingKey
##### 19.4.3 TTL(Time-To-Live)，如果队列中的消息TTL结束仍未被消费，则会变成死信，TTL超时分为两种情况
* 消息所在的队列(`x-message-ttl`)设置了存活时间
* 消息本身设置了存活时间

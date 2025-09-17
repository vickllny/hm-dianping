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
#### 6.3 `rehash`下的扩容：1、计算哈希表是否`used/size`>=1 && (`!(BGSAVE || BGREWRITEAOF)` || `used/size >=5`)，如果未 true，则将`rehashidx`的值设置为`0`，标识开始 rehash，计算`used+1`的最小 2 的 n 次方数作为第二个`dictht`的初始数组大小，rehash 不是一次就完成，是每次增删改查时去处理第一个`dictht`中的`rehashidx`元素的节点，直到处理完成每个`dictEntry`，释放第一个`dictht`的内存，再将第二个`dictht`的引用赋值给第一个`dictht`，将`rehashidx`设置为`-1`
#### 6.4 `rehash`下的收缩：1、计算哈希表是否`used*100/size` <= 0.1，如果未 true，则将`rehashidx`的值设置为`0`，标识开始 rehash，计算`used+1`的最小 2 的 n 次方数作为第二个`dictht`的初始数组大小，接下来的步骤同扩容时一致

### 7. ZipList
#### 7.1 tlbytes 4字节，标识当前 zipList 的总长度
#### 7.2 tltail 4字节，表示最后一个`entry` 的偏移量
#### 7.3 tllen 2字节，表示`entry`的个数
#### 7.4 content 存放`ZipListEntry`
##### 7.4.1 previous_entry_length 1 字节或者 5 字节，前一个节点的长度，第一个节点该值为`0`
##### 7.4.2 encoding 编码规则，字符串类型的长度可选值有 1、2、5，整数固定为 1
###### 7.4.2.1 字符串 00,01,10
###### 7.4.2.1.1 标识符：`|00pppppp|`1字节，高 2 位固定为`00`，剩余 6 位表示 `content`长度，最大值为 63
###### 7.4.2.1.2 标识符：`|01pppppp|qqqqqqqq|`2字节，高 2 位固定为`01`，剩余 14 位表示 `content`长度，最大值为 1 << 14 -1
###### 7.4.2.1.3 标识符：`|01pppppp|qqqqqqqq|rrrrrrrr|uuuuuuuu|vvvvvvvv|`5字节，高 2 位固定为`10`，剩余 38 位表示 `content`长度，最大值为 1 << 38 -1
###### 7.4.2.1.4 示例：插入字符`ab`和`bc`
ab:字符长度为 2 ，使用标识符`00`表示
| previous_entry_length | encoding | content |
| ----                  | ----    | ----    |
| 00000000              |00000010 => 0x02 | a => 01100001 => 0x61, b => 01100010 => 0x62 |

tlbytes | tltail  | tllen  | content | 0xff
00001111| 00001100|00000001|
0x0f    | 0x0a    | 0x01   | 0x00 | 0x02 | 0x61 | 0x62 | 0xff

bc:字符串长度为 2，在放入 ab 后增加entry，entry长度为 4
tlbytes | tltail  | tllen  | content | 0xff
00010011| 00001110|00000010|
0x13    | 0x0e    | 0x02   | 0x00 | 0x02 | 0x61 | 0x62 | 0x04 | 0x02 | 0x62 | 0x63 | 0xff


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


### 9. SkipList header tail length level
#### 9.1 zskiplistNode *header
#### 9.2 zskiplistNode *tail
#### 9.3 length
#### 9.4 level
#### 9.5 zskiplistNode
##### 9.5.1 zskiplistNode *backward
##### 9.5.2 zskiplistLevel level[]
###### 9.5.2.1 zskiplistNode *forward
###### 9.5.2.2 long span

### 10. RedisObject
``struct redisObject {
    unsigned type:4;
    unsigned encoding:4;
    unsigned lru:LRU_BITS; /* LRU time (relative to global lru_clock) or * LFU data (least significant 8 bits frequency * and most significant 16 bits access time). */
    unsigned iskvobj : 1;   /* 1 if this struct serves as a kvobj base */
    unsigned expirable : 1; /* 1 if this key has expiration time attached.* If set, then this object is of type kvobj */
    unsigned refcount : OBJ_REFCOUNT_BITS;
    void *ptr;
};``
### 11. String 的三种编码模式 OBJECT_ENCODING_(RAW、EMBSTR、INT)
### 12. List 的三种编码模式 OBJ_ENCODING_LINKEDLIST 、OBJ_ENCODING_ZIPLIST、OBJ_ENCODING_QUICKLIST
### 13. Set OBJ_ENCODING_HT、OBJ_ENCODING_INTSET 添加元素时可从OBJ_ENCODING_INTSET 转换为 OBJ_ENCODING_HT
### 14. ZSet OBJ_ENCODING_SKIPLIST、OBJ_ENCODING_HT、OBJ_ENCODING_ZIPLIST 问题1：使用 ht 和 skiplist 时覆盖插入如何保证插入时的性能  问题2：在使用ziplist时是如何存储的，因为ziplist是存储的一个一个元素，而zset是kv形式  
### 15. HASH OBJ_ENCODING_HT、OBJ_ENCODING_ZIPLIST  ziplist转换为ht的原理
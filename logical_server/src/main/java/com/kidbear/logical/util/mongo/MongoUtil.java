package com.kidbear.logical.util.mongo;

import com.kidbear.logical.util.cache.MC;
import com.mongodb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mongo操作工具类
 */
public class MongoUtil {

    private MongoClient mongo = null;
    private DB db = null;
    private static Logger logger = LoggerFactory.getLogger(MongoUtil.class);
    private static final Map<String, MongoUtil> instances = new ConcurrentHashMap<String, MongoUtil>();
    private static final String CONF_PATH = "/spring-mongodb/mongodb.properties";
    /**
     * The constant DB_ID.
     */
    public static final String DB_ID = "id";// DB中id字段名

    /**
     * 实例化
     *
     * @return MongoDBManager对象
     */
    static {
        getInstance("db");// 初始化默认的MongoDB数据库
    }

    /**
     * Gets instance.
     *
     * @return the instance
     */
    public static MongoUtil getInstance() {
        return getInstance("db");// 配置文件默认数据库前缀为db
    }

    /**
     * Gets instance.
     *
     * @param dbName the db name
     * @return the instance
     */
    public static MongoUtil getInstance(String dbName) {
        MongoUtil mongoMgr = instances.get(dbName);
        if (mongoMgr == null) {
            mongoMgr = buildInstance(dbName);
            if (mongoMgr == null) {
                return null;
            }
            instances.put(dbName, mongoMgr);
        }
        return mongoMgr;
    }

    private static synchronized MongoUtil buildInstance(String dbName) {
        MongoUtil mongoMgr = new MongoUtil();
        try {
            mongoMgr.mongo = new MongoClient(getServerAddress(dbName),
                    getMongoCredential(dbName), getDBOptions(dbName));
            mongoMgr.db = mongoMgr.mongo.getDB(getProperty(CONF_PATH, dbName
                    + ".database"));
            logger.info("connect to MongoDB success!");
            boolean flag = mongoMgr.db.authenticate(
                    getProperty(CONF_PATH, dbName + ".username"),
                    getProperty(CONF_PATH, dbName + ".password").toCharArray());
            if (!flag) {
                logger.error("MongoDB auth failed");
                return null;
            }
        } catch (Exception e) {
            logger.info("Can't connect " + dbName + " MongoDB! {}", e);
            return null;
        }
        return mongoMgr;
    }

    /**
     * 根据properties文件的key获取value
     *
     * @param filePath properties文件路径
     * @param key      属性key
     * @return 属性value
     */
    private static String getProperty(String filePath, String key) {
        Properties props = new Properties();
        try {
            InputStream in = MongoUtil.class.getResourceAsStream(filePath);
            props.load(in);
            String value = props.getProperty(key);
            return value;
        } catch (Exception e) {
            logger.info("load mongo properties exception {}", e);
            System.exit(0);
            return null;
        }
    }

    /**
     * 获取集合（表）
     *
     * @param collection the collection
     * @return the collection
     */
    public DBCollection getCollection(String collection) {
        db.requestStart();
        DBCollection collect = db.getCollection(collection);
        return collect;
    }

    /**
     * 插入
     *
     * @param collection the collection
     * @param o          the o
     */
    public void insert(String collection, DBObject o) {
        getCollection(collection).insert(o);
        db.requestDone();
        // 添加到MC控制
        MC.add(o, o.get(DB_ID).toString());
    }

    /**
     * 批量插入
     *
     * @param collection the collection
     * @param list       the list
     */
    public void insertBatch(String collection, List<DBObject> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        getCollection(collection).insert(list);
        db.requestDone();
        // 批量插入MC
        for (DBObject o : list) {
            MC.add(o, o.get(DB_ID).toString());
        }
    }

    /**
     * 删除
     *
     * @param collection the collection
     * @param q          查询条件
     * @return the list
     */
    public List<DBObject> delete(String collection, DBObject q) {
        getCollection(collection).remove(q);
        List<DBObject> list = find(collection, q);
        db.requestDone();
        // MC中删除
        for (DBObject tmp : list) {
            DBObject dbObject = MC.get(DBObject.class, tmp
                    .get(DB_ID).toString());
            if (null != dbObject) {
                MC.delete(DBObject.class, dbObject.get(DB_ID).toString());
            }
        }
        return list;
    }

    /**
     * 批量删除
     *
     * @param collection the collection
     * @param list       删除条件列表
     */
    public void deleteBatch(String collection, List<DBObject> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            // 批量条件删除
            delete(collection, list.get(i));
        }
        db.requestDone();
    }

    /**
     * 计算集合总条数
     *
     * @param collection the collection
     * @return the count
     */
    public int getCount(String collection) {
        int count = getCollection(collection).find().count();
        db.requestDone();
        return count;
    }

    /**
     * 计算满足条件条数
     *
     * @param collection the collection
     * @param q          查询条件
     * @return the count
     */
    public long getCount(String collection, DBObject q) {
        long count = getCollection(collection).getCount(q);
        db.requestDone();
        return count;
    }

    /**
     * 更新
     *
     * @param collection the collection
     * @param q          查询条件
     * @param setFields  更新对象
     * @return List<DBObject>  更新后的对象列表
     */
    public List<DBObject> update(String collection, DBObject q,
                                 DBObject setFields) {
        getCollection(collection).findAndModify(q,
                new BasicDBObject("$set", setFields));
        List<DBObject> list = find(collection, q);
        db.requestDone();
        // 遍历
        for (DBObject dbObject : list) {
            // MC 中修改
            DBObject tmp = MC.get(DBObject.class, dbObject
                    .get(DB_ID).toString());
            if (null != tmp) {
                MC.update(dbObject, tmp.get(DB_ID).toString());
            }
        }
        return list;
    }

    /**
     * Update one db object.
     *
     * @param collection the collection
     * @param q          the q
     * @param setFields  the set fields
     * @return DBObject db object
     * @throws
     * @Title: updateOne
     * @Description: 更新一条数据
     */
    public DBObject updateOne(String collection, DBObject q, DBObject setFields) {
        DBObject ret = getCollection(collection).findAndModify(q, setFields);
        db.requestDone();
        // MC 中修改
        DBObject tmp = MC.get(DBObject.class, ret.get(DB_ID)
                .toString());
        if (null != tmp) {
            MC.update(ret, tmp.get(DB_ID).toString());
        }
        return ret;
    }

    /**
     * Update one db object.
     *
     * @param collection the collection
     * @param userid     the userid
     * @param setFields  the set fields
     * @return DBObject db object
     * @throws
     * @Title: updateOne
     * @Description: 更新一条数据
     */
    public DBObject updateOne(String collection, long userid, DBObject setFields) {
        BasicDBObject condition = new BasicDBObject(DB_ID, userid);
        DBObject ret = getCollection(collection).findAndModify(condition,
                setFields);
        db.requestDone();
        // MC 中修改
        DBObject tmp = MC.get(DBObject.class, ret.get(DB_ID)
                .toString());
        if (null != tmp) {
            MC.update(ret, tmp.get(DB_ID).toString());
        }
        return ret;
    }

    /**
     * 查找集合所有对象
     *
     * @param collection the collection
     * @return the list
     */
    public List<DBObject> findAll(String collection) {
        List<DBObject> list = getCollection(collection).find().toArray();
        db.requestDone();
        return list;
    }

    /**
     * 查找集合所有对象特定字段
     *
     * @param collection the collection
     * @param fields     the fields
     * @return the list
     */
    public List<DBObject> findAllByFields(String collection, DBObject fields) {
        List<DBObject> list = getCollection(collection).find(null, fields)
                .toArray();
        db.requestDone();
        return list;
    }

    /**
     * 按顺序查找集合所有对象
     *
     * @param collection 数据集
     * @param orderBy    排序
     * @return the list
     */
    public List<DBObject> findAll(String collection, DBObject orderBy) {
        List<DBObject> list = getCollection(collection).find().sort(orderBy)
                .toArray();
        db.requestDone();
        return list;
    }

    /**
     * 查找（返回一个对象）
     *
     * @param collection the collection
     * @param q          查询条件
     * @return the db object
     */
    public DBObject findOne(String collection, DBObject q) {
        DBObject ret = findOne(collection, q, null);
        db.requestDone();
        return ret;
    }

    /**
     * 查找（返回一个对象）
     *
     * @param collection the collection
     * @param q          查询条件
     * @param fields     the fields
     * @return the db object
     */
    public DBObject findOne(String collection, DBObject q, DBObject fields) {
        if (q.containsField(DB_ID)) {// 如果根据id来查询,先从缓存取数据
            DBObject tmp = MC.get(DBObject.class, q.get(DB_ID)
                    .toString());
            if (tmp != null) {// 缓存没有数据，从数据库取
                if (fields != null) {// 留下需要返回的字段
                    for (String key : tmp.keySet()) {
                        if (!fields.containsField(key)) {
                            tmp.removeField(key);
                        }
                    }
                }
                return tmp;
            }
        }
        DBObject ret = fields == null ? getCollection(collection).findOne(q)
                : getCollection(collection).findOne(q, fields);
        db.requestDone();
        return ret;
    }

    /**
     * 查找返回特定字段（返回一个List<DBObject>）
     *
     * @param collection the collection
     * @param q          查询条件
     * @param fileds     返回字段
     * @return the list
     */
    public List<DBObject> findLess(String collection, DBObject q,
                                   DBObject fileds) {
        DBCursor c = getCollection(collection).find(q, fileds);
        if (c != null) {
            List<DBObject> list = c.toArray();
            db.requestDone();
            return list;
        } else {
            db.requestDone();
            return null;
        }
    }

    /**
     * 查找返回特定字段（返回一个List<DBObject>）
     *
     * @param collection the collection
     * @param q          查询条件
     * @param fileds     返回字段
     * @param orderBy    排序
     * @return the list
     */
    public List<DBObject> findLess(String collection, DBObject q,
                                   DBObject fileds, DBObject orderBy) {
        DBCursor c = getCollection(collection).find(q, fileds).sort(orderBy);
        if (c != null) {
            List<DBObject> list = c.toArray();
            db.requestDone();
            return list;
        } else {
            db.requestDone();
            return null;
        }
    }

    /**
     * 分页查找集合对象，返回特定字段
     *
     * @param collection   the collection
     * @param q            查询条件
     * @param fileds       返回字段
     * @param pageNo       the page no
     * @param perPageCount the per page count
     * @return the list
     * @pageNo 第n页
     * @perPageCount 每页记录数
     */
    public List<DBObject> findLess(String collection, DBObject q,
                                   DBObject fileds, int pageNo, int perPageCount) {
        List<DBObject> list = getCollection(collection).find(q, fileds)
                .skip((pageNo - 1) * perPageCount).limit(perPageCount)
                .toArray();
        db.requestDone();
        return list;

    }

    /**
     * 按顺序分页查找集合对象，返回特定字段
     *
     * @param collection   集合
     * @param q            查询条件
     * @param fileds       返回字段
     * @param orderBy      排序
     * @param pageNo       第n页
     * @param perPageCount 每页记录数
     * @return the list
     */
    public List<DBObject> findLess(String collection, DBObject q,
                                   DBObject fileds, DBObject orderBy, int pageNo, int perPageCount) {
        List<DBObject> list = getCollection(collection).find(q, fileds)
                .sort(orderBy).skip((pageNo - 1) * perPageCount)
                .limit(perPageCount).toArray();
        db.requestDone();
        return list;
    }

    /**
     * 查找（返回一个List<DBObject>）
     *
     * @param collection the collection
     * @param q          查询条件
     * @return the list
     */
    public List<DBObject> find(String collection, DBObject q) {
        DBCursor c = getCollection(collection).find(q);
        if (c != null) {
            List<DBObject> list = c.toArray();
            db.requestDone();
            return list;
        } else {
            db.requestDone();
            return null;
        }
    }

    /**
     * 按顺序查找（返回一个List<DBObject>）
     *
     * @param collection the collection
     * @param q          查询条件
     * @param orderBy    排序
     * @return the list
     */
    public List<DBObject> find(String collection, DBObject q, DBObject orderBy) {
        DBCursor c = getCollection(collection).find(q).sort(orderBy);
        if (c != null) {
            List<DBObject> list = c.toArray();
            db.requestDone();
            return list;
        } else {
            db.requestDone();
            return null;
        }
    }

    /**
     * 分页查找集合对象
     *
     * @param collection   the collection
     * @param q            查询条件
     * @param pageNo       the page no
     * @param perPageCount the per page count
     * @return the list
     * @pageNo 第n页
     * @perPageCount 每页记录数
     */
    public List<DBObject> find(String collection, DBObject q, int pageNo,
                               int perPageCount) {
        List<DBObject> list = getCollection(collection).find(q)
                .skip((pageNo - 1) * perPageCount).limit(perPageCount)
                .toArray();
        db.requestDone();
        return list;
    }

    /**
     * 按顺序分页查找集合对象
     *
     * @param collection   集合
     * @param q            查询条件
     * @param orderBy      排序
     * @param pageNo       第n页
     * @param perPageCount 每页记录数
     * @return the list
     */
    public List<DBObject> find(String collection, DBObject q, DBObject orderBy,
                               int pageNo, int perPageCount) {
        List<DBObject> list = getCollection(collection).find(q).sort(orderBy)
                .skip((pageNo - 1) * perPageCount).limit(perPageCount)
                .toArray();
        db.requestDone();
        return list;
    }

    /**
     * distinct操作
     *
     * @param collection 集合
     * @param field      distinct字段名称
     * @return the object [ ]
     */
    public Object[] distinct(String collection, String field) {
        Object[] ret = getCollection(collection).distinct(field).toArray();
        db.requestDone();
        return ret;
    }

    /**
     * distinct操作
     *
     * @param collection 集合
     * @param field      distinct字段名称
     * @param q          查询条件
     * @return the object [ ]
     */
    public Object[] distinct(String collection, String field, DBObject q) {
        Object[] ret = getCollection(collection).distinct(field, q).toArray();
        db.requestDone();
        return ret;
    }

    /**
     * group分组查询操作，返回结果少于10,000keys时可以使用
     *
     * @param collection 集合
     * @param key        分组查询字段
     * @param q          查询条件
     * @param initial    the initial
     * @param reduce     reduce Javascript函数，如：function(obj,            out){out.count++;out.csum=obj.c;}
     * @param finalize   reduce            function返回结果处理Javascript函数，如：function(out){out.avg=out.csum            /out.count;}
     * @return the basic db list
     */
    public BasicDBList group(String collection, DBObject key, DBObject q,
                             DBObject initial, String reduce, String finalize) {
        BasicDBList list = ((BasicDBList) getCollection(collection).group(key,
                q, initial, reduce, finalize));
        db.requestDone();
        return list;
    }

    /**
     * group分组查询操作，返回结果大于10,000keys时可以使用
     *
     * @param collection 集合
     * @param map        映射javascript函数字符串，如：function(){ for(var key in this) {            emit(key,{count:1}) } }
     * @param reduce     reduce Javascript函数字符串，如：function(key,emits){ total=0; for(var            i in emits){ total+=emits[i].count; } return {count:total}; }
     * @param q          分组查询条件
     * @param orderBy    分组查询排序
     * @return the iterable
     */
    public Iterable<DBObject> mapReduce(String collection, String map,
                                        String reduce, DBObject q, DBObject orderBy) {
        // DBCollection coll = db.getCollection(collection);
        // MapReduceCommand cmd = new MapReduceCommand(coll, map, reduce, null,
        // MapReduceCommand.OutputType.INLINE, q);
        // return coll.mapReduce(cmd).results();
        MapReduceOutput out = getCollection(collection).mapReduce(map, reduce,
                null, q);
        Iterable<DBObject> list = out.getOutputCollection().find()
                .sort(orderBy).toArray();
        db.requestDone();
        return list;
    }

    /**
     * group分组分页查询操作，返回结果大于10,000keys时可以使用
     *
     * @param collection   集合
     * @param map          映射javascript函数字符串，如：function(){ for(var key in this) {            emit(key,{count:1}) } }
     * @param reduce       reduce Javascript函数字符串，如：function(key,emits){ total=0; for(var            i in emits){ total+=emits[i].count; } return {count:total}; }
     * @param q            分组查询条件
     * @param orderBy      分组查询排序
     * @param pageNo       第n页
     * @param perPageCount 每页记录数
     * @return the list
     */
    public List<DBObject> mapReduce(String collection, String map,
                                    String reduce, DBObject q, DBObject orderBy, int pageNo,
                                    int perPageCount) {
        MapReduceOutput out = getCollection(collection).mapReduce(map, reduce,
                null, q);
        List<DBObject> list = out.getOutputCollection().find().sort(orderBy)
                .skip((pageNo - 1) * perPageCount).limit(perPageCount)
                .toArray();
        db.requestDone();
        return list;
    }

    /**
     * group分组查询操作，返回结果大于10,000keys时可以使用
     *
     * @param collection           集合
     * @param map                  映射javascript函数字符串，如：function(){ for(var key in this) {            emit(key,{count:1}) } }
     * @param reduce               reduce Javascript函数字符串，如：function(key,emits){ total=0; for(var            i in emits){ total+=emits[i].count; } return {count:total}; }
     * @param outputCollectionName 输出结果表名称
     * @param q                    分组查询条件
     * @param orderBy              分组查询排序
     * @return the list
     */
    public List<DBObject> mapReduce(String collection, String map,
                                    String reduce, String outputCollectionName, DBObject q,
                                    DBObject orderBy) {
        db.requestStart();
        if (!db.collectionExists(outputCollectionName)) {
            getCollection(collection).mapReduce(map, reduce,
                    outputCollectionName, q);
        }
        List<DBObject> list = getCollection(outputCollectionName)
                .find(null, new BasicDBObject("_id", false)).sort(orderBy)
                .toArray();
        db.requestDone();
        return list;
    }

    /**
     * group分组分页查询操作，返回结果大于10,000keys时可以使用
     *
     * @param collection           集合
     * @param map                  映射javascript函数字符串，如：function(){ for(var key in this) {            emit(key,{count:1}) } }
     * @param reduce               reduce Javascript函数字符串，如：function(key,emits){ total=0; for(var            i in emits){ total+=emits[i].count; } return {count:total}; }
     * @param outputCollectionName 输出结果表名称
     * @param q                    分组查询条件
     * @param orderBy              分组查询排序
     * @param pageNo               第n页
     * @param perPageCount         每页记录数
     * @return the list
     */
    public List<DBObject> mapReduce(String collection, String map,
                                    String reduce, String outputCollectionName, DBObject q,
                                    DBObject orderBy, int pageNo, int perPageCount) {
        db.requestStart();
        if (!db.collectionExists(outputCollectionName)) {
            getCollection(collection).mapReduce(map, reduce,
                    outputCollectionName, q);
        }
        List<DBObject> list = getCollection(outputCollectionName)
                .find(null, new BasicDBObject("_id", false)).sort(orderBy)
                .skip((pageNo - 1) * perPageCount).limit(perPageCount)
                .toArray();
        db.requestDone();
        return list;
    }

    /**
     * Gets table id max.
     *
     * @param <T> the type parameter
     * @param t   the t
     * @return table id max
     */
    public <T> Long getTableIDMax(Class<T> t) {
        Long id = 1l;
        DBCursor cursor = getCollection(t.getSimpleName()).find()
                .sort(new BasicDBObject(DB_ID, -1)).limit(1);
        List<DBObject> list = cursor.toArray();
        if (list.size() > 0) {
            DBObject ret = list.get(0);
            id = (Long) ret.get(DB_ID);
        }
        return id;
    }

    /**
     * @param dbName
     * @return List<ServerAddress>
     * @throws UnknownHostException
     * @throws
     * @Title: getServerAddress
     * @Description: 获取数据库服务器列表
     */
    private static List<ServerAddress> getServerAddress(String dbName)
            throws UnknownHostException {
        List<ServerAddress> list = new ArrayList<ServerAddress>();
        String hosts = getProperty(CONF_PATH, dbName + ".host");
        for (String host : hosts.split("&")) {
            String ip = host.split(":")[0];
            String port = host.split(":")[1];
            list.add(new ServerAddress(ip, Integer.parseInt(port)));
        }
        return list;
    }

    /**
     * @param dbName
     * @return List<MongoCredential>
     * @throws
     * @Title: getMongoCredential
     * @Description: 获取数据库安全验证信息
     */
    private static List<MongoCredential> getMongoCredential(String dbName) {
        String username = getProperty(CONF_PATH, dbName + ".username");
        String password = getProperty(CONF_PATH, dbName + ".password");
        String database = getProperty(CONF_PATH, dbName + ".database");
        MongoCredential credentials = MongoCredential.createMongoCRCredential(
                username, database, password.toCharArray());
        List<MongoCredential> credentialsList = new ArrayList<MongoCredential>();
        credentialsList.add(credentials);
        return credentialsList;
    }

    /**
     * @return MongoClientOptions
     * @throws
     * @Title: getDBOptions
     * @Description: 获取数据参数设置
     */
    private static MongoClientOptions getDBOptions(String dbName) {
        MongoClientOptions.Builder build = new MongoClientOptions.Builder();
        build.connectionsPerHost(Integer.parseInt(getProperty(CONF_PATH, dbName
                + ".connectionsPerHost"))); // 与目标数据库能够建立的最大connection数量为50
        build.threadsAllowedToBlockForConnectionMultiplier(Integer
                .parseInt(getProperty(CONF_PATH, dbName
                        + ".threadsAllowedToBlockForConnectionMultiplier"))); // 如果当前所有的connection都在使用中，则每个connection上可以有50个线程排队等待
        build.maxWaitTime(Integer.parseInt(getProperty(CONF_PATH, dbName
                + ".maxWaitTime")));
        build.connectTimeout(Integer.parseInt(getProperty(CONF_PATH, dbName
                + ".connectTimeout")));
        MongoClientOptions myOptions = build.build();
        return myOptions;
    }

    /**
     * 测试类
     *
     * @param args the input arguments
     */
    public static void main(String[] args) {
        try {
            // getInstance().insert(
            // "user",
            // new BasicDBObject().append("name", "admin3")
            // .append("type", "2").append("score", 70)
            // .append("level", 2)
            // .append("inputTime", new Date().getTime()));
            // getInstance().update("user",
            // new BasicDBObject().append("status", 1),
            // new BasicDBObject().append("status", 2));
            // === group start =============
            // StringBuilder sb = new StringBuilder(100);
            // sb.append("function(obj, out){out.count++;out.").append("scoreSum")
            // .append("+=obj.").append("score").append(";out.")
            // .append("levelSum").append("+=obj.").append("level")
            // .append('}');
            // String reduce = sb.toString();
            // BasicDBList list = getInstance().group(
            // "user",
            // new BasicDBObject("type", true),
            // new BasicDBObject(),
            // new BasicDBObject().append("count", 0)
            // .append("scoreSum", 0).append("levelSum", 0)
            // .append("levelAvg", (Double) 0.0), reduce,
            // "function(out){ out.levelAvg = out.levelSum / out.count }");
            // for (Object o : list) {
            // DBObject obj = (DBObject) o;
            // System.out.println(obj);
            // }
            // ======= group end=========
            // === mapreduce start =============
            // Iterable<DBObject> list2 = getInstance()
            // .mapReduce(
            // "user",
            // "function(){emit( {type:this.type}, {score:this.score, level:this.level} );}",
            // "function(key,values){var result={score:0,level:0};var count = 0;values.forEach(function(value){result.score += value.score;result.level += value.level;count++});result.level = result.level / count;return result;}",
            // new BasicDBObject(), new BasicDBObject("score", 1));
            // for (DBObject o : list2) {
            // System.out.println(o);
            // }

            // List<DBObject> list3 = getInstance().mapReduce("user",
            // "function(){emit({type:this.type},{type:this.type,score:this.score,level:this.level});}",
            // "function(key,values){var result={type:key.type,score:0,level:0};var count=0;values.forEach(function(value){result.score+=value.score;result.level+=value.level;count++});result.level=result.level/count;return result;}",
            // "group_temp_user",
            // new BasicDBObject(),
            // new BasicDBObject("score",1));
            // for (DBObject o : list3) {
            // System.out.println(o);
            // }
            // ======= mapreduce end=========
            // System.out.print(getInstance().findAll("user"));
            // System.out.print(getInstance().find(
            // "user",
            // new BasicDBObject("inputTime", new BasicDBObject("$gt",
            // 1348020002890L)),
            // new BasicDBObject().append("_id", "-1"), 1, 2));
            // getInstance().delete("user", new BasicDBObject());
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }
}

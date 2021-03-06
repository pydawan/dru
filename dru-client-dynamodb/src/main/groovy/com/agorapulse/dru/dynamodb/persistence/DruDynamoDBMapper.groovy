package com.agorapulse.dru.dynamodb.persistence

import com.agorapulse.dru.DataSet
import com.agorapulse.dru.persistence.meta.PropertyMetadata
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBDeleteExpression
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBSaveExpression
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBScanExpression
import com.amazonaws.services.dynamodbv2.datamodeling.PaginatedParallelScanList
import com.amazonaws.services.dynamodbv2.datamodeling.PaginatedQueryList
import com.amazonaws.services.dynamodbv2.datamodeling.PaginatedScanList
import com.amazonaws.services.dynamodbv2.datamodeling.QueryResultPage
import com.amazonaws.services.dynamodbv2.datamodeling.ScanResultPage
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.amazonaws.services.dynamodbv2.model.Capacity
import com.amazonaws.services.dynamodbv2.model.Condition
import com.amazonaws.services.dynamodbv2.model.ConsumedCapacity
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.FromString

import static com.agorapulse.dru.dynamodb.persistence.DynamoDB.getOriginalId

/**
 * DynamoDBMapper mock implemented on the best afford.
 *
 * Please, take a note that query only supports automatic filter by hashes and any other filtering must be done
 * manually adding filter #onQuery(Closure).
 * Scanning returns all items but can be customized by filter added with #onScan(Closure) method.
 *
 * Limit for queries and scans is respected.
 */
class DruDynamoDBMapper extends DynamoDBMapper {

    static String getTableNameUsingConfig(Class<?> clazz, DynamoDBMapperConfig config) {
        if (config.tableNameResolver == null) {
            return DynamoDBMapperConfig.DefaultTableNameResolver.INSTANCE.getTableName(clazz, config)
        }
        return config.tableNameResolver.getTableName(clazz, config)
    }

    private final DataSet dataSet
    private final Map<Class, List<Closure<Boolean>>> processQuery = [:].withDefault { [] }
    private final Map<Class, List<Closure<Boolean>>> processScan = [:].withDefault { [] }

    DruDynamoDBMapper(DataSet dataSet) {
        super(null)
        this.dataSet = dataSet
    }

    @SuppressWarnings('DuplicateStringLiteral')
    <T> DruDynamoDBMapper onScan(Class<T> type, @ClosureParams(value = FromString, options = [
            'T,com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBScanExpression,com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig',
            'T,com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBScanExpression',
            'T',
    ]) Closure<Boolean> filter) {
        processScan[type] << filter
        return this
    }

    @SuppressWarnings('DuplicateStringLiteral')
    <T> DruDynamoDBMapper onQuery(Class<T> type, @ClosureParams(value = FromString, options = [
            'T,com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression<T>,com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapperConfig',
            'T,com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression<T>',
            'T',
    ]) Closure<Boolean> filter) {
        processQuery[type] << filter
        return this
    }

    @Override
    <T extends Object> T load(Class<T> clazz, Object hashKey, Object rangeKey, DynamoDBMapperConfig config) {
        T found = dataSet.findByTypeAndOriginalId(clazz, getOriginalId(hashKey, rangeKey))
        if (found) {
            return found
        }
        return dataSet.findByType(clazz).find {
            Object hash = DynamoDB.INSTANCE.getHash(it)
            Object range = DynamoDB.INSTANCE.getRange(it)
            return hash == hashKey && range == rangeKey
        } as T
    }

    @Override
    <T> T load(T keyObject, DynamoDBMapperConfig config) {
        T found = dataSet.findByTypeAndOriginalId(keyObject.getClass(), DynamoDB.INSTANCE.getId(keyObject)) as T
        if (found) {
            return found
        }
        return dataSet.findByType(keyObject.getClass()).find {
            Object hash = DynamoDB.INSTANCE.getHash(it)
            Object range = DynamoDB.INSTANCE.getRange(it)
            return hash == DynamoDB.INSTANCE.getHash(keyObject) && range == DynamoDB.INSTANCE.getRange(keyObject)
        } as T
    }

    @Override
    <T> void save(T object, DynamoDBSaveExpression saveExpression, DynamoDBMapperConfig config) {
        if (object != null) {
            dataSet.add(object)
        }
    }

    @Override
    <T> void delete(T object, DynamoDBDeleteExpression deleteExpression, DynamoDBMapperConfig config) {
        if (object != null) {
            dataSet.remove(object)
        }
    }

    @Override
    List<DynamoDBMapper.FailedBatch> batchWrite(Iterable<? extends Object> objectsToWrite,
                                                Iterable<? extends Object> objectsToDelete,
                                                DynamoDBMapperConfig config) {
        for (Object toWrite in objectsToWrite) {
            save(toWrite)
        }
        for (Object toDelete in objectsToDelete) {
            delete(toDelete)
        }
        return Collections.emptyList()
    }

    @Override
    Map<String, List<Object>> batchLoad(Iterable<? extends Object> itemsToGet, DynamoDBMapperConfig config) {
        Map<String, List<Object>> result = [:].withDefault { [] }
        for (Object toGet in itemsToGet) {
            Object found = load(toGet)
            if (found) {
                result[getTableName(found.class, config)] << found
            }
        }
        return result
    }

    @Override
    <T> PaginatedScanList<T> scan(Class<T> clazz,
                                  DynamoDBScanExpression scanExpression,
                                  DynamoDBMapperConfig mapperConfig) {
        DynamoDBMapperConfig config = mergeConfig(mapperConfig)

        List<T> items = findAllMatching(clazz, scanExpression, config)

        return new SimplePaginatedScanList<T>(this, clazz, items, config)
    }

    @Override
    <T> PaginatedParallelScanList<T> parallelScan(Class<T> clazz,
                                                  DynamoDBScanExpression scanExpression,
                                                  int totalSegments,
                                                  DynamoDBMapperConfig mapperConfig) {
        DynamoDBMapperConfig config = mergeConfig(mapperConfig)

        List<T> items = findAllMatching(clazz, scanExpression, config)

        return new SimplePaginatedParallelScanList<T>(this, clazz, items, config)
    }

    @Override
    <T> ScanResultPage<T> scanPage(Class<T> clazz,
                                   DynamoDBScanExpression scanExpression,
                                   DynamoDBMapperConfig mapperConfig) {
        DynamoDBMapperConfig config = mergeConfig(mapperConfig)

        List<T> items = findAllMatching(clazz, scanExpression, config)

        items = handleStartKey(clazz, items, scanExpression.exclusiveStartKey)

        ScanResultPage<T> result = new ScanResultPage<T>()

        if (scanExpression.limit > 0) {
            items = items.take(scanExpression.limit)

            if (items.size() == scanExpression.limit) {
                result.lastEvaluatedKey = getLastKey(items, clazz)
            }
        }

        result.results = items
        result.count = items.size()
        result.scannedCount = items.size()
        result.consumedCapacity = new ConsumedCapacity()
            .withCapacityUnits(1)
            .withGlobalSecondaryIndexes([:])
            .withLocalSecondaryIndexes([:])
            .withTableName(getTableName(clazz, config))
            .withTable(new Capacity().withCapacityUnits(1))

        return result
    }

    @Override
    int count(Class<?> clazz, DynamoDBScanExpression scanExpression, DynamoDBMapperConfig config) {
        scan(clazz, scanExpression, config).size()
    }

    @Override
    <T> PaginatedQueryList<T> query(Class<T> clazz,
                                    DynamoDBQueryExpression<T> queryExpression,
                                    DynamoDBMapperConfig mapperConfig) {
        DynamoDBMapperConfig config = mergeConfig(mapperConfig)

        List<T> items = findAllMatching(clazz, queryExpression, config)

        return new SimplePaginatedQueryList<T>(this, clazz, items, config)
    }

    @Override
    <T> QueryResultPage<T> queryPage(Class<T> clazz,
                                     DynamoDBQueryExpression<T> queryExpression,
                                     DynamoDBMapperConfig mapperConfig) {
        DynamoDBMapperConfig config = mergeConfig(mapperConfig)

        List<T> items = findAllMatching(clazz, queryExpression, config)

        items = handleStartKey(clazz, items, queryExpression.exclusiveStartKey)

        QueryResultPage<T> result = new QueryResultPage<T>()

        if (queryExpression.limit > 0) {
            items = items.take(queryExpression.limit)

            if (items.size() == queryExpression.limit) {
                result.lastEvaluatedKey = getLastKey(items, clazz)
            }
        }

        result.results = items
        result.count = items.size()
        result.scannedCount = items.size()
        result.consumedCapacity = new ConsumedCapacity()
            .withCapacityUnits(1)
            .withGlobalSecondaryIndexes([:])
            .withLocalSecondaryIndexes([:])
            .withTableName(getTableName(clazz, config))
            .withTable(new Capacity().withCapacityUnits(1))

        return result
    }

    @Override
    <T> int count(Class<T> clazz, DynamoDBQueryExpression<T> queryExpression, DynamoDBMapperConfig config) {
        queryExpression.limit = Integer.MAX_VALUE
        query(clazz, queryExpression, config).size()
    }

    @SuppressWarnings('CatchException')
    private <T> List<T> handleStartKey(Class<T> clazz, List<T> items, Map<String, AttributeValue> exclusiveStartKey) {
        if (!exclusiveStartKey) {
            return items
        }

        T startKeyInstance = clazz.newInstance()

        for (Map.Entry<String, AttributeValue> e in exclusiveStartKey) {
            try {
                startKeyInstance."$e.key" = getTableModel(clazz).field(e.key).unconvert(e.value)
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot set property $e.key of $clazz to $e.value", ex)
            }
        }

        List<T> result = items.dropWhile { DynamoDB.INSTANCE.getId(it) != DynamoDB.INSTANCE.getId(startKeyInstance) }
        if (result.size() > 1) {
            return result.tail()
        }

        return  Collections.emptyList()
    }

    private <T> Map<String, AttributeValue> getLastKey(List<T> items, Class<T> clazz) {
        Object last = items.last()
        Object hash = DynamoDB.INSTANCE.getHash(last)
        Object range = DynamoDB.INSTANCE.getRange(last)

        Map<String, AttributeValue> lastId = getTableModel(clazz).convertKey(hash, range)
        lastId
    }

    @SuppressWarnings([
        'UnnecessaryGetter',
        'Instanceof',
    ])
    private <T> List<T> findAllMatching(Class<T> type, DynamoDBQueryExpression<T> expression, DynamoDBMapperConfig config) {
        List<T> ret = new ArrayList<>(dataSet.findAllByType(type))

        if (expression.hashKeyValues) {
            PropertyMetadata property = DynamoDB.INSTANCE.getDynamoDBClassMetadata(type).hash
            ret = ret.findAll {
                it."$property.name" == expression.hashKeyValues."$property.name"
            }
        }

        if (expression.rangeKeyConditions) {
            for (Map.Entry<String, Condition> e in expression.rangeKeyConditions.entrySet()) {
                ret = ret.findAll {
                    Object value = it."$e.key"
                    if (value instanceof Date) {
                        value = getTableModel(type).field(e.key).convert(value).getS()
                    }
                    DynamoDBConditions.conditionToClosure(e.value)(value)
                }
            }
        }

        List<Closure<Boolean>> filters = processQuery[type]
        if (filters) {
            ret = ret.findAll { T item -> filters.every { executeHelper(it, item, expression, config) } }
        }

        return ret
    }

    private <T> List<T> findAllMatching(Class<T> type, DynamoDBScanExpression expression, DynamoDBMapperConfig config) {
        List<T> ret = new ArrayList<>(dataSet.findAllByType(type))

        if (expression.scanFilter) {
            for (Map.Entry<String, Condition> e in expression.scanFilter.entrySet()) {
                ret = ret.findAll { DynamoDBConditions.conditionToClosure(e.value)(it."$e.key") }
            }
        }

        List<Closure<Boolean>> filters = processScan[type]
        if (filters) {
            ret = ret.findAll { T item -> filters.every { executeHelper(it, item, expression, config) } }
        }

        return ret
    }

    private static boolean executeHelper(Closure<Boolean> closure, Object value, Object expression, DynamoDBMapperConfig config) {
        switch (closure.maximumNumberOfParameters) {
            case 0: return closure.call()
            case 1: return closure.call(value)
            case 2: return closure.call(value, expression)
            default: return closure.call(value, expression, config)
        }
    }

}

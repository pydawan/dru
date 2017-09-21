package avl

import com.agorapulse.dru.Dru
import com.agorapulse.dru.dynamodb.persistence.DruDynamoDBMapper
import com.agorapulse.dru.dynamodb.persistence.DynamoDB
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBScanExpression
import com.amazonaws.services.dynamodbv2.datamodeling.PaginatedParallelScanList
import com.amazonaws.services.dynamodbv2.datamodeling.PaginatedQueryList
import com.amazonaws.services.dynamodbv2.datamodeling.PaginatedScanList
import com.amazonaws.services.dynamodbv2.datamodeling.QueryResultPage
import com.amazonaws.services.dynamodbv2.datamodeling.ScanResultPage
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator
import com.amazonaws.services.dynamodbv2.model.Condition
import grails.testing.gorm.DataTest
import org.junit.Rule
import spock.lang.Specification

class AvlDataSetsSpec extends Specification implements DataTest {

    @Rule Dru dru = Dru.plan {
        include AvlDataSets.missions

        whenLoaded {
            println it.report
        }
    }

    void setup() {
        dru.load()
    }

    void ' warmup'() { expect: true }

    void 'entities can be access from the data set'() {
        expect:
            dru.findAllByType(Mission).size() == 2
            dru.findByTypeAndOriginalId(Mission, 7)
            dru.findAllByType(Agent).size() == 3
            dru.findAllByType(Assignment).size() == 4
            dru.findAllByType(Villain).size() == 2
            dru.findAllByType(Item).size() == 2
    }

    void 'GORM entities are persisted'() {
        expect:
            Mission.list().size() == 2
            Agent.list().size() == 3
            Assignment.list().size() == 4
            Villain.list().size() == 2
    }

    void 'DynamoDB mapper can access loaded entities'() {
        when:
            DruDynamoDBMapper mapper = DynamoDB.createMapper(dru)
        then:
            mapper.count(Item.class, new DynamoDBQueryExpression<Item>()) == 2
            mapper.count(MissionLogEntry.class, new DynamoDBScanExpression()) == 7
            mapper.load(new Item(name: "Dupont Diamond"))
            mapper.load(Item.class, "Dupont Diamond")
        when:
            Map<String, List<Object>> batchFetch = mapper.batchLoad([new Item(name: "Dupont Diamond")])
        then:
            batchFetch.size() == 1
            batchFetch['Item']
            batchFetch['Item'].size() == 1
            batchFetch['Item'][0].name == "Dupont Diamond"
    }

    void 'DynamoDB mapper can query the loaded entities'() {
        when:
            DruDynamoDBMapper mapper = DynamoDB.createMapper(dru)
            MissionLogEntry hashKey = new MissionLogEntry(missionId: dru.findByTypeAndOriginalId(Mission, 7).id)
            DynamoDBQueryExpression hashQuery = new DynamoDBQueryExpression<MissionLogEntry>().withHashKeyValues(hashKey)
            QueryResultPage<MissionLogEntry> hashPage = mapper.queryPage(MissionLogEntry, hashQuery)
        then:
            hashPage.count == 7
            Condition rangeCondition = new Condition()
                .withComparisonOperator(ComparisonOperator.BETWEEN)
                .withAttributeValueList(
                new AttributeValue().withS('2013-07-05T00:00:00Z'),
                new AttributeValue().withS('2013-07-10T00:00:00Z')
            )
        when:
            DynamoDBQueryExpression rangeQuery = new DynamoDBQueryExpression<MissionLogEntry>()
                .withHashKeyValues(hashKey)
                .withRangeKeyCondition('date', rangeCondition)
                .withLimit(2)
            QueryResultPage<MissionLogEntry> rangePage = mapper.queryPage(MissionLogEntry, rangeQuery)
        then:
            rangePage.count == 2
        when:
            QueryResultPage<MissionLogEntry> nextRangePage = mapper.queryPage(MissionLogEntry, rangeQuery.withExclusiveStartKey(rangePage.lastEvaluatedKey))
        then:
            nextRangePage.count == 1
        when:
            PaginatedQueryList<MissionLogEntry> paginatedRangeQuery = mapper.query(MissionLogEntry, rangeQuery)
        then:
            paginatedRangeQuery.size() == 3
    }

    void 'DynamoDB mapper can scan the loaded entities'() {
        when:
            DruDynamoDBMapper mapper = DynamoDB.createMapper(dru)
            Condition scanFilterCondition = new Condition()
                .withComparisonOperator(ComparisonOperator.EQ)
                .withAttributeValueList(
                new AttributeValue().withN(Agent.findByName('Felonius Gru').id.toString())
            )
            DynamoDBScanExpression hashScanExpression = new DynamoDBScanExpression().withScanFilter(agentId: scanFilterCondition).withLimit(2)
            ScanResultPage<MissionLogEntry> scanPage = mapper.scanPage(MissionLogEntry, hashScanExpression)
        then:
            scanPage.count == 2
        when:
            ScanResultPage<MissionLogEntry> nextScanPage = mapper.scanPage(MissionLogEntry, hashScanExpression.withExclusiveStartKey(scanPage.lastEvaluatedKey))
        then:
            nextScanPage.count == 1
        when:
            PaginatedScanList<MissionLogEntry> scan = mapper.scan(MissionLogEntry, hashScanExpression)
        then:
            scan.size() == 3
        when:
            PaginatedParallelScanList<MissionLogEntry> scanParallelPage = mapper.parallelScan(MissionLogEntry, hashScanExpression, 5)
        then:
            scanParallelPage.size() == 3
    }

    void 'DynamoDB mapper updates data set'() {
        when:
            DruDynamoDBMapper mapper = DynamoDB.createMapper(dru)
            Item moon = new Item(name: 'Moon')
            mapper.save(moon)
        then:
            mapper.count(Item, new DynamoDBQueryExpression<Item>()) == 3
            dru.findAllByType(Item).size() == 3
        when:
            mapper.batchWrite([new Item(name: 'Lollipop'), new Item(name: 'Statue of Liberty (from Las Vegas)')], [new Item(name: 'Moon')])
        then:
            mapper.count(Item, new DynamoDBQueryExpression<Item>()) == 4
    }

    void 'Complex DynamoDB scan can be done using onScan'() {
        when:
            DruDynamoDBMapper mapper = DynamoDB.createMapper(dru)
            Long gruId = Agent.findByName('Felonius Gru').id
            mapper.onScan(MissionLogEntry) {
                it.agentId == gruId
            }
            PaginatedScanList<MissionLogEntry> scan = mapper.scan(MissionLogEntry, new DynamoDBScanExpression().withIndexName('agentId'))
        then:
            scan.size() == 3
    }

    void 'Complex DynamoDB query can be done using onScan'() {
        when:
            DruDynamoDBMapper mapper = DynamoDB.createMapper(dru)
            Long gruId = Agent.findByName('Felonius Gru').id
            mapper.onQuery(MissionLogEntry) { MissionLogEntry it, DynamoDBQueryExpression expression ->
                it[expression.indexName] == gruId
            }
            PaginatedQueryList<MissionLogEntry> query = mapper.query(MissionLogEntry, new DynamoDBQueryExpression<MissionLogEntry>().withIndexName('agentId'))
        then:
            query.size() == 3
    }

}

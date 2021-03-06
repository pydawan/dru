package com.agorapulse.dru.parser

import com.agorapulse.dru.PojoTester
import com.agorapulse.dru.reflect.ReflectionParser
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Tests for abstract parser
 */
@Unroll
class AbstractParserSpec extends Specification {

    private static final Map MAP_CONTENT = [
        map: [
            other: [foo: 'bar']
        ],
        list: [
            [item: 'one'],
            [item: 'two'],
            [item: 'three'],
        ],
        nothing: null,
        greeting: 'hello',
    ]

    private static final List LIST = [
        [item: 1],
        [item: 2],
    ]

    void 'null value is converted to #expected for type #type'() {
        when:
            String path = 'path'
            Parser parser = new ReflectionParser()
        then:
            parser.convertValue(path, null, type) == expected
        where:
            type        | expected
            boolean     | false
            int         | 0
            long        | 0
            char        | 0
            byte        | 0
            String      | null
    }

    void '#value is converted to #expected for type #type'() {
        when:
            String path = 'path'
            Parser parser = new ReflectionParser()
        then:
            parser.convertValue(path, value, type) == expected
        where:
            value       | type          | expected
            '10'        | Integer       | 10
            'any'       | Boolean       | true
            ''          | Boolean       | false
    }

    void 'exception is thrown if the conversion cannot be done'() {
        when:
            String path = 'path'
            Parser parser = new ReflectionParser()
            parser.convertValue(path, 'xyz', PojoTester)
        then:
            IllegalArgumentException e = thrown(IllegalArgumentException)
            e.message == 'Failed to convert \'xyz\' to \'class com.agorapulse.dru.PojoTester\' at \'path\''

    }

    void 'find by path'() {
        when:
            Parser parser = new ReflectionParser()
        then:
            parser.findAllMatching(MAP_CONTENT, '') == [MAP_CONTENT]
            parser.findAllMatching(MAP_CONTENT, 'nothing').size() == 0
            parser.findAllMatching(MAP_CONTENT, 'greeting') == [[value: 'hello']]
            parser.findAllMatching(MAP_CONTENT, 'greeting.bytes').size() == 0
            parser.findAllMatching(MAP_CONTENT, 'map.other') == [[foo: 'bar']]
            parser.findAllMatching(MAP_CONTENT, 'map.other.foo') == [[value: 'bar']]
            parser.findAllMatching(MAP_CONTENT, 'list') == [
                [item: 'one'],
                [item: 'two'],
                [item: 'three'],
            ]
            parser.findAllMatching(MAP_CONTENT, 'list.item') == [
                [value: 'one'],
                [value: 'two'],
                [value: 'three'],
            ]
            parser.findAllMatching(LIST, '') == LIST
            parser.findAllMatching(LIST, 'item') == [[value: 1], [value: 2]]
            parser.findAllMatching(null, '').size() == 0
            parser.findAllMatching('foo', '') == [[value: 'foo']]
            parser.findAllMatching('foo', 'bytes').size() == 0
    }

}

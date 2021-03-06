package avl

import com.agorapulse.dru.Dru
import org.junit.Rule
import spock.lang.Specification

/**
 * Testing value override.
 */
class AliasSpec extends Specification {


    // tag::plan[]
    @Rule Dru dru = Dru.plan {
        from ('item.json') {
            map {
                to (Item) {
                    map('desc') {
                        to (description: String)
                    }
                }
            }
        }
    }

    void 'entities can be access from the data set'() {
        when:
            Item item = dru.findByTypeAndOriginalId(Item, ID)
        then:
            item
            item.name == 'PX-41'
            item.description == "The PX-41 is a very dangerous mutator engineered in the top secret PX-Labs, located in the Arctic Circle. It is capable of turning any living things in the world into a purple, furry, indestructible, mindless, killing machine that is so dangerous that it can destroy anything in its path."
            item.tags.contains('superpowers')
    }
    // end::plan[]

    private static final String ID = '050e4fcf-158d-4f44-9b8b-a6ba6809982e:PX-41'
}

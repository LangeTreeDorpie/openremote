package org.openremote.test.assets

import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.*
import org.openremote.manager.datapoint.AssetDatapointService
import org.openremote.manager.rules.RulesService
import org.openremote.manager.setup.SetupService
import org.openremote.manager.setup.builtin.KeycloakTestSetup
import org.openremote.model.asset.Asset
import org.openremote.model.asset.agent.AgentLink
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.*
import org.openremote.test.ManagerContainerTrait
import org.openremote.test.protocol.MockAgent
import org.openremote.test.protocol.MockProtocol
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.persistence.EntityManager

import static org.openremote.model.value.ValueType.*
import static org.openremote.model.value.MetaItemType.*

class AssetProcessingTest extends Specification implements ManagerContainerTrait {

    def "Check processing of asset updates through the processing chain"() {

        given: "expected conditions"
        def conditions = new PollingConditions(timeout: 10, delay: 0.2)

        and: "mock attribute state consumers"
        List<Attribute> updatesPassedStartOfProcessingChain = []
        List<Attribute> updatesPassedAgentService = []
        List<Attribute> updatesPassedRulesService = []
        List<Attribute> updatesPassedDatapointService = []
        List<Attribute> updatesPassedAttributeLinkingService = []

        AssetUpdateProcessor firstProcessor = new AssetUpdateProcessor() {
            @Override
            boolean processAssetUpdate(EntityManager em, Asset asset, Attribute attribute, AttributeEvent.Source source) throws AssetProcessingException {
                updatesPassedStartOfProcessingChain.add(attribute)
                false
            }
        }

        AssetUpdateProcessor afterAgentServiceProcessor = new AssetUpdateProcessor() {
            @Override
            boolean processAssetUpdate(EntityManager em, Asset asset, Attribute attribute, AttributeEvent.Source source) throws AssetProcessingException {
                updatesPassedAgentService.add(attribute)
                false
            }
        }

        AssetUpdateProcessor afterRulesServiceProcessor = new AssetUpdateProcessor() {
            @Override
            boolean processAssetUpdate(EntityManager em, Asset asset, Attribute attribute, AttributeEvent.Source source) throws AssetProcessingException {
                updatesPassedRulesService.add(attribute)
                false
            }
        }

        AssetUpdateProcessor afterDatapointServiceProcessor = new AssetUpdateProcessor() {
            @Override
            boolean processAssetUpdate(EntityManager em, Asset asset, Attribute attribute, AttributeEvent.Source source) throws AssetProcessingException {
                updatesPassedDatapointService.add(attribute)
                false
            }
        }

        AssetUpdateProcessor afterAttributeLinkingServiceProcessor = new AssetUpdateProcessor() {
            @Override
            boolean processAssetUpdate(EntityManager em, Asset asset, Attribute attribute, AttributeEvent.Source source) throws AssetProcessingException {
                updatesPassedAttributeLinkingService.add(attribute)
                false
            }
        }

        when: "the container is started"
        def container = startContainer(defaultConfig(), defaultServices())
        def agentService = container.getService(AgentService.class)
        def assetStorageService = container.getService(AssetStorageService.class)
        def assetProcessingService = container.getService(AssetProcessingService.class)
        def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)

        then: "the container should be running and initialised"
        conditions.eventually {
            assert noEventProcessedIn(assetProcessingService, 500)
        }

        then: "register mock asset processors"
        assetProcessingService.processors.add(0, firstProcessor)
        assetProcessingService.processors.add(assetProcessingService.processors.findIndexOf {it instanceof AgentService}+1, afterAgentServiceProcessor)
        assetProcessingService.processors.add(assetProcessingService.processors.findIndexOf {it instanceof RulesService}+1, afterRulesServiceProcessor)
        assetProcessingService.processors.add(assetProcessingService.processors.findIndexOf {it instanceof AssetDatapointService}+1, afterDatapointServiceProcessor)
        assetProcessingService.processors.add(assetProcessingService.processors.findIndexOf {it instanceof AttributeLinkingService}+1, afterAttributeLinkingServiceProcessor)

        when: "a mock agent that uses the mock protocol is created"
        def mockAgent = new MockAgent("Mock agent")
            .setRealm(keycloakTestSetup.masterTenant.realm)
            .setRequired(true)
        mockAgent = assetStorageService.merge(mockAgent)

        and: "a mock thing asset is created with a valid agent linked attribute, an invalid protocol attribute and a plain attribute"
        def mockThing = new ThingAsset("Mock Thing Asset")
            .setParent(mockAgent)
        mockThing.addOrReplaceAttributes(
                new Attribute<>("light1Toggle", BOOLEAN, true)
                    .addOrReplaceMeta(
                        new MetaItem<>(
                            AGENT_LINK,
                            new MockAgent.MockAgentLink(mockAgent.id)
                                .setRequiredValue("true")
                        )
                    ),
                new Attribute<>("light2Toggle", BOOLEAN, true)
                    .addOrReplaceMeta(
                        new MetaItem<>(
                            AGENT_LINK,
                            new AgentLink.Default("INVALID AGENT ID")
                        )
                    ),
                new Attribute<>("plainAttribute", STRING, "demo")
        )
        mockThing = assetStorageService.merge(mockThing)

        then: "the mock thing to be deployed to the protocol instance"
        conditions.eventually {
            assert agentService.getProtocolInstance(mockAgent.id).linkedAttributes.size() == 1
        }

        when: "the mock protocol instance is configured to not update the sensor on write"
        ((MockProtocol)agentService.getProtocolInstance(mockAgent.id)).updateSensor = false

        and: "an attribute event occurs for a valid protocol linked attribute on the test asset"
        def light1toggleOn = new AttributeEvent(
                new AttributeState(new AttributeRef(mockThing.getId(), "light1Toggle"), false)
        )
        assetProcessingService.sendAttributeEvent(light1toggleOn)

        then: "the attribute event should reach the protocol and stop at the agent service, not be in the database"
        conditions.eventually {
            assert updatesPassedStartOfProcessingChain.size() == 1
            assert updatesPassedStartOfProcessingChain[0].name == "light1Toggle"
            assert !updatesPassedStartOfProcessingChain[0].value
            assert ((MockProtocol)agentService.getProtocolInstance(mockAgent.id)).protocolMethodCalls.last() == "WRITE_ATTRIBUTE:" + mockThing.id + ":light1Toggle"
            assert updatesPassedAgentService.size() == 0
            assert updatesPassedRulesService.size() == 0
            assert updatesPassedDatapointService.size() == 0
            assert updatesPassedAttributeLinkingService.size() == 0
            // Light toggle attribute value should still be true in database
            def asset = assetStorageService.find(mockThing.getId(), true)
            assert asset.getAttribute("light1Toggle").flatMap{it.value}.orElse(false)
        }

        when: "the protocol updates the attributes value with the value it just received"
        updatesPassedStartOfProcessingChain.clear()
        updatesPassedRulesService.clear()
        updatesPassedAgentService.clear()
        updatesPassedDatapointService.clear()
        updatesPassedAttributeLinkingService.clear()
        mockProtocol.responseReceived()

        then: "a new attribute event should occur and reach the end of the processing chain, be stored in database"
        conditions.eventually {
            assert updatesPassedStartOfProcessingChain.size() == 1
            assert updatesPassedStartOfProcessingChain[0].nameOrThrow == "light1Toggle"
            assert !updatesPassedStartOfProcessingChain[0].value.get()
            assert updatesPassedAgentService.size() == 1
            assert updatesPassedRulesService.size() == 1
            assert updatesPassedDatapointService.size() == 1
            assert updatesPassedAttributeLinkingService.size() == 1
            // Light toggle should be off in database
            def asset = assetStorageService.find(mockThing.getId(), true)
            assert !asset.getAttribute("light1Toggle").flatMap{it.value}.orElse(false)
        }

        when: "an attribute event occurs for the invalid protocol attribute"
        updatesPassedStartOfProcessingChain.clear()
        updatesPassedRulesService.clear()
        updatesPassedAgentService.clear()
        updatesPassedDatapointService.clear()
        updatesPassedAttributeLinkingService.clear()
        sendToActuatorEvents.clear()
        def light2toggleOn = new AttributeEvent(
                new AttributeState(new AttributeRef(mockThing.getId(), "light2Toggle"), true)
        )
        assetProcessingService.sendAttributeEvent(light2toggleOn)

        then: "the attribute event should pass the start of the processing chain, but not reach the mock protocol or the end of the processing chain and error should be populated"
        conditions.eventually {
            assert updatesPassedStartOfProcessingChain.size() == 1
            assert updatesPassedStartOfProcessingChain[0].nameOrThrow == "light2Toggle"
            assert sendToActuatorEvents.size() == 0
            assert updatesPassedAgentService.size() == 0
            assert updatesPassedRulesService.size() == 0
            assert updatesPassedDatapointService.size() == 0
            assert updatesPassedAttributeLinkingService.size() == 0
        }

        when: "an attribute event occurs for the plain attribute"
        updatesPassedStartOfProcessingChain.clear()
        updatesPassedRulesService.clear()
        updatesPassedAgentService.clear()
        updatesPassedDatapointService.clear()
        updatesPassedAttributeLinkingService.clear()
        sendToActuatorEvents.clear()
        def plainAttributeTest = new AttributeEvent(
                new AttributeState(new AttributeRef(mockThing.getId(), "plainAttribute"), "test")
        )
        assetProcessingService.sendAttributeEvent(plainAttributeTest)

        then: "the attribute event should pass the start of the processing chain and reach the end of the processing chain"
        conditions.eventually {
            assert updatesPassedStartOfProcessingChain.size() == 1
            assert updatesPassedAgentService.size() == 1
            assert updatesPassedRulesService.size() == 1
            assert updatesPassedDatapointService.size() == 1
            assert updatesPassedAttributeLinkingService.size() == 1
            assert updatesPassedAttributeLinkingService[0].nameOrThrow == "plainAttribute"
            assert updatesPassedAttributeLinkingService[0].value.orElse(null) == "test"
            assert sendToActuatorEvents.size() == 0
        }

    }
}

package org.openremote.test.protocol.velbus

import org.apache.commons.io.IOUtils
import org.openremote.agent.protocol.velbus.VelbusAgent
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.model.asset.agent.AgentLink
import org.openremote.model.asset.agent.AgentResource
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.MetaItem
import org.openremote.model.file.FileInfo
import org.openremote.model.util.TextUtil
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import static org.openremote.container.util.MapAccess.getString
import static org.openremote.manager.security.ManagerIdentityProvider.SETUP_ADMIN_PASSWORD
import static org.openremote.manager.security.ManagerIdentityProvider.SETUP_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.*
import static org.openremote.model.value.MetaItemType.AGENT_LINK
import static org.openremote.model.value.ValueType.STRING

class VelbusProtocolTest extends Specification implements ManagerContainerTrait {

    def setupSpec() {
        MockVelbusProtocol.messageProcessor.mockPackets = [
            // Module Type request for address 48 - return VMBGPOD Packets
            "0F FB 30 40 86 04 00 00 00 00 00 00 00 00": [
                "0F FB 30 07 FF 28 00 02 01 16 12 6D 04 00",
                "0F FB 30 08 B0 28 00 02 31 32 FF 40 42 04"
                //"0F FB 30 08 B0 28 00 02 31 32 33 40 0E 04"
            ],

            // Module Status request for address 48
            "0F FB 30 02 FA 00 CA 04 00 00 00 00 00 00": [
                "0F FB 30 07 ED 00 FF FF 00 00 8D 47 04 00",
                "0F FB 31 07 ED 00 FF FF 00 10 8D 36 04 00",
                "0F FB 32 07 ED 00 FF FF 00 00 8D 45 04 00",
            ],

            // Thermostat Status request for address 48
            "0F FB 30 02 E7 00 DD 04 00 00 00 00 00 00": [
                "0F FB 30 08 EA 00 00 10 38 18 00 00 74 04",
                "0F FB 30 08 E8 18 32 2C 20 18 06 01 21 04",
                "0F FB 30 08 E9 2A 2E 34 48 00 3C 05 C0 04"
            ]
        ]
    }

    def "Check VELBUS agent and device asset deployment"() {

        given: "expected conditions"
        def conditions = new PollingConditions(timeout: 20, delay: 0.2)

        when: "the container starts"
        def container = startContainer(defaultConfig(), defaultServices())
        def assetStorageService = container.getService(AssetStorageService.class)
        def agentService = container.getService(AgentService.class)

        and: "a mock VELBUS agent is created"
        def agent = new MockVelbusAgent("VELBUS")
        agent.setRealm(MASTER_REALM)
        agent = assetStorageService.merge(agent)

        and: "a device asset is created"
        def device = new ThingAsset("VELBUS Demo VMBGPOD")
            .setParent(agent)
            .addOrReplaceAttributes(
                new Attribute<>("ch1State", STRING)
                    .addOrReplaceMeta(
                        new MetaItem<>(
                                AGENT_LINK,
                                new VelbusAgent.VelbusAgentLink(agent.id, 48, "CH1")
                        )
                    )
            )

        and: "the device asset is added to the asset service"
        device = assetStorageService.merge(device)
        def deviceId = device.getId()

        then: "a client should be created and the device asset attribute values should match the values returned by the actual device"
        conditions.eventually {
            assert agentService.getProtocolInstance(agent.id) != null
            assert ((MockVelbusProtocol)agentService.getProtocolInstance(agent.id)).network != null
            def asset = assetStorageService.find(deviceId, true)
            assert asset.getAttribute("ch1State").flatMap { it.getValue() }.orElse(null) == "RELEASED"
        }

        cleanup: "remove agent"
        if (agent != null) {
            assetStorageService.delete([agent.id])
        }
    }

    def "Check linked attribute import"() {

        given: "the server container is started"
        def conditions = new PollingConditions(timeout: 10, delay: 0.2)
        def container = startContainer(defaultConfig(), defaultServices())
        def assetStorageService = container.getService(AssetStorageService.class)
        def agentService = container.getService(AgentService.class)
        def assetProcessingService = container.getService(AssetProcessingService.class)

        and: "a VELBUS project file"
        def velbusProjectFileResource = getClass().getResourceAsStream(
            "/org/openremote/test/protocol/velbus/VelbusProject.vlp"
        )
        def velbusProjectFile = IOUtils.toString(velbusProjectFileResource, "UTF-8")

        and: "a VELBUS agent is created"
        def agent = new MockVelbusAgent("VELBUS")
            .setRealm(MASTER_REALM)
        agent = assetStorageService.merge(agent)

        and: "an authenticated admin user"
        def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), SETUP_ADMIN_PASSWORD, SETUP_ADMIN_PASSWORD_DEFAULT)
        ).token

        and: "the agent resource"
        def agentResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(AgentResource.class)

        expect: "the system should settle down"
        conditions.eventually {
            assert agentService.getAgent(agent.id) != null
            assert noEventProcessedIn(assetProcessingService, 300)
        }

        when: "discovery is requested with a VELBUS project file"
        def fileInfo = new FileInfo("VelbusProject.vlp", velbusProjectFile, false)
        def assets = agentResource.doProtocolAssetImport(null, agent.getId(), MASTER_REALM, fileInfo)

        then: "the correct number of assets should be returned and all should have IDs"
        assert assets != null
        assert assets.length == 13
        assert assets.each {
            !TextUtil.isNullOrEmpty(it.asset.id) &&
                !TextUtil.isNullOrEmpty(it.asset.getName()) &&
                !it.asset.getAttributes().isEmpty() &&
                it.asset.getAttributesStream().allMatch({attr ->
                    AgentLink.getAgentLink(attr)
                        .map({agentLink -> agentLink.assetId == agent.id && agentLink.attributeName == "protocolConfig"})
                        .orElse(false)
                })

        }

        and: "a given asset should have the correct attributes (VMBGPOD)"
        def asset = assets.find {it.asset.name == "VMBGPOD"}
        assert asset != null
        assert asset.asset.getAttributes().size() == 303
        def memoTextAttribute = asset.asset.getAttributes().find {VelbusConfiguration.getVelbusDevicePropertyLink(it) == "MEMO_TEXT"}
        assert memoTextAttribute != null
        assert VelbusConfiguration.getVelbusDeviceAddress(memoTextAttribute) == 24

        cleanup: "remove agent"
        if (agent != null) {
            assetStorageService.delete(Collections.singletonList(agent.id))
        }
    }
}

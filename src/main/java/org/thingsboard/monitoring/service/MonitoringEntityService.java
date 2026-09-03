/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.monitoring.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.RegexUtils;
import org.thingsboard.monitoring.client.TbClient;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringConfig;
import org.thingsboard.monitoring.config.integration.IntegrationMonitoringTarget;
import org.thingsboard.monitoring.config.integration.IntegrationType;
import org.thingsboard.monitoring.config.integration.MqttIntegrationMonitoringConfig;
import org.thingsboard.monitoring.config.transport.DeviceConfig;
import org.thingsboard.monitoring.config.transport.TransportMonitoringConfig;
import org.thingsboard.monitoring.config.transport.TransportMonitoringTarget;
import org.thingsboard.monitoring.config.transport.TransportType;
import org.thingsboard.monitoring.util.ResourceUtils;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.DashboardInfo;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceProfileType;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.ShortCustomerInfo;
import org.thingsboard.server.common.data.TbResource;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.cf.CalculatedField;
import org.thingsboard.server.common.data.converter.Converter;
import org.thingsboard.server.common.data.group.EntityGroup;
import org.thingsboard.server.common.data.group.EntityGroupInfo;
import org.thingsboard.server.common.data.id.ConverterId;
import org.thingsboard.server.common.data.id.DashboardId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityGroupId;
import org.thingsboard.server.common.data.integration.Integration;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.cf.CalculatedFieldType;
import org.thingsboard.server.common.data.cf.configuration.Argument;
import org.thingsboard.server.common.data.cf.configuration.ArgumentType;
import org.thingsboard.server.common.data.cf.configuration.ReferencedEntityKey;
import org.thingsboard.server.common.data.cf.configuration.ScriptCalculatedFieldConfiguration;
import org.thingsboard.server.common.data.cf.configuration.TimeSeriesOutput;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MBootstrapClientCredentials;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MDeviceCredentials;
import org.thingsboard.server.common.data.device.credentials.lwm2m.NoSecBootstrapClientCredential;
import org.thingsboard.server.common.data.device.credentials.lwm2m.NoSecClientCredential;
import org.thingsboard.server.common.data.device.data.DefaultDeviceConfiguration;
import org.thingsboard.server.common.data.device.data.DefaultDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.data.DeviceData;
import org.thingsboard.server.common.data.device.data.Lwm2mDeviceTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileConfiguration;
import org.thingsboard.server.common.data.device.profile.DefaultDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.device.profile.DeviceProfileData;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;
import org.thingsboard.server.common.data.rule.RuleChainType;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;

import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.thingsboard.monitoring.service.BaseHealthChecker.TEST_CF_TELEMETRY_KEY;
import static org.thingsboard.monitoring.service.BaseHealthChecker.TEST_TELEMETRY_KEY;

@Service
@Slf4j
@RequiredArgsConstructor
public class MonitoringEntityService {

    private static final String DASHBOARD_TITLE = "[Monitoring] Cloud monitoring";
    private static final String DASHBOARD_RESOURCE_PATH = "dashboard_cloud_monitoring.json";

    private final TbClient tbClient;

    @Value("${monitoring.calculated_fields.enabled:true}")
    private boolean calculatedFieldsMonitoringEnabled;

    DashboardId dashboardId = null;

    public void checkEntities() {
        RuleChain ruleChain = tbClient.getRuleChains(RuleChainType.CORE, new PageLink(10)).getData().stream()
                .filter(RuleChain::isRoot)
                .findFirst().orElseThrow();
        RuleChainId ruleChainId = ruleChain.getId();

        JsonNode ruleChainDescriptor = ResourceUtils.getResource("rule_chain.json");
        List<String> attributeKeys = tbClient.getAttributeKeys(ruleChainId);
        Map<String, String> attributes = tbClient.getAttributeKvEntries(ruleChainId, attributeKeys).stream()
                .collect(Collectors.toMap(KvEntry::getKey, KvEntry::getValueAsString));

        int currentVersion = Integer.parseInt(attributes.getOrDefault("version", "0"));
        int newVersion = ruleChainDescriptor.get("version").asInt();
        if (currentVersion == newVersion) {
            log.debug("Not updating rule chain, version is the same ({})", currentVersion);
        } else {
            log.info("Updating rule chain '{}' from version {} to {}", ruleChain.getName(), currentVersion, newVersion);

            String metadataJson = RegexUtils.replace(ruleChainDescriptor.get("metadata").toString(),
                    "\\$\\{MONITORING:(.+?)}", matchResult -> {
                        String key = matchResult.group(1);
                        String value = attributes.get(key);
                        if (value == null) {
                            throw new IllegalArgumentException("No attribute found for key " + key);
                        }
                        log.info("Using {}: {}", key, value);
                        return value;
                    });
            RuleChainMetaData metaData = JacksonUtil.fromString(metadataJson, RuleChainMetaData.class);
            metaData.setRuleChainId(ruleChainId);
            tbClient.saveRuleChainMetaData(metaData);
            tbClient.saveEntityAttributesV2(ruleChainId, DataConstants.SERVER_SCOPE, JacksonUtil.newObjectNode()
                    .put("version", newVersion));
        }

        Asset asset = getOrCreateMonitoringAsset();
        Dashboard dashboard = getOrCreateMonitoringDashboard();

        makeAssetPublic(asset);
        makeDashboardPublic(dashboard);

        this.dashboardId = Optional.ofNullable(dashboard).map(Dashboard::getId).orElse(null);
    }

    // Probed once and cached, so a single build monitors both CE and PE targets.
    private Boolean pe;

    private boolean isPe() {
        if (pe == null) {
            pe = tbClient.getSystemVersionInfo()
                    .map(info -> info.path("type").asText(""))
                    .filter(type -> !type.isEmpty())
                    .map(type -> {
                        boolean result = "PE".equalsIgnoreCase(type);
                        log.info("Detected ThingsBoard edition from /api/system/info (type {}): {}", type, result ? "PE" : "CE");
                        return result;
                    })
                    .orElseGet(this::probeIsPe);
        }
        return pe;
    }

    // Fallback for the unlikely case /api/system/info doesn't return a "type" field.
    private boolean probeIsPe() {
        boolean result;
        try {
            tbClient.getEntityGroupsByType(EntityType.CUSTOMER);
            result = true;
        } catch (Exception e) {
            result = false;
        }
        log.info("Detected ThingsBoard edition by probing a PE-only endpoint: {}", result ? "PE" : "CE");
        return result;
    }

    private void makeAssetPublic(Asset asset) {
        if (isPe()) {
            addToPublicGroup(asset.getId(), EntityType.ASSET);
        } else {
            tbClient.assignAssetToPublicCustomer(asset.getId());
        }
    }

    private void makeDashboardPublic(Dashboard dashboard) {
        if (isPe()) {
            addToPublicGroup(dashboard.getId(), EntityType.DASHBOARD);
        } else {
            tbClient.assignDashboardToPublicCustomer(dashboard.getId());
        }
    }

    private void addToPublicGroup(EntityId entityId, EntityType type) {
        EntityGroupInfo group = getOrCreatePublicGroup(type);
        if (!isInGroup(group.getId(), entityId)) {
            tbClient.addEntitiesToEntityGroup(group.getId(), List.of(entityId));
        }
    }

    // Unlike most "get by id" RestClient methods, getGroupEntity() doesn't map "not found" to an
    // empty Optional - it throws a 400 with this specific message instead.
    private boolean isInGroup(EntityGroupId groupId, EntityId entityId) {
        try {
            return tbClient.getGroupEntity(groupId, entityId).isPresent();
        } catch (HttpClientErrorException e) {
            String body = e.getResponseBodyAsString();
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST && body != null && body.contains("not present in entity group")) {
                return false;
            }
            throw e;
        }
    }

    // Keyed by type, not just for the dashboards group - getDashboardPublicLink() calls this again
    // right after makeDashboardPublic() already fetched/created it.
    private final Map<EntityType, EntityGroupInfo> publicGroups = new EnumMap<>(EntityType.class);

    private EntityGroupInfo getOrCreatePublicGroup(EntityType type) {
        EntityGroupInfo cached = publicGroups.get(type);
        if (cached != null) {
            return cached;
        }
        String groupName = "[Monitoring] Public " + type.name().toLowerCase() + "s";
        EntityId ownerId = tbClient.getUser().map(User::getOwnerId).orElseThrow();
        EntityGroupInfo group = tbClient.getEntityGroupInfoByOwnerAndNameAndType(ownerId, type, groupName)
                .orElseGet(() -> {
                    EntityGroup newGroup = new EntityGroup();
                    newGroup.setName(groupName);
                    newGroup.setType(type);
                    newGroup.setOwnerId(ownerId);
                    log.info("Creating new public entity group '{}'", groupName);
                    return tbClient.saveEntityGroup(newGroup);
                });
        if (!group.isPublic()) {
            tbClient.makeEntityGroupPublic(group.getId());
            group = tbClient.getEntityGroupById(group.getId()).orElse(group);
        }
        publicGroups.put(type, group);
        return group;
    }

    public Asset getOrCreateMonitoringAsset() {
        String assetName = "[Monitoring] Latencies";
        return tbClient.findAsset(assetName).orElseGet(() -> {
            Asset asset = new Asset();
            asset.setType("Monitoring");
            asset.setName(assetName);
            asset = tbClient.saveAsset(asset);
            log.info("Created monitoring asset {}", asset.getId());
            return asset;
        });
    }

    public void checkEntities(TransportMonitoringConfig config, TransportMonitoringTarget target) {
        Device device = getOrCreateDevice(config, target);
        DeviceCredentials credentials = tbClient.getDeviceCredentialsByDeviceId(device.getId())
                .orElseThrow(() -> new IllegalArgumentException("No credentials found for device " + device.getId()));

        DeviceConfig deviceConfig = new DeviceConfig();
        deviceConfig.setId(device.getId().toString());
        deviceConfig.setName(device.getName());
        deviceConfig.setCredentials(credentials);
        target.setDevice(deviceConfig);
    }

    private Device getOrCreateDevice(TransportMonitoringConfig config, TransportMonitoringTarget target) {
        TransportType transportType = config.getTransportType();
        String deviceName = String.format("%s %s (%s) - %s", target.getNamePrefix(), transportType.getName(), target.getQueue(), target.getBaseUrl()).trim();
        Device device = tbClient.getTenantDevice(deviceName).orElse(null);
        if (device != null) {
            if (calculatedFieldsMonitoringEnabled) {
                CalculatedField calculatedField = tbClient.getCalculatedFieldsByEntityId(device.getId(), new PageLink(1, 0, TEST_CF_TELEMETRY_KEY))
                        .getData().stream().findFirst().orElse(null);
                if (calculatedField == null) {
                    createCalculatedField(device);
                }
            }
            return device;
        }

        log.info("Creating new device '{}'", deviceName);
        device = new Device();
        device.setName(deviceName);

        DeviceCredentials credentials = new DeviceCredentials();
        credentials.setCredentialsId(RandomStringUtils.secure().nextAlphabetic(20));
        DeviceData deviceData = new DeviceData();
        deviceData.setConfiguration(new DefaultDeviceConfiguration());

        DeviceProfile deviceProfile = getOrCreateDeviceProfile(config, target);
        device.setType(deviceProfile.getName());
        device.setDeviceProfileId(deviceProfile.getId());

        if (transportType != TransportType.LWM2M) {
            deviceData.setTransportConfiguration(new DefaultDeviceTransportConfiguration());
            credentials.setCredentialsType(DeviceCredentialsType.ACCESS_TOKEN);
        } else {
            deviceData.setTransportConfiguration(new Lwm2mDeviceTransportConfiguration());
            credentials.setCredentialsType(DeviceCredentialsType.LWM2M_CREDENTIALS);
            LwM2MDeviceCredentials lwm2mCreds = new LwM2MDeviceCredentials();
            NoSecClientCredential client = new NoSecClientCredential();
            client.setEndpoint(credentials.getCredentialsId());
            lwm2mCreds.setClient(client);
            LwM2MBootstrapClientCredentials bootstrap = new LwM2MBootstrapClientCredentials();
            bootstrap.setBootstrapServer(new NoSecBootstrapClientCredential());
            bootstrap.setLwm2mServer(new NoSecBootstrapClientCredential());
            lwm2mCreds.setBootstrap(bootstrap);
            credentials.setCredentialsValue(JacksonUtil.toString(lwm2mCreds));
        }

        device = tbClient.saveDeviceWithCredentials(device, credentials).get();
        if (calculatedFieldsMonitoringEnabled) {
            createCalculatedField(device);
        }
        return device;
    }

    private DeviceProfile getOrCreateDeviceProfile(TransportMonitoringConfig config, TransportMonitoringTarget target) {
        TransportType transportType = config.getTransportType();
        String profileName = String.format("%s %s (%s)", target.getNamePrefix(), transportType.getName(), target.getQueue()).trim();
        DeviceProfile deviceProfile = tbClient.getDeviceProfiles(new PageLink(1, 0, profileName)).getData()
                .stream().findFirst().orElse(null);
        if (deviceProfile != null) {
            return deviceProfile;
        }

        log.info("Creating new device profile '{}'", profileName);
        if (transportType != TransportType.LWM2M) {
            deviceProfile = new DeviceProfile();
            deviceProfile.setType(DeviceProfileType.DEFAULT);
            deviceProfile.setTransportType(DeviceTransportType.DEFAULT);
            DeviceProfileData profileData = new DeviceProfileData();
            profileData.setConfiguration(new DefaultDeviceProfileConfiguration());
            profileData.setTransportConfiguration(new DefaultDeviceProfileTransportConfiguration());
            deviceProfile.setProfileData(profileData);
        } else {
            tbClient.getResources(new PageLink(1, 0, "LwM2M Monitoring")).getData()
                    .stream().findFirst()
                    .orElseGet(() -> {
                        TbResource newResource = ResourceUtils.getResource("lwm2m/resource.json", TbResource.class);
                        log.info("Creating LwM2M resource");
                        return tbClient.saveResource(newResource);
                    });
            deviceProfile = ResourceUtils.getResource("lwm2m/device_profile.json", DeviceProfile.class);
        }

        deviceProfile.setName(profileName);
        deviceProfile.setDefaultQueueName(target.getQueue());
        return tbClient.saveDeviceProfile(deviceProfile);
    }

    private void createCalculatedField(Device device) {
        log.info("Creating calculated field for device '{}'", device.getName());
        CalculatedField calculatedField = new CalculatedField();
        calculatedField.setName(TEST_CF_TELEMETRY_KEY);
        calculatedField.setEntityId(device.getId());
        calculatedField.setType(CalculatedFieldType.SCRIPT);
        ScriptCalculatedFieldConfiguration configuration = new ScriptCalculatedFieldConfiguration();
        Argument testDataArgument = new Argument();
        testDataArgument.setRefEntityKey(new ReferencedEntityKey(TEST_TELEMETRY_KEY, ArgumentType.TS_LATEST, null));
        configuration.setArguments(Map.of(
                TEST_TELEMETRY_KEY, testDataArgument
        ));
        configuration.setExpression("return { \"" + TEST_CF_TELEMETRY_KEY + "\": " + TEST_TELEMETRY_KEY + " + \"-cf\" };");
        configuration.setOutput(new TimeSeriesOutput());
        calculatedField.setConfiguration(configuration);
        calculatedField.setDebugMode(true);
        tbClient.saveCalculatedField(calculatedField);
    }

    public String getDashboardPublicLink() {
        String link = "";
        try {
            if (dashboardId == null) {
                return link;
            }
            String publicCustomerId = isPe() ? getPublicCustomerIdPe() : getPublicCustomerIdCe();
            if (publicCustomerId != null) {
                link = buildPublicDashboardLink(dashboardId, publicCustomerId);
                log.info("Public Monitoring dashboard link: {}", link);
            } else {
                log.warn("Dashboard is not assigned to a public customer/group. Public link can't be generated.");
            }
        } catch (Exception e) {
            log.error("Failed to get a public link to Monitoring dashboard ", e);
        }
        return link;
    }

    private String getPublicCustomerIdCe() {
        Optional<DashboardInfo> infoOpt = tbClient.getDashboardInfoById(dashboardId);
        if (infoOpt.isEmpty()) {
            return null;
        }
        Set<ShortCustomerInfo> customers = infoOpt.get().getAssignedCustomers();
        if (customers == null) {
            return null;
        }
        return customers.stream()
                .filter(ShortCustomerInfo::isPublic)
                .map(c -> c.getCustomerId().getId().toString())
                .findFirst().orElse(null);
    }

    private String getPublicCustomerIdPe() {
        EntityGroupInfo group = getOrCreatePublicGroup(EntityType.DASHBOARD);
        JsonNode additionalInfo = group.getAdditionalInfo();
        if (additionalInfo != null && additionalInfo.has("publicCustomerId")) {
            return additionalInfo.get("publicCustomerId").asText();
        }
        return null;
    }

    private Dashboard getOrCreateMonitoringDashboard() {
        Dashboard existing = findDashboardByTitle(DASHBOARD_TITLE).orElse(null);
        if (existing != null) {
            log.debug("Found Monitoring dashboard '{}' with id {}", existing.getTitle(), existing.getId());
            return existing;
        }

        Dashboard dashboardFromResource = ResourceUtils.getResource(DASHBOARD_RESOURCE_PATH, Dashboard.class);
        dashboardFromResource.setTitle(DASHBOARD_TITLE);
        //Optional.ofNullable(existing).map(Dashboard::getId).ifPresent(dashboardFromResource::setId);
        Dashboard saved = tbClient.saveDashboard(dashboardFromResource);
        log.info("Created Monitoring dashboard '{}' with id {}", saved.getTitle(), saved.getId());
        return saved;
    }

    private Optional<Dashboard> findDashboardByTitle(String title) {
        // Use text search first and then filter by exact title
        PageData<DashboardInfo> page = tbClient.getTenantDashboards(new PageLink(10, 0, title));
        return page.getData().stream()
                .filter(info -> title.equals(info.getTitle()))
                .findFirst()
                .flatMap(info -> tbClient.getDashboardById(info.getId()));
    }

    private String buildPublicDashboardLink(DashboardId dashboardId, String publicCustomerId) {
        String base = getBaseUrl();
        return String.format("%s/dashboard/%s?publicId=%s", base, dashboardId.getId().toString(), publicCustomerId);
    }

    private String getBaseUrl() {
        // TbClient.baseURL contains the root url, without trailing slash
        try {
            var baseUrlField = tbClient.getClass().getSuperclass().getDeclaredField("baseURL");
            baseUrlField.setAccessible(true);
            return (String) baseUrlField.get(tbClient);
        } catch (Exception e) {
            log.warn("Unable to access baseURL from RestClient. Falling back to http://localhost:8080");
            return "http://localhost:8080";
        }
    }

    // Integrations Framework is PE-only; this codepath is only ever exercised when an
    // integration check is enabled in config, which only makes sense against a PE target.
    public void checkEntities(IntegrationMonitoringConfig config, IntegrationMonitoringTarget target) {
        Device device = getOrCreateIntegrationDevice(config, target);
        DeviceConfig deviceConfig = new DeviceConfig();
        deviceConfig.setId(device.getId().toString());
        deviceConfig.setName(device.getName());
        target.setDevice(deviceConfig);

        Converter converter = getOrCreateMonitoringConverter();
        Integration integration = getOrCreateIntegration(config, target, converter.getId());
        target.setIntegration(integration);
    }

    private Device getOrCreateIntegrationDevice(IntegrationMonitoringConfig config, IntegrationMonitoringTarget target) {
        String deviceName = String.format("%s %s integration - %s", target.getNamePrefix(), config.getIntegrationType().getName(), target.getBaseUrl()).trim();
        return tbClient.getTenantDevice(deviceName)
                .orElseGet(() -> {
                    Device device = ResourceUtils.getResource("integration/device.json", Device.class);
                    device.setName(deviceName);
                    log.info("Creating new device '{}'", deviceName);
                    return tbClient.saveDevice(device);
                });
    }

    private Converter getOrCreateMonitoringConverter() {
        String converterName = "[Monitoring] Default converter";
        return tbClient.getConverters(new PageLink(1, 0, converterName)).getData()
                .stream().findFirst()
                .orElseGet(() -> {
                    Converter converter = ResourceUtils.getResource("integration/converter.json", Converter.class);
                    converter.setName(converterName);
                    log.info("Creating new converter '{}'", converterName);
                    return tbClient.saveConverter(converter);
                });
    }

    private Integration getOrCreateIntegration(IntegrationMonitoringConfig config, IntegrationMonitoringTarget target, ConverterId converterId) {
        String integrationName = String.format("%s %s integration - %s", target.getNamePrefix(), config.getIntegrationType().getName(), target.getBaseUrl()).trim();
        return tbClient.getIntegrations(new PageLink(1, 0, integrationName)).getData()
                .stream().findFirst()
                .orElseGet(() -> {
                    String routingKey = UUID.randomUUID().toString();

                    List<String> configParams;
                    if (config instanceof MqttIntegrationMonitoringConfig mqttConfig) {
                        URI uri = URI.create(target.getBaseUrl());
                        configParams = List.of(
                                uri.getHost() /* %1$s */,
                                String.valueOf(uri.getPort()) /* %2$s */,
                                routingKey /* %3$s */,
                                RandomStringUtils.secure().nextNumeric(6) /* client id suffix, %4$s */,
                                Objects.requireNonNullElse(mqttConfig.getUsername(), "") /* %5$s */
                        );
                    } else {
                        configParams = List.of(
                                target.getBaseUrl() /* %1$s */,
                                routingKey /* %2$s */
                        );
                    }
                    // The template's placeholders must be substituted before the text is parsed - a
                    // numeric field (e.g. MQTT port) can only be filled in with a real JSON number
                    // this way; parsing first and formatting configuration.toString() afterwards (as
                    // done for the other fields here) would leave it a quoted string.
                    String rawTemplate = ResourceUtils.getResourceAsString(
                            "integration/" + config.getIntegrationType().name().toLowerCase() + "/integration.json");
                    Integration integration = JacksonUtil.fromString(
                            String.format(rawTemplate, configParams.toArray()), Integration.class);

                    integration.setName(integrationName);
                    integration.setDefaultConverterId(converterId);
                    integration.setRoutingKey(routingKey);
                    log.info("Creating new integration '{}'", integrationName);
                    return tbClient.saveIntegration(integration);
                });
    }

}

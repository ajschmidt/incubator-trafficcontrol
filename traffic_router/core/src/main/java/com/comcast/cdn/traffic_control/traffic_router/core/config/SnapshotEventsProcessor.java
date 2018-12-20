/*
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

package com.comcast.cdn.traffic_control.traffic_router.core.config;

import com.comcast.cdn.traffic_control.traffic_router.core.cache.Cache;
import com.comcast.cdn.traffic_control.traffic_router.core.cache.Cache.DeliveryServiceReference;
import com.comcast.cdn.traffic_control.traffic_router.core.ds.DeliveryService;
import com.comcast.cdn.traffic_control.traffic_router.core.util.JsonUtils;
import com.comcast.cdn.traffic_control.traffic_router.core.util.JsonUtilsException;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SnapshotEventsProcessor {
	private static final Logger LOGGER = Logger.getLogger(SnapshotEventsProcessor.class);
	final private Map<String, DeliveryService> creationEvents = new HashMap<>();
	final private Map<String, DeliveryService> updateEvents = new HashMap<>();
	final private Map<String, DeliveryService> deleteEvents = new HashMap<>();
	final private Map<String, Cache> mappingEvents = new HashMap<>();
	final private List<String> serverModEvents = new ArrayList<>();

	public List<String> getDeleteCacheEvents() {
		return deleteCacheEvents;
	}

	final private List<String> deleteCacheEvents = new ArrayList<>();
	private JsonNode existingConfig = null;
	private boolean loadall = false;

	public static SnapshotEventsProcessor diffCrConfigs(final JsonNode newSnapDb,
	                                                    final JsonNode existingDb) throws
			JsonUtilsException, ParseException {
		LOGGER.info("In diffCrConfigs");
		final SnapshotEventsProcessor sepRet = new SnapshotEventsProcessor();

		if (newSnapDb == null)
		{
			final String errstr = "Required parameter 'newSnapDb' was null";
			LOGGER.error(errstr);
			JsonUtils.throwException(errstr);
		}
		// Load the entire crConfig from the snapshot if there isn't one saved on the filesystem
		if (existingDb == null || existingDb.size()< 1) {
			sepRet.parseDeliveryServices(newSnapDb);
			sepRet.loadall = true;
			return sepRet;
		}
		// Load the entire crConfig from the snapshot if it is not a version supporting
		// DS Snapshots
		if (!sepRet.versionSupportsDsSnapshots(newSnapDb)){
			LOGGER.info("In diffCrConfig 'DS Snapshot' feature turned off.");
			sepRet.parseDeliveryServices(newSnapDb);
			sepRet.loadall = true;
			return sepRet;
		}
		// Verify that only DS related configurations have changed
		if (sepRet.hasDiffsForcingReload(newSnapDb, existingDb)){
			LOGGER.info("hasDiffsForcingReload true");
			sepRet.parseDeliveryServices(newSnapDb);
			sepRet.loadall = true;
			return sepRet;
		}
		// process only the changes to Delivery Services if none of the above conditions are met
		sepRet.diffDeliveryServices(newSnapDb, existingDb);
		sepRet.diffCacheMappings(newSnapDb, existingDb);
		return sepRet;
	}

	private boolean versionSupportsDsSnapshots(final JsonNode snapDb) throws JsonUtilsException {
		if (snapDb == null) {
			return false;
		}
		final JsonNode config = JsonUtils.getJsonNode(snapDb,ConfigHandler.CONFIG_KEY);
		return (config.has(ConfigHandler.DS_SNAPSHOTS_KEY) &&
				config.get(ConfigHandler.DS_SNAPSHOTS_KEY).textValue().equals("true"));
	}

	private boolean hasDiffsForcingReload(final JsonNode newSnapDb, final JsonNode existingConfig) throws JsonUtilsException {
		if (!JsonUtils.equalSubtreesExcept(newSnapDb, existingConfig,ConfigHandler.CONFIG_KEY,ConfigHandler.DS_SNAPSHOTS_KEY)) {
			LOGGER.info("Config diff found");
			return true;
		}
		if (!JsonUtils.equalSubtrees(newSnapDb, existingConfig,"contentRouters")) {
			LOGGER.info("ContentRouters diff found");
			return true;
		}
		if (!JsonUtils.equalSubtrees(newSnapDb, existingConfig,"monitors")) {
			LOGGER.info("Monitors diff found");
			return true;
		}
		if (!JsonUtils.equalSubtreesExcept(newSnapDb, existingConfig,"stats","date")) {
			LOGGER.info("Stats diff found");
			return true;
		}
		if (!JsonUtils.equalSubtrees(newSnapDb, existingConfig,"edgeLocations")) {
			LOGGER.info("EdgeLocation diff found");
			return true;
		}
		return !contentServersEqual(newSnapDb,existingConfig);
	}

	private boolean contentServersEqual(final JsonNode newSnapDb, final JsonNode existingConfig) throws JsonUtilsException {
		final JsonNode cs1 = JsonUtils.getJsonNode(newSnapDb, "contentServers");
		final JsonNode cs2 = JsonUtils.getJsonNode(existingConfig, "contentServers");

		if (cs1.size() != cs2.size())
		{
			LOGGER.info("ContentServers Size diff found");
			return false;
		}
		final Iterator<String> cs1fields = cs1.fieldNames();
		final Iterator<String> cs2fields = cs2.fieldNames();
		while (cs1fields.hasNext()) {
			final String csName = cs1fields.next();
			if (!JsonUtils.equalSubtreesExcept(cs1,cs2,csName,"deliveryServices")) {
				LOGGER.info("ContentServers CS1, CS2 diff found for "+csName);
				return false;
			}
		}
		while (cs2fields.hasNext()) {
			final String csName = cs2fields.next();
			if (!cs1.has(csName)) {
				LOGGER.info("ContentServers CS2 extra found");
				return false;
			}
		}
		return true;
	}

	private void diffCacheMappings(final JsonNode newSnapDb, final JsonNode existingDb) throws JsonUtilsException,
			ParseException {
		setExistingConfig(JsonUtils.getJsonNode(existingDb, ConfigHandler.CONFIG_KEY));
		final JsonNode newServers = JsonUtils.getJsonNode(newSnapDb, ConfigHandler.CONTENT_SERVERS_KEY);
		final Iterator<String> newServersIter = newServers.fieldNames();
		while (newServersIter.hasNext()) {
			final String cacheId = newServersIter.next();
			final JsonNode newCacheJson = newServers.get(cacheId);
			if (!newCacheJson.has(ConfigHandler.DELIVERY_SERVICES_KEY)) {
				continue;
			}
			final Iterator<String> dsrKeys =
					JsonUtils.getJsonNode(newCacheJson, ConfigHandler.DELIVERY_SERVICES_KEY).fieldNames();
			while (dsrKeys.hasNext()) {
				final String dsrId = dsrKeys.next();
				if (serverModEvents.contains(dsrId)) {
					addMappingEvent(newCacheJson, cacheId);
				}
			}
			if (dssLinkChangeDetected(cacheId, newCacheJson, existingDb)) {
				addMappingEvent(newCacheJson, cacheId);
			}
		}

		parseDeleteCacheEvents(JsonUtils.getJsonNode(existingDb, ConfigHandler.CONTENT_SERVERS_KEY), newServers);
	}

	private boolean dssLinkChangeDetected(final String cid, final JsonNode newCacheJson, final JsonNode existingDb) throws
			JsonUtilsException {
		if (existingDb == null)
		{
			return true;
		}
		final JsonNode existingServers = JsonUtils.getJsonNode(existingDb,ConfigHandler.CONTENT_SERVERS_KEY);
		if (!existingServers.has(cid)) {
			return true;
		}
		final JsonNode existingCache = JsonUtils.getJsonNode(existingServers,cid);
		if (newCacheJson.has(ConfigHandler.DELIVERY_SERVICES_KEY) && !existingCache.has(ConfigHandler.DELIVERY_SERVICES_KEY)) {
			return true;
		}
		if (newCacheJson.has(ConfigHandler.DELIVERY_SERVICES_KEY)) {
			final JsonNode newDsLinks = JsonUtils.getJsonNode(newCacheJson, ConfigHandler.DELIVERY_SERVICES_KEY);
			return !newDsLinks.equals(JsonUtils.getJsonNode(existingCache, ConfigHandler.DELIVERY_SERVICES_KEY));
		} else {
			return true;
		}
	}

	private void addMappingEvent(final JsonNode newCacheJson, final String cid) throws JsonUtilsException, ParseException {
		if (mappingEvents.keySet().contains(cid)) {
			return;
		}
		final Cache modCache = new Cache(cid, cid, 1);
		final List<DeliveryServiceReference> dsRefs = parseDsRefs(newCacheJson);
		if (!dsRefs.isEmpty()) {
			modCache.setDeliveryServices(dsRefs);
		}
		mappingEvents.put(cid, modCache);
	}

	private void parseDeleteCacheEvents(final JsonNode exCacheServers, final JsonNode newServers) {
		final Iterator<String> exServersIter = exCacheServers.fieldNames();
		exServersIter.forEachRemaining(cId->{
			if (!newServers.has(cId)) {
				deleteCacheEvents.add(cId);
			}
		});
	}

	private List<DeliveryServiceReference> parseDsRefs(final JsonNode cacheJson) throws JsonUtilsException, ParseException{
		final List<DeliveryServiceReference> dsRefs = new ArrayList<>();
		if (cacheJson.has(ConfigHandler.DELIVERY_SERVICES_KEY)) {
			final JsonNode dsRefsJson = JsonUtils.getJsonNode(cacheJson, ConfigHandler.DELIVERY_SERVICES_KEY);
			final Iterator<String> dsrfs = dsRefsJson.fieldNames();
			while (dsrfs.hasNext()) {
				final String dsid = dsrfs.next();
				final JsonNode dso = dsRefsJson.get(dsid);
				DeliveryServiceReference newDsref = null;
				if (dso.isArray() && dso.size() > 0) {
					final JsonNode nameNode = dso.get(0);
					final List<String> dsNames = parseDsAliases(dso);
					newDsref = new DeliveryServiceReference(dsid, nameNode.asText(), dsNames);
					dsRefs.add(newDsref);
				} else {
					dsRefs.add(new DeliveryServiceReference(dsid, dso.toString()));
				}
			}
		}
		return dsRefs;
	}

	private List<String> parseDsAliases(final JsonNode dso) {
		final String tld = JsonUtils.optString(getExistingConfig(), "domain_name");
		final List<String> dsNames = new ArrayList<>();

		if (dso.isArray()) {
			for (final JsonNode nameNode : dso) {
				final String name = nameNode.asText();

				if (name.endsWith(tld)) {
					final String reName = name.replaceAll("^.*?\\.", "");

					if (!dsNames.contains(reName)) {
						dsNames.add(reName);
					}
				} else {
					if (!dsNames.contains(name)) {
						dsNames.add(name);
					}
				}
			}
		} else {
			dsNames.add(dso.toString());
		}
	    return dsNames;
	}

	private void parseDeliveryServices(final JsonNode newSnapDb) throws
			JsonUtilsException {
		this.diffDeliveryServices(newSnapDb, null);
	}

	private void diffDeliveryServices(final JsonNode newSnapDb, final JsonNode existingDb) throws
			JsonUtilsException {
		JsonNode compDeliveryServices = null;
		final JsonNode newDeliveryServices = JsonUtils.getJsonNode(newSnapDb, ConfigHandler.DELIVERY_SERVICES_KEY);
		final List<String> existingIds = new ArrayList<>();

		// find delivery services that have been changed or deleted in the new snapshot
		if (existingDb != null) {
			compDeliveryServices = JsonUtils.getJsonNode(existingDb, ConfigHandler.DELIVERY_SERVICES_KEY);

			final Iterator<String> compIter = compDeliveryServices.fieldNames();
			while (compIter.hasNext()) {
				final String deliveryServiceId = compIter.next();
				final JsonNode compDeliveryService = JsonUtils.getJsonNode(compDeliveryServices, deliveryServiceId);
				existingIds.add(deliveryServiceId);

				final JsonNode newService = newDeliveryServices.get(deliveryServiceId);

				if (newService == null) {
					LOGGER.info(("deleted Delivery Service = "+deliveryServiceId));
					addEvent(deleteEvents, compDeliveryService, deliveryServiceId);
				}
				if (newService != null && isUpdated(newService, compDeliveryService)) {
					addEvent(updateEvents, newService, deliveryServiceId);
				}
			}
		}

		// find delivery services that have been added in the latest snapshot
		final Iterator<String> newServiceIter = newDeliveryServices.fieldNames();
		while (newServiceIter.hasNext()) {
			final String deliveryServiceId = newServiceIter.next();
			if (!existingIds.contains(deliveryServiceId)) {
				addEvent(creationEvents, JsonUtils.getJsonNode(newDeliveryServices, deliveryServiceId), deliveryServiceId);
			}
		}
	}

	private boolean isUpdated(final JsonNode newService, final JsonNode deliveryServiceJson) {
		return !(newService.equals(deliveryServiceJson));
	}

	private void addEvent(final Map<String, DeliveryService> events, final JsonNode ds, final String dsid) throws
			JsonUtilsException {
		final DeliveryService deliveryService = new DeliveryService(dsid, ds);
		boolean isDns = false;
		final JsonNode matchsets = JsonUtils.getJsonNode(ds, "matchsets");

		for (final JsonNode matchset : matchsets) {
			if (matchset != null && matchset.has("protocol")) {
				final String protocol = JsonUtils.getString(matchset, "protocol");
				if ("DNS".equals(protocol)) {
					isDns = true;
				}
			}
		}
		deliveryService.setDns(isDns);
		events.put(dsid, deliveryService);
	}

	public boolean shouldLoadAll() {
		return loadall;
	}

	public Map<String, DeliveryService> getCreationEvents() {
		return creationEvents;
	}

	public Map<String, DeliveryService> getDeleteEvents() {
		return deleteEvents;
	}

	public Map<String, DeliveryService> getUpdateEvents() {
		return updateEvents;
	}

	public List<DeliveryService> getSSLEnabledChangeEvents() {
		final List<DeliveryService> httpsDeliveryServices = new ArrayList<>();
		getChangeEvents().forEach((dsid, ds) -> {
			if (!ds.isDns() && ds.isSslEnabled()) {
				httpsDeliveryServices.add(ds);
			}
		});
		return httpsDeliveryServices;
	}

	public Map<String, DeliveryService> getChangeEvents() {
		final Map<String, DeliveryService> retEvts = new HashMap<>();
		retEvts.putAll(creationEvents);
		retEvts.putAll(updateEvents);
		return retEvts;
	}

	public Map<String, Cache> getMappingEvents() {
		return mappingEvents;
	}

	public JsonNode getExistingConfig(){
		return existingConfig;
	}

	private void setExistingConfig( final JsonNode config ){
		existingConfig = config;
	}
}

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
	final private Map<String, DeliveryService> noChangeEvents = new HashMap<>();
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
		// Load the entire crConfig from the snapshot if it is not version a version supporting
		// DS Snapshots
		if (!sepRet.versionSupportsDsSnapshots(newSnapDb)){
			LOGGER.info("In diffCrConfig got old version.");
			sepRet.parseDeliveryServices(newSnapDb);
			sepRet.loadall = true;
			return sepRet;
		}
		// Load the entire crConfig from the snapshot if the load flag is set in the snapshot
		final JsonNode config = JsonUtils.getJsonNode(newSnapDb, ConfigHandler.configKey);
		if (JsonUtils.optLong(config, "modified", 0L) > ConfigHandler.getLastSnapshotTimestamp()) {
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
		return snapDb.has(ConfigHandler.versionKey);
	}

	private void diffCacheMappings(final JsonNode newSnapDb, final JsonNode existingDb) throws JsonUtilsException,
			ParseException {
		setExistingConfig(JsonUtils.getJsonNode(existingDb, ConfigHandler.configKey));
		final JsonNode newServers = JsonUtils.getJsonNode(newSnapDb, ConfigHandler.contentServersKey);
		final Iterator<String> newServersIter = newServers.fieldNames();
		while (newServersIter.hasNext()) {
			final String cid = newServersIter.next();
			final JsonNode newCacheJo = newServers.get(cid);
			final Iterator<String> dsrKeys =
					JsonUtils.getJsonNode(newCacheJo, ConfigHandler.deliveryServicesKey).fieldNames();
			while (dsrKeys.hasNext()) {
				final String dsrId = dsrKeys.next();
				if (serverModEvents.contains(dsrId)) {
					addMappingEvent(newCacheJo, cid);
				}
			}
			if (!newCacheJo.has(ConfigHandler.deliveryServicesModKey)) { LOGGER.info("deliveryServicesModified is not in the json: "+newCacheJo.toString());}
			final long dsm = JsonUtils.getLong(newCacheJo, ConfigHandler.deliveryServicesModKey);
			if (dsm > ConfigHandler.getLastSnapshotTimestamp()) {
				addMappingEvent(newCacheJo, cid);
			}
		}

		parseDeleteCacheEvents(JsonUtils.getJsonNode(existingDb, ConfigHandler.contentServersKey), newServers);
	}

	private void addMappingEvent(final JsonNode newCacheJo, final String cid) throws JsonUtilsException, ParseException {
		if (mappingEvents.keySet().contains(cid)) {
			return;
		}
		final Cache modCache = new Cache(cid, cid, 1);
		final List<DeliveryServiceReference> dsRefs = parseDsRefs(newCacheJo);
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

	public List<DeliveryServiceReference> parseDsRefs(final JsonNode cjo) throws JsonUtilsException, ParseException{
		final List<DeliveryServiceReference> dsRefs = new ArrayList<>();
		if (cjo.has(ConfigHandler.deliveryServicesKey)) {
			final JsonNode dsRefsJo = JsonUtils.getJsonNode(cjo, ConfigHandler.deliveryServicesKey);
			final Iterator<String> dsrfs = dsRefsJo.fieldNames();
			while (dsrfs.hasNext()) {
				final String dsid = dsrfs.next();
				final JsonNode dso = dsRefsJo.get(dsid);
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
		final JsonNode newDeliveryServices = JsonUtils.getJsonNode(newSnapDb, ConfigHandler.deliveryServicesKey);
		final List<String> existingIds = new ArrayList<>();

		if (existingDb != null) {
			compDeliveryServices = JsonUtils.getJsonNode(existingDb, ConfigHandler.deliveryServicesKey);

			final Iterator<String> deliveryServiceIter = compDeliveryServices.fieldNames();
			while (deliveryServiceIter.hasNext()) {
				final String deliveryServiceId = deliveryServiceIter.next();
				final JsonNode deliveryServiceJson = JsonUtils.getJsonNode(compDeliveryServices, deliveryServiceId);
				existingIds.add(deliveryServiceId);

				final JsonNode newService = newDeliveryServices.get(deliveryServiceId);

				if (newService != null) {
					LOGGER.info(("found service = "+deliveryServiceId));
					if (isUpdated(newService)) {
						addEvent(updateEvents, newService, deliveryServiceId);

						if (fqdnUpdated(newService,deliveryServiceJson)) {
							LOGGER.info("fqdnUpdated for delivery service: "+deliveryServiceId);
							serverModEvents.add(deliveryServiceId);
						}
					} else {
						addEvent(noChangeEvents, newService, deliveryServiceId);
					}
				} else {
					LOGGER.info(("deleted Service = "+deliveryServiceId));
					addEvent(deleteEvents, deliveryServiceJson, deliveryServiceId);
				}
			}
		}

		final Iterator<String> newServiceIter = newDeliveryServices.fieldNames();
		while (newServiceIter.hasNext()) {
			final String deliveryServiceId = newServiceIter.next();
			final JsonNode newDsJson = JsonUtils.getJsonNode(newDeliveryServices, deliveryServiceId);
			if (!existingIds.contains(deliveryServiceId)) {
				addEvent(creationEvents, newDsJson, deliveryServiceId);
			}
		}
	}

	private boolean fqdnUpdated(final JsonNode newService, final JsonNode existingService) throws JsonUtilsException {
		final JsonNode newDomains = JsonUtils.getJsonNode(newService, "domains");
		if (!newDomains.equals(JsonUtils.getJsonNode(existingService, "domains"))) {
			return true;
		}
		final JsonNode newMatchSets = JsonUtils.getJsonNode(newService,"matchsets");
		if (!newMatchSets.equals(JsonUtils.getJsonNode(existingService, "matchsets"))) {
			return true;
		}
		return false;
	}

	private void addEvent(final Map<String, DeliveryService> events, final JsonNode ds, final String dsid) throws
			JsonUtilsException {
		final DeliveryService deliveryService = new DeliveryService(dsid, ds);
		boolean isDns = false;

		final JsonNode matchsets = JsonUtils.getJsonNode(ds, "matchsets");

		for (final JsonNode matchset : matchsets) {
			final String protocol = JsonUtils.getString(matchset, "protocol");
			if ("DNS".equals(protocol)) {
				isDns = true;
			}
		}
		deliveryService.setDns(isDns);
		events.put(dsid, deliveryService);
	}

	private boolean isUpdated(final JsonNode svcNode) throws JsonUtilsException {
		final long anyModDate = JsonUtils.getLong(svcNode, "anyModified");
		return (anyModDate > ConfigHandler.getLastSnapshotTimestamp());
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

	public Map<String, DeliveryService> getNoChangeEvents() {
		return noChangeEvents;
	}

	public List<DeliveryService> getSSLEnableDeliveryServices() {
		final List<DeliveryService> httpsDeliveryServices = new ArrayList<>();
		httpsDeliveryServices.addAll(getSSLEnabledChangeEvents());
		getNoChangeEvents().forEach((dsid, ds) -> {
			if (!ds.isDns() && ds.isSslEnabled()) {
				httpsDeliveryServices.add(ds);
			}
		});
		return httpsDeliveryServices;
	}

	public List<DeliveryService> getSSLEnabledChangeEvents() {
		final List<DeliveryService> httpsDeliveryServices = new ArrayList<>();
		getUpdateEvents().forEach((dsid, ds) -> {
			if (!ds.isDns() && ds.isSslEnabled()) {
				httpsDeliveryServices.add(ds);
			}
		});
		getCreationEvents().forEach((dsid, ds) -> {
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

	public void setExistingConfig( final JsonNode config ){
		existingConfig = config;
	}
}

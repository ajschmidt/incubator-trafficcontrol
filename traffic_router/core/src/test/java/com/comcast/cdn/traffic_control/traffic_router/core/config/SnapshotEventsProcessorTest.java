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
import com.comcast.cdn.traffic_control.traffic_router.core.ds.DeliveryService;
import com.comcast.cdn.traffic_control.traffic_router.core.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.powermock.reflect.Whitebox;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SnapshotEventsProcessorTest {
	private JsonNode newDsSnapJo;
	private JsonNode baselineJo;
	private JsonNode updateJo;

	@Before
	public void setUp() throws Exception {
		String resourcePath = "unit/ExistingConfig.json";
		InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
		if (inputStream == null) {
			fail("Could not find file '" + resourcePath + "' needed for test from the current classpath as a resource!");
		}
		String baseDb = IOUtils.toString(inputStream);

		resourcePath = "unit/UpdateDsSnap.json";
		inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
		if (inputStream == null) {
			fail("Could not find file '" + resourcePath + "' needed for test from the current classpath as a resource!");
		}
		String updateDb = IOUtils.toString(inputStream);

		resourcePath = "unit/NewDsSnap.json";
		inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
		if (inputStream == null) {
			fail("Could not find file '" + resourcePath + "' needed for test from the current classpath as a resource!");
		}
		String newDsSnapDb = IOUtils.toString(inputStream);

		final ObjectMapper mapper = new ObjectMapper();
		assertThat(newDsSnapDb, notNullValue());
		assertThat(baseDb, notNullValue());
		assertThat(updateDb, notNullValue());

		newDsSnapJo = mapper.readTree(newDsSnapDb);
		assertThat(newDsSnapJo, notNullValue());
		updateJo = mapper.readTree(updateDb);
		assertThat(updateJo, notNullValue());
		baselineJo = mapper.readTree(baseDb);
		assertThat(baselineJo, notNullValue());
	}

	@Test
	public void mineEventsFromDBDiffsNoChanges() throws Exception {
		SnapshotEventsProcessor snapEvents = SnapshotEventsProcessor.diffCrConfigs(baselineJo, null);
		assertThat("LoadAll should be true because the snapshot does not have a snapshot config parameter.",
				snapEvents.shouldLoadAll());
		assertThat("18 Delivery services should have been loaded but there were only "+snapEvents.getCreationEvents().size(), snapEvents.getCreationEvents().size() == 18);
		snapEvents = SnapshotEventsProcessor.diffCrConfigs(baselineJo, baselineJo);
		assertThat("No new, updated or deleted delivery services should have been loaded beacause the snapshots were " +
						"the same: New = " + snapEvents.getCreationEvents().size() + ", updated = "+ snapEvents.getUpdateEvents().size() + ", deleted = "+ snapEvents.getDeleteEvents().size(),
				(snapEvents.getCreationEvents().size() == 0 && snapEvents.getUpdateEvents().size() == 0 && snapEvents.getDeleteEvents().size() == 0));
		assertThat("No other events should have been loaded beacause the snapshots were the same: mapping = " +
						snapEvents.getMappingEvents().size()+ ", cache = "+ snapEvents.getDeleteCacheEvents().size() +
						", ssl = "+ snapEvents.getSSLEnabledChangeEvents().size(),
				(snapEvents.getMappingEvents().size() == 0 && snapEvents.getDeleteCacheEvents().size() == 0 && snapEvents.getSSLEnabledChangeEvents().size() == 0));
	}

	@Test
	public void mineEventsFromDBNewUpdate() throws Exception {
		ConfigHandler.setLastSnapshotTimestamp(14650848001l);
		SnapshotEventsProcessor snapEvents = SnapshotEventsProcessor.diffCrConfigs(updateJo,
				baselineJo);
		assertThat("LoadAll should be false.", !snapEvents.shouldLoadAll());
		assertThat("18 Delivery services should have been updated but there were only "+snapEvents.getChangeEvents().size(),
				snapEvents.getChangeEvents().size() == 18);
		assertThat("1 links should have been updated but there were only "+snapEvents.getMappingEvents().size(),
				snapEvents.getMappingEvents().size() == 1);
	}

	@Test
	public void mineEventsFromDBDiffsNewDs() throws Exception {

		ConfigHandler.setLastSnapshotTimestamp(14650848001l);
		SnapshotEventsProcessor snapEvents = SnapshotEventsProcessor.diffCrConfigs(newDsSnapJo, baselineJo);
		assertThat("LoadAll should be false.", !snapEvents.shouldLoadAll());
		assertThat("1 Delivery services should have been added but there was "+snapEvents.getCreationEvents().size(),
				snapEvents.getCreationEvents().size() == 1);
		assertThat("4 links should have been updated but there were only "+snapEvents.getMappingEvents().size(),
				snapEvents.getMappingEvents().size() == 4);
	}

	@Test
	public void diffCrConfigNoChanges() throws Exception {
		ConfigHandler.setLastSnapshotTimestamp(14650848001l);
		SnapshotEventsProcessor snapEvents = SnapshotEventsProcessor.diffCrConfigs(updateJo, updateJo);
		assertThat("LoadAll should be false.", !snapEvents.shouldLoadAll());
		assertThat("0 Delivery services should have been added but there was "+snapEvents.getCreationEvents().size(),
				snapEvents.getCreationEvents().size() == 0);

	}

	@Test
	public void parseDsAliases() throws Exception {
		final JsonNode config = JsonUtils.getJsonNode(baselineJo, ConfigHandler.CONFIG_KEY);
		final JsonNode contentServers = JsonUtils.getJsonNode(baselineJo, ConfigHandler.CONTENT_SERVERS_KEY);
		final JsonNode cjo = contentServers.get("edge-cache-000");
		if (cjo.has(ConfigHandler.DELIVERY_SERVICES_KEY)) {
			final JsonNode dso = cjo.get(ConfigHandler.DELIVERY_SERVICES_KEY).get("https-only-test");
			final SnapshotEventsProcessor sep = mock(SnapshotEventsProcessor.class);
			when(sep.getExistingConfig()).thenReturn(config);
			List<String> aliases = Whitebox.invokeMethod(sep, "parseDsAliases", dso);
			assertThat("Expected to find 1 aliases but found " + aliases.size(), aliases.size() == 1);
			assertThat("Expected aliases to be 'https-only-test.thecdn.example.com' but found " + aliases.get(0),
					aliases.get(0).equals("https-only-test.thecdn.example.com"));
		} else {
			fail("The edge-cache-000 content server needs to have a delivery service.");
		}
	}

	@Test
	public void getSSLEnabledChangeEvents_updated() throws Exception {
		final SnapshotEventsProcessor sep = SnapshotEventsProcessor.diffCrConfigs(updateJo, baselineJo);
		List<DeliveryService> httpsDs = sep.getSSLEnabledChangeEvents();
		assertThat("Expected to find 4 changed https delivery services but found " + httpsDs.size(),
				httpsDs.size() == 4);
		assertThat("Did not get the expected list of Https Delivery Services " + httpsDs.toString(),
				httpsDs.toString().contains("http-only-test"));
	}

	@Test
	public void getSSLEnabledChangeEvents_new() throws Exception {
		final SnapshotEventsProcessor sep = SnapshotEventsProcessor.diffCrConfigs(newDsSnapJo, updateJo);
		List<DeliveryService> httpsDs = sep.getSSLEnabledChangeEvents();
		// Tests JSON equivalence operator for change in order of members vs. actual data value changes
		// acceptHttps and acceptHttp are swapped in one service and dispersion:limit is changed to 2 in the other
		assertThat("Expected to find 4 changed delivery services but found " + httpsDs.size(), httpsDs.size() == 4);
		assertThat("Did not get the expected list of Https Delivery Services " + httpsDs.toString(),
				httpsDs.toString().contains("http-addnew-test"));
	}

	@Test
	public void getChangeEvents() throws Exception {
		final SnapshotEventsProcessor sep = SnapshotEventsProcessor.diffCrConfigs(updateJo, baselineJo);
		Map<String, DeliveryService> changes = sep.getChangeEvents();
		assertThat("Expected to find 18 changed delivery services but found " + changes.size(), changes.size() == 18);
		assertThat("Did not get the expected list of Changed Delivery Services " + changes.toString(),
				changes.toString().contains("http-only-test"));
		assertThat("Did not get the expected list of Changed Delivery Services " + changes.toString(),
				changes.toString().contains("https-only-test"));
		assertThat("Did not get the expected list of Changed Delivery Services " + changes.toString(),
				changes.toString().contains("http-and-https-test"));
		assertThat("Did not get the expected list of Changed Delivery Services " + changes.toString(),
				changes.toString().contains("http-to-https-test"));
	}

	@Test
	public void getMappingEvents_update() throws Exception {
		final SnapshotEventsProcessor sep = SnapshotEventsProcessor.diffCrConfigs(updateJo, baselineJo);
		Map<String, Cache> mappingChanges = sep.getMappingEvents();
		assertThat("Expected to find 1 mapping changes but found " + mappingChanges.size(),
				mappingChanges.size() == 1);
		assertThat("Did not get the expected list of mapping changes " + mappingChanges.toString(),
				mappingChanges.toString().contains("edge-cache-011"));
	}

	@Test
	public void getMappingEvents_new() throws Exception {
		final SnapshotEventsProcessor sep = SnapshotEventsProcessor.diffCrConfigs(newDsSnapJo, updateJo);
		Map<String, Cache> mappingChanges = sep.getMappingEvents();
		assertThat("Expected to find 4 mapping changes but found " + mappingChanges.size(),
				mappingChanges.size() == 4);
		assertThat("Did not get the expected list of mapping changes " + mappingChanges.toString(),
				mappingChanges.toString().contains("edge-cache-000"));
		assertThat("Did not get the expected list of mapping changes " + mappingChanges.toString(),
				mappingChanges.toString().contains("edge-cache-001"));
		assertThat("Did not get the expected list of mapping changes " + mappingChanges.toString(),
				mappingChanges.toString().contains("edge-cache-002"));
		assertThat("Did not get the expected list of mapping changes " + mappingChanges.toString(),
				mappingChanges.toString().contains("edge-cache-011"));
	}

	@Test
	public void getMappingEvents_delete() throws Exception {
		final SnapshotEventsProcessor sep = SnapshotEventsProcessor.diffCrConfigs(updateJo, newDsSnapJo);
		Map<String, Cache> mappingChanges = sep.getMappingEvents();
		assertThat("Expected to find 4 mapping changes but found " + mappingChanges.size(),
				mappingChanges.size() == 4);
		assertThat("Did not get the expected list of mapping changes " + mappingChanges.toString(),
				mappingChanges.toString().contains("edge-cache-000"));
		assertThat("Did not get the expected list of mapping changes " + mappingChanges.toString(),
				mappingChanges.toString().contains("edge-cache-001"));
		assertThat("Did not get the expected list of mapping changes " + mappingChanges.toString(),
				mappingChanges.toString().contains("edge-cache-002"));
		assertThat("Did not get the expected list of mapping changes " + mappingChanges.toString(),
				mappingChanges.toString().contains("edge-cache-011"));
	}
}
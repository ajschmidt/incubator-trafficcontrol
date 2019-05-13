/*
t *
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

package com.comcast.cdn.traffic_control.traffic_router.core.dns;

import com.comcast.cdn.traffic_control.traffic_router.core.cache.CacheRegister;
import com.comcast.cdn.traffic_control.traffic_router.core.config.ConfigHandler;
import com.comcast.cdn.traffic_control.traffic_router.core.config.SnapshotEventsProcessor;
import com.comcast.cdn.traffic_control.traffic_router.core.ds.DeliveryService;
import com.comcast.cdn.traffic_control.traffic_router.core.ds.SteeringWatcher;
import com.comcast.cdn.traffic_control.traffic_router.core.loc.AnonymousIpDatabaseService;
import com.comcast.cdn.traffic_control.traffic_router.core.loc.FederationRegistry;
import com.comcast.cdn.traffic_control.traffic_router.core.loc.FederationsWatcher;
import com.comcast.cdn.traffic_control.traffic_router.core.loc.MaxmindGeolocationService;
import com.comcast.cdn.traffic_control.traffic_router.core.router.StatTracker;
import com.comcast.cdn.traffic_control.traffic_router.core.router.TrafficRouter;
import com.comcast.cdn.traffic_control.traffic_router.core.router.TrafficRouterManager;
import com.comcast.cdn.traffic_control.traffic_router.core.util.JsonUtils;
import com.comcast.cdn.traffic_control.traffic_router.core.util.JsonUtilsException;
import com.comcast.cdn.traffic_control.traffic_router.core.util.TrafficOpsUtils;
import com.comcast.cdn.traffic_control.traffic_router.geolocation.GeolocationService;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.LoadingCache;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;
import org.xbill.DNS.DNSSEC;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;
import org.xbill.DNS.Zone;
import sun.security.x509.DNSName;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;
import static org.powermock.api.mockito.PowerMockito.*;

class SigManagerForTesting extends SignatureManager {
	public static enum KeyProfile {
		ONE, TWO, THREE;
	}

	public static KeyProfile returnKey = KeyProfile.ONE;

	public SigManagerForTesting(TrafficRouterManager trafficRouterManager){
		super(trafficRouterManager);
	}
	public SigManagerForTesting(final ZoneManager zoneManager, final CacheRegister cacheRegister,
	                            final TrafficOpsUtils trafficOpsUtils,
	                            final TrafficRouterManager trafficRouterManager) {
		super(zoneManager, cacheRegister, trafficOpsUtils, trafficRouterManager);
	};

	@Override
	protected JsonNode fetchKeyPairData(CacheRegister cacheRegister){
		final String dnstZskStr =
			"[" +
				"{" +
					"\"inceptionDate\": 1545136505," +
					"\"expirationDate\": 2547728505, " +
					"\"name\": \"dns-test.thecdn.example.com.\"," +
					"\"ttl\": \"60\", " +
					"\"status\": \"new\"," +
					"\"effectiveDate\": 1543408205," +
					"\"public\": " +
				"\"ZG5zLXRlc3QudGhlY2RuLmV4YW1wbGUuY29tLiBJTiBETlNLRVkgMjU2IDMgOCBBd0VBQVpyMFJMdm1ubGNGK1IvMG1ESXJ2T3dZUDdJbVozaER0Tzdpc3NSQ0ZUdlBMNmhOK0dBU3ZvY3NyWXQxeTBITWt4eXN1ZnRDT25vZUh0T25QeUJaR1pWSWM2eVJFaHFLUVJlbnJ6QzBFRmVYWXhiMy9QOGFMV0pVdXBXOVdINXpRTlBEeThGVjJtUzBxSjJCNzRWYmowNnBLQlNFME1OdHQzLzZZUlJlZTlTeAo=\"," +
					"\"private\": " +
				"\"UHJpdmF0ZS1rZXktZm9ybWF0OiB2MS4yCkFsZ29yaXRobTogOCAoUlNBU0hBMjU2KQpNb2R1bHVzOiBtdlJFdSthZVZ3WDVIL1NZTWl1ODdCZy9zaVpuZUVPMDd1S3l4RUlWTzg4dnFFMzRZQksraHl5dGkzWExRY3lUSEt5NSswSTZlaDRlMDZjL0lGa1psVWh6ckpFU0dvcEJGNmV2TUxRUVY1ZGpGdmY4L3hvdFlsUzZsYjFZZm5OQTA4UEx3VlhhWkxTb25ZSHZoVnVQVHFrb0ZJVFF3MjIzZi9waEZGNTcxTEU9ClB1YmxpY0V4cG9uZW50OiBBUUFCClByaXZhdGVFeHBvbmVudDogSmtxRWpiWmduSHFpWkc0cUNnUGE3TERWVksyKzFlNU5VTmIrZkJja2JpSTEwYTVxMlRyb2tEalBMZTVPNnhTbHFlbFpFQ2ora0Z6UEcxaHg5Z2x1azUyWVlMUGJiWkFNMW1WVlc5WldRQkdDM2FzSnRRVWIxbDhLSHpldlJqN0lpNlJlcDVUeldMUnpoWGtncGdLSTV1UVBZY2xhVDJOdGk3M2kyb011SDIwPQpQcmltZTE6IDF5RERoZURDZVdORW5BSnd1ZU0xbThNdzIrb1FmaEk4Z1hwbWMvckZRdHVGMU5RS0Q0a3VEdzIvakxHakJ6RUFvNHYwZjhzQ3E5T2hJaWpvVDVSazl3PT0KUHJpbWUyOiB1R1RRdW5KZWFiS05lWW90TWUxc044M2hMZVlSNVFzY2pGalRCYXZZSzZnbG9FWWpEaSs5SzYzbWQ4YmlXN2hlU2N4bk5FejhKbFMvZ2ZNR1F5Y3hsdz09CkV4cG9uZW50MTogbVZnT1p4QzJMd2EyY2lvL0poR3lOY3hsdUd4WXd6VEdrbGlvVFFXMHRKcDhCQi84NStRVnc3OCtDZERaYjVmYlo3aXNXS2RoeVE4NkxYcFJWZUJtTXc9PQpFeHBvbmVudDI6IGE1U0dJd0Z2REFQY2ZyaWJQYkhqblh0RWtWN1Z1ZWdOcytSdTJiUTAzdU92Y0I3N2ZOOWxZd0tHb0FNdE5ZNFBsTWJvdjU3YXpoSkwyU2xNMGdrZjZRPT0KQ29lZmZpY2llbnQ6IHhDZmtZRC9UT0NvbnNoWWVxZE9yUFA4Ri93a1BaUk41S0Myc3N1U011QjlLTkNJY0VTckVTUW01K2tmbDZHUThwcGxJcVRMU2FUZWlLenFQRFU5ZGhnPT0K\"" +
				"}" +
			"]";
		final String fedZskStr =
			"[" +
				"{" +
					"\"inceptionDate\": 1545136505," +
					"\"expirationDate\": 2547728505, " +
					"\"name\": \"federation-test.thecdn.example.com.\"," +
					"\"ttl\": \"60\", " +
					"\"status\": \"new\"," +
					"\"effectiveDate\": 1543408205," +
					"\"public\": " +
				"\"ZmVkZXJhdGlvbi10ZXN0LnRoZWNkbi5leGFtcGxlLmNvbS4gSU4gRE5TS0VZIDI1NiAzIDggQXdFQUFZMFBPcVRuTjU3MU9pZTNDbTQ2aHRQU1J5NkI2dElPQUtCTExxRTMvY3ZzVFA1YzRBSUxhQ3VYWVIvd1FIUjY0MzhjWW9PWWUyM2NNeXZ5cWFZaU9vMFBCMmVpK3pER0RPZ211TmdIc3VZbXlnU1NzelY4czJSczRTNHZONjdBVjBFVVNiWCttUEFZT1IvMTJIRFRDQy9zT0hRT3IwclllREhHbCswaFBROWYK\"," +
					"\"private\":" +
				"\"UHJpdmF0ZS1rZXktZm9ybWF0OiB2MS4yCkFsZ29yaXRobTogOCAoUlNBU0hBMjU2KQpNb2R1bHVzOiBqUTg2cE9jM252VTZKN2NLYmpxRzA5SkhMb0hxMGc0QW9Fc3VvVGY5eSt4TS9semdBZ3RvSzVkaEgvQkFkSHJqZnh4aWc1aDdiZHd6Sy9LcHBpSTZqUThIWjZMN01NWU02Q2E0MkFleTVpYktCSkt6Tlh5elpHemhMaTgzcnNCWFFSUkp0ZjZZOEJnNUgvWFljTk1JTCt3NGRBNnZTdGg0TWNhWDdTRTlEMTg9ClB1YmxpY0V4cG9uZW50OiBBUUFCClByaXZhdGVFeHBvbmVudDogSmdjcEJEUGhadFV0ckc5SVBKZENxZkJTaUZNMS94TVBVQ2QwbHJvRmplaFNpWEI0WTVTM3JLak80bEZlendnaU5LNXVVSlBYRXJMK2lLYU8zZDcwY1pFR2QrUkZGWDJFUjl2VzlabmxsYlJkSDB6b3lHQkJoTk8wblIrbFVrTUhsdFBQblZjV0Fvdkt6bHZQcFMvQmVVT3R4bllZZVdUWnJzb2pBbjFKdWdFPQpQcmltZTE6IDlQK28zQkgyRTVDYWI3Z2RiZjIva1VYOFFBZ1lYcVFDb3NhbzAyN2EwOTlUdWhYc0UwaTZ3ajZ0V09ySWpmVjFGbnROTzBSZWV1emdESkszSGtIOEh3PT0KUHJpbWUyOiBrMlRCSFJTT2FKOGR5Q3p1RGZ1aUh6MmVXTEhnVGZhNzlFYXNQcGlqSGpvN3FZTGlkRWI0S2diUnpNWEVqOXUyaDRKM252c3dHVmRXRTNzMFJNS0V3UT09CkV4cG9uZW50MTogZXd6OUxxc0d3UVRiekVqWTN5bVhVY3VveWpCR3JTSUxBTjV1Wk9ORW5TMkp5K2kreldDMkRHR1doeFpFN0tmZnl3N2ExMjJiVm5vcWZhWWl1dHZCV1E9PQpFeHBvbmVudDI6IFJ5M1QrSmd4d2FKOXZtcThON0o2YzMzTlYyWG5QWjlXMnp1NStLeTdzV0JMNmF1VWNyVEhLWHlMbXNrekNJb0JWdVdSb1F3TENXSGM1cUdMOTF5OHdRPT0KQ29lZmZpY2llbnQ6IFgzeGc5cmV1cVpjWElkL0tJL2RxQWJXT05kam5BZXEwWE5paHZJQ0djQ0N6YU5GWUo0VnZuRUx0QytZbVdHT2J6bndEd3NyblVBZXRVcloxWUlkaVZRPT0K\"" +
				"}" +
			"]";
		final String dnstKskStr =
			"[" +
				"{" +
					"\"inceptionDate\": 1545136505," +
					"\"expirationDate\": 2547728505, " +
					"\"name\": \"dns-test.thecdn.example.com.\"," +
					"\"ttl\": \"60\", " +
					"\"status\": \"new\"," +
					"\"effectiveDate\": 1543408205," +
					"\"public\": " +
				"\"ZG5zLXRlc3QudGhlY2RuLmV4YW1wbGUuY29tLiBJTiBETlNLRVkgMjU3IDMgOCBBd0VBQVozU09rTjZ1bnVxYlM5ZGtDcnE4VFQyT1JTcmNrOHE3bVZDUEhtMmxYKzdBTHU3OURsOE9nVFEvTkxTd09iNk0wNmo3QW0wT0ROZElJVllqeGFuRXNqRWZ3c1RUUFg0MDhHc0NPa1BOeHNoclZMU0ZXUEJ3dXF6SW1VVElOT0MyVHByckMwNkswRzJCNFVhbG9CTElZTEsxOTRwT2VHK1FVQ0p4ZkJERWZjVEh0ZWdLOHlvc29MamNYZTM5L3k5RW5kV2YxV2JWZTE0RTNhTmdqeDJlcjlwNnl4MkJJZHVHOGJ6Y2dGS3AzbHFEdjFrZE9tU2ZTSXV0Rm50TzhPQkJ1M25DYisvWWtpNlE3TkROd1pTaHIxazdHTXFmOTZqbEZVNUhGYUdiN2xlNklYVXh3YWtzUWdrZFQ3THhYVUJoVjhlWWxNVFhsV3NsTzRQcXJoVXA2Yz0K\"," +
					"\"private\": " +
				"\"UHJpdmF0ZS1rZXktZm9ybWF0OiB2MS4yCkFsZ29yaXRobTogOCAoUlNBU0hBMjU2KQpNb2R1bHVzOiBuZEk2UTNxNmU2cHRMMTJRS3VyeE5QWTVGS3R5VHlydVpVSThlYmFWZjdzQXU3djBPWHc2Qk5EODB0TEE1dm96VHFQc0NiUTRNMTBnaFZpUEZxY1N5TVIvQ3hOTTlmalR3YXdJNlE4M0d5R3RVdElWWThIQzZyTWlaUk1nMDRMWk9tdXNMVG9yUWJZSGhScVdnRXNoZ3NyWDNpazU0YjVCUUluRjhFTVI5eE1lMTZBcnpLaXlndU54ZDdmMy9MMFNkMVovVlp0VjdYZ1RkbzJDUEhaNnYybnJMSFlFaDI0Ynh2TnlBVXFuZVdvTy9XUjA2Wko5SWk2MFdlMDd3NEVHN2VjSnY3OWlTTHBEczBNM0JsS0d2V1RzWXlwLzNxT1VWVGtjVm9adnVWN29oZFRIQnFTeENDUjFQc3ZGZFFHRlh4NWlVeE5lVmF5VTdnK3F1RlNucHc9PQpQdWJsaWNFeHBvbmVudDogQVFBQgpQcml2YXRlRXhwb25lbnQ6IGh4RzZUYkJHMDdvTFVpTllWSExZMXdQMzNFblRQaEEzRWJCN2c0dVJMVTFGbG1hSTRYNEJSY2Y2NlEvNGluWU4zVHNMczA1clh3SlA1Ky9nSG5vRTZKRExUaFpKb3FZL3pSeElUL1oycWlETGJ2dGYxUTJxblNXTXhVWjJySzdxN1VYamlKMmxFY3NSYW9oVDBCNzg0aXhxVGJlbzB4djZTcHJmTGY2bzdIUXh6bVlGZVMxaGdRbWhuZm1rTnp6dElpMEtjcDFWMG1Pa2NBcmhteFpkWTgvUHJsalpCL3RSMTZQdU1KbG16dG1aUE95Wm1Gbnk4c3RiYWt4VkppL0s3Uit0aHU1NHVTYU1vamxuV3FuVnh0U0QyM1VQaFQzZDFFVERyVGd1UlBQbXB4YU02aWxKYUdERkpPL3ZzenhEaE5YT1ZGU3E3M1FoVUpLeDEySncwUT09ClByaW1lMTogOGZieFJHY0ordEV1SDN5RHI5cTZqVkdySzF6K09ENlA5d2JOeEJLR0NUNndCM3RXNmNzUkpQOThIeEovSUEvenRtL2MrTnRHRVBmdDZLVDZLNjVCMkNKY3Z3WmZmcDZZOEpSekF3dzZEWWV0MHFpeGttZWZqYUVKZWtWSUxGUTZSdU9nbllCdUVNTFpaby93N3M4K0VXbUdyK0xMR3RyR3BMU2VNZmkyOHY4PQpQcmltZTI6IHB2bkx6TjU2NFpCaWRycXZFaVg1dC91Q1dSYlRNdzNQNEVnNThoWUZkR1FpbmVIUm53d2tzRUlleG5Id2VETExtWWZXcEsxZkNLck9aeUhiUEZ5OFdhY3ZEUkt5Q3AzeFRTcEhRSmk0L2xXcTdlVHFQaEpJV2YvYVVkeDJ1RW1kbzNwREFVQlZ2eDhZUlBkUjR6Szk3QnhkSHFHNTJ3WVltZzNhOTFPTzAxaz0KRXhwb25lbnQxOiBQdUxvZDllejMwMUlpSVJyRVdSdXdkWHMvOXN1YzEzSE92TzR2UEgzaGlXVnlJd0UzY1NhVXh4WG5SZklsSU93MnNTZUVNdWtuVHBpeWVrKzMrVnRWWWd3eExFYVZxVlBxSTljaVBrL2lVNnZIYVljYUttbjdUNWlZVFhxZVNMMjluK291ZWFzTkl6L3hjazVYRWZlb05YbFhJYzhOR0dSNlRMTVByNmVoZTg9CkV4cG9uZW50MjogZzQ4RkdDR2l4OTR1OWtVWWMwQWdoT2xSUmtoSmwwd21vUnZITEFwVnVlSzdzNUdjeTZlUnNKNG9DVXIwb0gvRkV1NklHNi9OMU5KZlZickROY2dMVHNmK3Rsb29sVnprSmx4TlQ0UUZIYjc1c2Y1TzRTRWVpR3FoNVNYREZHaE1IK1hRclVlM1I2S0VTTEprZnBJWU9kUVBPbmRLTEZ1ZFBxUDBCako3c2VFPQpDb2VmZmljaWVudDogald3L2phSmNJeVRtclhNc3J2L1NucmRjUHEzZmlpVkc1K3BZYmVYN1g1MFBMMVJtWE5VM2JVM09SdmlNZE9mbjdlR2dXaWkrNnFZQVdaZDBqbDJ6em5na1NMUnkzZytQNWpya0VyelNZVStzaTM1RklJUURObnU2YUlHUXJXS25oa29ESEhORzM0MXJFSytHRHZXbXg4dWpjTjVQTzk5ZUdYNEE4L0RaTFQ0PQo=\"" +
				"}" +
			"]";
		final String fedKskStr =
			"[" +
				"{" +
					"\"inceptionDate\": 1545136505," +
					"\"expirationDate\": 2547728505, " +
					"\"name\": \"federation-test.thecdn.example.com.\"," +
					"\"ttl\": \"60\", " +
					"\"status\": \"new\"," +
					"\"effectiveDate\": 1543408205," +
					"\"public\": " +
				"\"ZmVkZXJhdGlvbi10ZXN0LnRoZWNkbi5leGFtcGxlLmNvbS4gSU4gRE5TS0VZIDI1NyAzIDggQXdFQUFaRnVUakFqUWN6V3ZjVWtITlQvQXBOdGZIRVEwd2QwNElPNStmY2laK3VWV3ZwNjVZcm1RTWI5WXZBcnY3aUhSYzFweDRsbXVxK0VndHBQekZEN3N6VDk0cEdHWXhzcTR2RG5BWFBad1h3clBuUjJBWkZVS1owWDAwQlBXdWdXRDcvUHJuRXAxVTg1V3dhSFBsd3JiSWhlWW01L1E3aTRUY1BvT0tHVjl1SzJyQXlnbzJ5d2dhd0NBWFp2cUp4Smg2bTRwVWx4Ry9YdVUxM0NLSFVRRURJSHo5UnliT0ZHcTAzMFZqbU92UndaK21DVGFQbmFsZTZjUEhFU1hXNms1aTVBeDA5cXBwTElFRkYySy91YzlRdGZlMXpjdUxkNVRMZ0hWcVhMYVA5RkxMU1ZPbXRmVFM3SEQ5WG8wcTc2ZGFSQktjSWEzUWIvSjFoU1NIZEU3SXM9Cg==\"," +
					"\"private\":" +
				"\"UHJpdmF0ZS1rZXktZm9ybWF0OiB2MS4yCkFsZ29yaXRobTogOCAoUlNBU0hBMjU2KQpNb2R1bHVzOiBrVzVPTUNOQnpOYTl4U1FjMVA4Q2syMThjUkRUQjNUZ2c3bjU5eUpuNjVWYStucmxpdVpBeHYxaThDdS91SWRGelduSGlXYTZyNFNDMmsvTVVQdXpOUDNpa1laakd5cmk4T2NCYzluQmZDcytkSFlCa1ZRcG5SZlRRRTlhNkJZUHY4K3VjU25WVHpsYkJvYytYQ3RzaUY1aWJuOUR1TGhOdytnNG9aWDI0cmFzREtDamJMQ0JyQUlCZG0rb25FbUhxYmlsU1hFYjllNVRYY0lvZFJBUU1nZlAxSEpzNFVhclRmUldPWTY5SEJuNllKTm8rZHFWN3B3OGNSSmRicVRtTGtESFQycW1rc2dRVVhZcis1ejFDMTk3WE55NHQzbE11QWRXcGN0by8wVXN0SlU2YTE5TkxzY1AxZWpTcnZwMXBFRXB3aHJkQnY4bldGSklkMFRzaXc9PQpQdWJsaWNFeHBvbmVudDogQVFBQgpQcml2YXRlRXhwb25lbnQ6IGg0QzJXMFhPZmxRclZ5OHhxZ2U4MTY2d3Z3eUZBN0tUcWtpekxlQWg0YkEwcDZPd2tuMjlKMnRhTHhza05JUGR0dW56WUFPV3VBa0lmdTdSR1RlY0h5amJYT3BSRnpRYlpZaG5veERtcFpJSlRDdlRoQnhkOWFBSVZpaGFORnF4Nis5T3d1UE9lMVdlaVhPajErOGgzZUhMWnRjdk8wS0dPcDM1ZmgwamZ0SjdZVmU2N0tGdzJBT1lQK1dZalBFaDJpb2ZWT2NVNWorR3VYWkt3V1NHUmZzM0lRYlp5Y2Q0SHZ0YTZmbndBR2s3WGhrSkhsQ3lBeHNvMTBCNVdmZHJ5ellNZ29LV2lKOHRZUGljTVVGcVJOaHM2UElYUTI0Qk9ITjdxQmF3UTJ6QXJralB6L3B4aWRKRTc3OURHQ29zZWFxRURrdytVak93SDV5N2RzU0lDUT09ClByaW1lMTogeTVMT0ZkVXEvZjVSSFpWR3pob2ozYkF2MU5PWEJQYjk3TXZmYmF5eFNWVnJiaGJzQXFMY05XTUFCdC9mRHpMQ1FZWjBneW4zR1cxWXBENWlUOG9SZzM2V3AwZ3gwWGs4dU50NEpxalpNWE5ySDZtVWQ2T0Jydm81dzl3d0lSQWY1VDh6UWJPVkE0N1I5RHJHTjk3RlJqYkNBVW1pWFpmN3E4ZUJuczF1U2tVPQpQcmltZTI6IHR1SkdnM1lvTG0yeE9YVHQ4TEFsdWt0akJJOGhXMncxTUlXSDFuRDBHQ3ZMY2ZPSUVtQ0ZGMUt1TkluaDhqcXZ5TUNjekdyU3k0bGF5WHg0OUZDV0dFT3ZuQ1JBLzcrRGo0RVJrS1pNdkZPNmxHYWV3Mm0vSkZZVGZEblNweEpXSkJtSW9pVmJkYVhsQ2hjUjM2SkNEYUFKT1J4b29aVS9pSHN1MWd2U3NJOD0KRXhwb25lbnQxOiBWU2hSSzFManpDSlJubFZ1ckJMRlJCeEt0ZlhaSzh1Q2gwYjFiUFNicVBpaG13amRxM0NqTzNYeGNlNithYVlyR3F2N0cwODN2WncvUTEyUlZKMUwzRHpkR3BjWnQrM0dWL0grL2ZVTi9pQ3hCQ3ExSDZMM1FkSU16Z0RTNVZIUWRkNk5PNE82NXlVY2NOVVJUQmZWWUR6UnhTWWZWSldhUXM2UFMzWFdHQjA9CkV4cG9uZW50MjogTmV2emRIRlRHWlZZQ3FQU1FBUC9xN1RzaGZ5WmpqWVNYTE1TUVFUZXczMnVKM1B4YTlHdmpCZmhxelg0TzQ1WUkrMitqWHIxbWZOdXBEZWlCZzc0b2tEYXQwUHRNanJLVkhadXNtS0YvNFVFWHhyK3Rva29SVk5udlZuakpVVi94bmNNMVJvRXBHUjhhb1F3emVvdVpZd0pEQ0MzTE9VdmJWTThsUG01YmpzPQpDb2VmZmljaWVudDogTkR3ditiMnlhWnRpNVFGTXRtbFBjQmpXdW5kTWd5TXp3REs0MUUzajI0QzVoMzQ4TG55UFB2aDNTMlBjYTZpanpydTdaSVRPNjdRZFpWL04rcTdrZ0ZIbTIzZ3E0V1c4dkxqQnhWMEZFcEJCWWRZK0pvOUdjYUJxK29LekUyblFQdklMZkFka1VsMExZWkoxa1FpMTVweHZNbERJazBDempLNDNNMVphT2wwPQo=\"" +
				"}" +
			"]";

		final String dnstKpdStr =
			"{" +
				"\"response\": {" +
					"\"dns-test\": {" +
						"\"zsk\": " + dnstZskStr + "," +
						"\"ksk\": " + dnstKskStr +
					"}" +
				"}" +
			"}";

		final String dnsFedKpdStr =
			"{" +
				"\"response\": {" +
					"\"dns-test\": {" +
						"\"zsk\": " + dnstZskStr + "," +
						"\"ksk\": " + dnstKskStr +
					"}," +
					"\"federation-test\": {" +
						"\"zsk\": " + fedZskStr + "," +
						"\"ksk\": " + fedKskStr +
					"}" +
				"}" +
			"}";

		try{
			final ObjectMapper mapper = new ObjectMapper();
			if (returnKey == KeyProfile.TWO){
				return mapper.readTree(dnsFedKpdStr);
			} else {
				return mapper.readTree(dnstKpdStr);
			}
		} catch (IOException ioe){
			fail(ioe.getMessage());
			return null;
		}
	}

	@Override
	protected boolean isDnssecEnabled() {return true;}
}

@RunWith(PowerMockRunner.class)
@PrepareForTest({ConfigHandler.class, CacheRegister.class, ZoneManager.class,
		TrafficRouterManager.class, TrafficRouter.class })
public class SignatureManagerTest {
    ZoneManager zoneManager;
    SignatureManager signatureManager;
    CacheRegister cacheRegister;
	LoadingCache<ZoneKey, Zone> dynamicZoneCache;
	LoadingCache<ZoneKey, Zone> zoneCache;
	String baseDb = null;
	JsonNode baselineJo, updateJo, modDsJo = null;
	TrafficRouterManager trafficRouterManager;
	ConfigHandler configHandler = PowerMockito.spy(new ConfigHandler());
	StatTracker statTracker = new StatTracker();
	TrafficRouter trafficRouter = null;

	public static String DNS_TEST = "dns-test.thecdn.example.com.";
	public static String FED_TEST = "federation-test.thecdn.example.com.";
	public static String TLD = "thecdn.example.com.";

    @Before
    public void before() throws Exception {
    	try {
		    System.setProperty("dns.zones.dir", "src/test/var/auto-zones");
		    String resourcePath = "unit/DNSSecCrConfig.json";
		    InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
		    if (inputStream == null) {
			    fail("Could not find file '" + resourcePath + "' needed for test from the current classpath as a resource!");
		    }
		    baseDb = IOUtils.toString(inputStream);

		    resourcePath = "unit/DNSSecPlusCrConfig.json";
		    inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
		    if (inputStream == null) {
			    fail("Could not find file '" + resourcePath + "' needed for test from the current classpath as a resource!");
		    }
		    String newDsSnap = IOUtils.toString(inputStream);
		    resourcePath = "unit/DNSSecModDsCrConfig.json";
		    inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
		    if (inputStream == null) {
			    fail("Could not find file '" + resourcePath + "' needed for test from the current classpath as a resource!");
		    }
		    String modDsSnap = IOUtils.toString(inputStream);
		    final ObjectMapper mapper = new ObjectMapper();
		    assertThat(baseDb, notNullValue());
		    assertThat(newDsSnap, notNullValue());
		    assertThat(modDsSnap, notNullValue());

		    baselineJo = mapper.readTree(baseDb);
		    updateJo = mapper.readTree(newDsSnap);
		    modDsJo = mapper.readTree(modDsSnap);
		    assertThat(baselineJo, notNullValue());

		    GeolocationService geolocationService = new MaxmindGeolocationService();
		    AnonymousIpDatabaseService anonymousIpDatabaseService = new AnonymousIpDatabaseService();
		    FederationRegistry federationRegistry = new FederationRegistry();
		    TrafficOpsUtils trafficOpsUtils = new TrafficOpsUtils();
		    trafficRouterManager = new TrafficRouterManager();
		    trafficRouterManager.setAnonymousIpService(anonymousIpDatabaseService);
		    trafficRouterManager.setGeolocationService(geolocationService);
		    trafficRouterManager.setGeolocationService6(geolocationService);
		    trafficRouterManager.setFederationRegistry(federationRegistry);
		    trafficRouterManager.setTrafficOpsUtils(trafficOpsUtils);
		    trafficRouterManager.setNameServer(new NameServer());
		    configHandler.setTrafficRouterManager(trafficRouterManager);
		    configHandler.setStatTracker(statTracker);
		    configHandler.setFederationsWatcher(new FederationsWatcher());
		    configHandler.setSteeringWatcher(new SteeringWatcher());
		    SnapshotEventsProcessor snapshotEventsProcessor = SnapshotEventsProcessor.diffCrConfigs(baselineJo, null);
		    Map<String, DeliveryService> deliveryServiceMap = snapshotEventsProcessor.getChangeEvents();
		    ZoneManager.setZoneDirectory(new File("src/test/resources/unit/sigmantest"));
		    cacheRegister = PowerMockito.spy(new CacheRegister());
		    cacheRegister = fillCacheRegister(cacheRegister, deliveryServiceMap, null, baselineJo);
		    trafficRouter = PowerMockito.mock(TrafficRouter.class);
		    when(trafficRouter, "getCacheRegister").thenReturn(cacheRegister);
		    zoneManager = PowerMockito.spy(new ZoneManager(trafficRouter, statTracker));

		    // These three lines only allow the SignatureManager to be created once. Therefore each unit test is
		    // interacting with the same SignatureManager instance as the previous test.
		    signatureManager = new SigManagerForTesting(zoneManager,cacheRegister, trafficOpsUtils, trafficRouterManager);
		    whenNew(SignatureManager.class).withArguments(zoneManager, cacheRegister,
				    trafficOpsUtils, trafficRouterManager).thenReturn(signatureManager);
		    ///////////////
		    SigManagerForTesting.returnKey = SigManagerForTesting.KeyProfile.ONE;
		    Whitebox.invokeMethod(zoneManager, "initDnsRoutingNames", cacheRegister);
		    Whitebox.invokeMethod(zoneManager, "initTopLevelDomain", cacheRegister);
		    Whitebox.invokeMethod(zoneManager, "initSignatureManager", cacheRegister, trafficOpsUtils, trafficRouterManager);
		    Whitebox.invokeMethod( zoneManager, "initZoneCache", trafficRouter);
		    zoneCache = Whitebox.getInternalState(ZoneManager.class, "zoneCache");
		    dynamicZoneCache = Whitebox.getInternalState(ZoneManager.class, "dynamicZoneCache");
	    }
	    catch (Exception ex)
	    {
	    	ex.printStackTrace();
	    	fail(ex.getMessage());
	    }
    }

	private CacheRegister fillCacheRegister(CacheRegister cacheRegister,
	                                        Map<String, DeliveryService> deliveryServiceMap,
	                                        SnapshotEventsProcessor snapshotEventsProcessor, JsonNode snapshotJo) throws Exception{
		final JsonNode config = JsonUtils.getJsonNode(snapshotJo, ConfigHandler.CONFIG_KEY);
		final JsonNode stats = JsonUtils.getJsonNode(snapshotJo, "stats");
		cacheRegister.setTrafficRouters(JsonUtils.getJsonNode(snapshotJo, "contentRouters"));
		cacheRegister.setConfig(config);
		cacheRegister.setStats(stats);
		Whitebox.invokeMethod(configHandler, "parseCertificatesConfig", config);
		if (snapshotEventsProcessor == null){
			Whitebox.invokeMethod(configHandler, "parseDeliveryServiceMatchSets", deliveryServiceMap, cacheRegister);
			Whitebox.invokeMethod(configHandler, "parseLocationConfig", JsonUtils
							.getJsonNode(snapshotJo, "edgeLocations"), cacheRegister);
			Whitebox.invokeMethod(configHandler, "parseCacheConfig", JsonUtils.getJsonNode(snapshotJo,
					ConfigHandler.CONTENT_SERVERS_KEY), cacheRegister);
		} else {
			Whitebox.invokeMethod(configHandler, "parseDeliveryServiceMatchSets", snapshotEventsProcessor, cacheRegister);
			Whitebox.invokeMethod(configHandler, "parseLocationConfig", JsonUtils
							.getJsonNode(snapshotJo, "edgeLocations"), cacheRegister);
			Whitebox.invokeMethod(configHandler, "parseCacheConfig", snapshotEventsProcessor, cacheRegister);
		}
		Whitebox.invokeMethod(configHandler, "parseMonitorConfig", JsonUtils.getJsonNode(snapshotJo, "monitors"));
		return cacheRegister;
	}

	@Test
    public void verifyInitialState() {
	    //verify(zoneManager, never()).rebuildZoneCache();
	    //verify(zoneManager, never()).updateZoneCache(anyList());
	    Map<String, List<DnsSecKeyPair>> keyMap = signatureManager.getKeyMap();
	    assertThat("keyMap should not be null.", keyMap, notNullValue());
	    assertThat("Expected to only find a keys for dns-test but found these keys - " + keyMap.keySet(),
			    keyMap.keySet(), containsInAnyOrder(DNS_TEST));
    }

	@Test
	public void snapNewDNSDsNoNewKeys ()
	{
		try{
			SnapshotEventsProcessor snapshotEventsProcessor = SnapshotEventsProcessor.diffCrConfigs(updateJo,
					baselineJo);
			cacheRegister = fillCacheRegister(cacheRegister,null,snapshotEventsProcessor,updateJo);
			when(trafficRouter, "getCacheRegister").thenReturn(cacheRegister);
			when(zoneManager, "getTrafficRouter").thenReturn(trafficRouter);
			signatureManager.refreshKeyMap();
		} catch (Exception e) {
			fail(e.getMessage());
		}

		verify(zoneManager, never()).updateZoneCache(anyList());
		verify(zoneManager, never()).rebuildZoneCache();
		Map<String, List<DnsSecKeyPair>> keyMap = signatureManager.getKeyMap();
		assertThat("keyMap should not be null.", keyMap, notNullValue());
		assertThat("Expected to only find a keys for dns-test but found these keys - " +
						keyMap.keySet(), keyMap.keySet(),
				containsInAnyOrder(DNS_TEST));
	}

	@Test
	public void snapNewDNSDsAndNewKeys ()
	{
		Map<ZoneKey, Zone> cacheOut = null;
		try{
			SnapshotEventsProcessor snapshotEventsProcessor = SnapshotEventsProcessor.diffCrConfigs(updateJo,
					baselineJo);
			cacheRegister = fillCacheRegister(cacheRegister,null, snapshotEventsProcessor, updateJo);
			SigManagerForTesting.returnKey = SigManagerForTesting.KeyProfile.TWO;
			when(trafficRouter, "getCacheRegister").thenReturn(cacheRegister);
			when(zoneManager, "getTrafficRouter").thenReturn(trafficRouter);
			signatureManager.refreshKeyMap();
			zoneCache = Whitebox.getInternalState(ZoneManager.class, "zoneCache");
			dynamicZoneCache = Whitebox.getInternalState(ZoneManager.class, "dynamicZoneCache");
			cacheOut = zoneCache.asMap();
		} catch (Exception e) {
			fail(e.getMessage());
		}

		verify(zoneManager, atLeastOnce()).updateZoneCache(anyList());
		Map<String, List<DnsSecKeyPair>> keyMap = signatureManager.getKeyMap();
		assertThat("keyMap should not be null.", keyMap, notNullValue());
		assertThat("Expected to only find a keys for dns-test and federation-test but found these keys - " +
						keyMap.keySet(), keyMap.keySet(),
				containsInAnyOrder(DNS_TEST, FED_TEST));

		final StringBuilder keysStr = new StringBuilder("");
		cacheOut.keySet().forEach(zoneKey -> keysStr.append(zoneKey.getName()+" "));
		assertThat("Expected a zone for 'dns-test.thecdn.example.com.' in :"+keysStr.toString(),
				keysStr.toString(), containsString(DNS_TEST));
		assertThat("Expected a zone for 'federation-test.thecdn.example.com.' in :"+keysStr.toString(),
				keysStr.toString(), containsString(FED_TEST));
		Zone dnsTestZone = null;
		Zone fedTestZone = null;
		Zone tldTestZone = null;
		for ( ZoneKey key : cacheOut.keySet()) {
			if (key.getName().toString().equals(DNS_TEST)) {
				dnsTestZone = cacheOut.get(key);
			} else if (key.getName().toString().equals(FED_TEST)){
				fedTestZone = cacheOut.get(key);
			} else if (key.getName().toString().equals(TLD)) {
				tldTestZone = cacheOut.get(key);
			}

			if (!(dnsTestZone == null || fedTestZone == null || tldTestZone == null)) {
				break;
			}
		}

		assertThat("Check DNSSec Key for "+DNS_TEST, hasNSec(dnsTestZone, DNS_TEST, Type.DNSKEY),
				notNullValue());
		assertThat("Check NSEC Key for "+DNS_TEST, hasNSec(dnsTestZone, DNS_TEST, Type.NSEC),
				notNullValue());
		assertThat("Check DNSSec Key for "+FED_TEST, hasNSec(fedTestZone, FED_TEST, Type.DNSKEY), notNullValue());
		assertThat("Check NSEC Key for "+FED_TEST, hasNSec(fedTestZone, FED_TEST, Type.NSEC), notNullValue());
		assertThat("Check DS record for "+DNS_TEST, hasNSec(tldTestZone, DNS_TEST, Type.DS), notNullValue());
		assertThat("Check DS record for "+FED_TEST, hasNSec(tldTestZone, FED_TEST, Type.DS), notNullValue());
	}

    @Test
    public void snapModDsAndSameKeys ()
    {
    	Map<ZoneKey, Zone> cacheOut = null;
    	try{
		    SnapshotEventsProcessor snapshotEventsProcessor = SnapshotEventsProcessor.diffCrConfigs(modDsJo,
				    baselineJo);
		    cacheRegister = fillCacheRegister(cacheRegister,null, snapshotEventsProcessor, modDsJo);
		    signatureManager.getKeyMap().remove(FED_TEST);
		    SigManagerForTesting.returnKey = SigManagerForTesting.KeyProfile.TWO;
		    when(trafficRouter, "getCacheRegister").thenReturn(cacheRegister);
		    when(zoneManager, "getTrafficRouter").thenReturn(trafficRouter);
		    signatureManager.refreshKeyMap();
		    zoneCache = Whitebox.getInternalState(ZoneManager.class, "zoneCache");
		    cacheOut = zoneCache.asMap();
	    } catch (Exception e) {
    		fail(e.getMessage());
	    }

	    verify(zoneManager, atLeastOnce()).updateZoneCache(anyList());
	    Map<String, List<DnsSecKeyPair>> keyMap = signatureManager.getKeyMap();
	    assertThat("keyMap should not be null.", keyMap, notNullValue());
	    assertThat("Expected to only find a keys for dns-test and federation-test but found these keys - " +
		    keyMap.keySet(), keyMap.keySet(),
	    containsInAnyOrder(DNS_TEST, FED_TEST));

	    final StringBuilder keysStr = new StringBuilder("");
	    cacheOut.keySet().forEach(zoneKey -> keysStr.append(zoneKey.getName()+" "));
    	assertThat("Expected a zone for 'dns-test.thecdn.example.com.' in :"+keysStr.toString(),
			    keysStr.toString(), containsString(DNS_TEST));
	    assertThat("Expected a zone for 'federation-test.thecdn.example.com.' in :"+keysStr.toString(),
    	        keysStr.toString(), containsString(FED_TEST));
	    Zone dnsTestZone = null;
	    Zone fedTestZone = null;
	    Zone tldTestZone = null;
	    for ( ZoneKey key : cacheOut.keySet()) {
	    	if (key.getName().toString().equals(DNS_TEST)) {
	    		dnsTestZone = cacheOut.get(key);
		    } else if (key.getName().toString().equals(FED_TEST)){
			    fedTestZone = cacheOut.get(key);
		    } else if (key.getName().toString().equals(TLD)) {
	    		tldTestZone = cacheOut.get(key);
		    }

		    if (!(dnsTestZone == null || fedTestZone == null || tldTestZone == null)) {
		    	break;
		    }
	    }

	    assertThat("Check DNSSec Key for "+DNS_TEST, hasNSec(dnsTestZone, DNS_TEST, Type.DNSKEY),
			    notNullValue());
	    assertThat("Check NSEC Key for "+DNS_TEST, hasNSec(dnsTestZone, DNS_TEST, Type.NSEC),
			    notNullValue());
	    assertThat("Check DNSSec Key for "+FED_TEST, hasNSec(fedTestZone, FED_TEST, Type.DNSKEY), notNullValue());
	    assertThat("Check NSEC Key for "+FED_TEST, hasNSec(fedTestZone, FED_TEST, Type.NSEC), notNullValue());
	    assertThat("Check DS record for "+DNS_TEST, hasNSec(tldTestZone, DNS_TEST, Type.DS), notNullValue());
	    assertThat("Check DS record for "+FED_TEST, hasNSec(tldTestZone, FED_TEST, Type.DS), notNullValue());
    }

    public Record hasNSec(final Zone srcZone, final String hostname, final int type) {
    	Iterator<RRset> rRsetIterator = srcZone.iterator();
	    while (rRsetIterator.hasNext()) {
	    	RRset recordSet = rRsetIterator.next();
	    	Iterator<Record> recordIterator = recordSet.rrs();
	    	while (recordIterator.hasNext()) {
			    Record record = recordIterator.next();
			    if (record.getType() == type && record.rdataToString() != null) {
				    return record;
			    }
		    }
	    }
	    return null;
    }

}

// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.demo.cmschat.service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.liferay.demo.cmschat.http.LiferayClient;

/**
 * Resolves Display Page URLs dynamically by querying the Liferay site and
 * object-definition APIs, mirroring the approach used in the forums
 * microservice.  Results are cached for the lifetime of the application
 * since site friendly-URL paths and object-definition separators rarely
 * change.
 *
 * <p>Supports both custom Object types (actions URLs containing
 * {@code /o/c/}) and standard Liferay content types (actions URLs
 * containing {@code /o/headless-delivery/}).</p>
 *
 * @author Neil Griffin
 */
@Service
public class DisplayPageUrlService {

	private static final Logger log = LoggerFactory.getLogger(DisplayPageUrlService.class);

	private static final ObjectMapper _objectMapper = new ObjectMapper();

	/** Pattern to extract the REST context path from a custom object or CMS object actions URL.
	 *  Matches both {@code /o/c/pressreleases/12345} and {@code /o/cms/blogs/12345}. */
	private static final Pattern _OBJECT_REST_PATH_PATTERN = Pattern.compile("/o/(?:c|cms)/([^/]+)");

	/** Pattern to extract the content type from a headless-delivery actions URL like {@code /o/headless-delivery/v1.0/structured-contents/12345}. */
	private static final Pattern _HEADLESS_DELIVERY_PATTERN = Pattern.compile(
		"/o/headless-delivery/v[^/]+/(?:sites/[^/]+/)?([^/]+)");

	/**
	 * Standard Liferay content type separators used in Display Page URLs.
	 * These are Liferay DXP platform conventions for built-in content types.
	 */
	private static final Map<String, String> _STANDARD_SEPARATORS = Map.of(
		"structured-contents", "w",
		"blog-postings", "b",
		"documents", "d"
	);

	// --- Object Definitions Cache ---

	/** Tracks whether the full object-definition scan has completed successfully. */
	private final AtomicBoolean _objectDefinitionsLoaded = new AtomicBoolean(false);

	/** Tracks whether the JWT fallback load for object definitions has been attempted. */
	private final AtomicBoolean _objectDefinitionsJwtAttempted = new AtomicBoolean(false);

	/** Cache: REST path key (e.g. "forumreplies") → friendlyURLSeparator (e.g. "c_forumreply"). */
	private final Map<String, String> _separatorCache = new ConcurrentHashMap<>();

	// --- Sites Cache ---

	/** Tracks whether the initial site scan has completed successfully. */
	private final AtomicBoolean _sitesLoaded = new AtomicBoolean(false);

	/** Tracks whether the JWT fallback load for sites has been attempted. */
	private final AtomicBoolean _sitesJwtAttempted = new AtomicBoolean(false);

	/**
	 * Cache: site identifier → friendlyUrlPath.  Keyed by both the site
	 * {@code name} (which matches the {@code scopeKey} field on custom
	 * object search results) and the site {@code id} (which matches the
	 * {@code siteId} field on standard content search results).
	 */
	private final Map<String, String> _siteCache = new ConcurrentHashMap<>();

	// --- Asset Library Cache ---

	/** Tracks whether the initial asset library scan has completed successfully. */
	private final AtomicBoolean _assetLibrariesLoaded = new AtomicBoolean(false);

	/** Tracks whether the JWT fallback load for asset libraries has been attempted. */
	private final AtomicBoolean _assetLibrariesJwtAttempted = new AtomicBoolean(false);

	/**
	 * Cache: Asset Library identifier → Asset Library group id (used in the
	 * {@code asset-library-{id}} URL segment).  Keyed by both the
	 * {@code name}/{@code assetLibraryKey} (which matches the {@code scopeKey}
	 * field on CMS Object search results) and the numeric {@code id}.
	 */
	private final Map<String, String> _assetLibraryCache = new ConcurrentHashMap<>();

	@Autowired
	private LiferayClient _liferayClient;

	@PostConstruct
	public void init() {
		try {
			_loadAllObjectDefinitions(null);
		}
		catch (Exception e) {
			log.warn(
				"Failed to load object definitions at startup (will retry on first request): {}",
				e.getMessage());
		}

		try {
			_loadAllSites(null);
		}
		catch (Exception e) {
			log.warn(
				"Failed to load sites at startup (will retry on first request): {}",
				e.getMessage());
		}

		try {
			_loadAllAssetLibraries(null);
		}
		catch (Exception e) {
			log.warn(
				"Failed to load asset libraries at startup (will retry on first request): {}",
				e.getMessage());
		}
	}

	/**
	 * Resolves a Display Page URL from the embedded JSON node of a search
	 * result.  Handles both custom Object types and standard Liferay content
	 * types.
	 *
	 * @param embeddedJsonNode the embedded headless response from the search result
	 * @param authToken        the OAuth2 bearer token
	 * @return the resolved Display Page URL path (e.g. {@code /web/sales-portal/press-release/my-article}),
	 *         or {@code null} if resolution fails
	 */
	public String resolveUrl(JsonNode embeddedJsonNode, String authToken, String siteKey) {
		try {
			JsonNode actionsNode = embeddedJsonNode.path("actions");

			// Try custom object resolution first (/o/c/ and /o/cms/ URLs)

			String objectRestPath = _extractObjectRestPath(actionsNode);

			if (objectRestPath != null) {
				return _resolveCustomObjectUrl(embeddedJsonNode, actionsNode, objectRestPath, authToken, siteKey);
			}

			// Fall back to standard content type resolution (/o/headless-delivery/ URLs)

			String contentType = _extractHeadlessDeliveryType(actionsNode);

			if (contentType != null) {
				return _resolveStandardTypeUrl(embeddedJsonNode, contentType, authToken);
			}

			log.debug("Could not determine content type from actions URLs");

			return null;
		}
		catch (Exception e) {
			log.warn("Failed to resolve Display Page URL: {}", e.getMessage());

			return null;
		}
	}

	// --- URL Building ---

	private String _buildUrl(JsonNode embeddedJsonNode, String scopeKey, String separator, String authToken) {
		String sitePath = _resolveSiteFriendlyUrl(scopeKey, authToken);

		if (sitePath == null) {
			return null;
		}

		String entryFriendlyUrl = embeddedJsonNode.path("friendlyUrlPath").asText("");

		String scopeType = embeddedJsonNode.path("systemProperties").path("scope").path("type").asText("");

		if ("AssetLibrary".equals(scopeType)) {

			// Resolve the Asset Library group id from the cache using the Asset Library name
			// from the embedded JSON's scopeKey (e.g. "Corporate"). The scopeKey parameter
			// passed to this method is the RENDERING site identifier (e.g. "44217"), which
			// is correct for _resolveSiteFriendlyUrl above but cannot be used here.
			// The asset library cache is keyed by Asset Library name, not rendering site id.

			String assetLibraryScopeKey = embeddedJsonNode.path("scopeKey").asText("");
			String assetLibraryGroupId = _resolveAssetLibraryGroupId(assetLibraryScopeKey, authToken);

			String urlTitle = entryFriendlyUrl.isBlank() ? embeddedJsonNode.path("id").asText("") : entryFriendlyUrl;

			if (assetLibraryGroupId != null && !urlTitle.isBlank()) {
				entryFriendlyUrl = "asset-library-" + assetLibraryGroupId + "/" + urlTitle;
			}
		}

		if (entryFriendlyUrl.isBlank()) {
			String id = embeddedJsonNode.path("id").asText("");

			if (id.isBlank()) {
				log.debug("No friendlyUrlPath or id available for URL resolution");

				return null;
			}

			entryFriendlyUrl = id;
		}

		String url = "/web" + sitePath + "/" + separator + "/" + entryFriendlyUrl;

		log.debug("Resolved Display Page URL: {}", url);

		return url;
	}

	// --- Content Type Extraction ---

	private String _extractHeadlessDeliveryType(JsonNode actionsNode) {
		if (actionsNode.isObject()) {
			java.util.Iterator<JsonNode> elements = actionsNode.elements();

			while (elements.hasNext()) {
				JsonNode actionNode = elements.next();
				String href = actionNode.path("href").asText("");

				if (!href.isBlank()) {
					Matcher matcher = _HEADLESS_DELIVERY_PATTERN.matcher(href);

					if (matcher.find()) {
						return matcher.group(1);
					}
				}
			}
		}

		return null;
	}

	private String _extractObjectRestPath(JsonNode actionsNode) {
		if (actionsNode.isObject()) {
			java.util.Iterator<JsonNode> elements = actionsNode.elements();

			while (elements.hasNext()) {
				JsonNode actionNode = elements.next();
				String href = actionNode.path("href").asText("");

				if (!href.isBlank()) {
					Matcher matcher = _OBJECT_REST_PATH_PATTERN.matcher(href);

					if (matcher.find()) {
						return matcher.group(1);
					}
				}
			}
		}

		return null;
	}

	// --- Custom Object URL Resolution ---

	private String _resolveCustomObjectUrl(
		JsonNode embeddedJsonNode, JsonNode actionsNode, String objectRestPath, String authToken, String siteKey) {

		String scopeType = embeddedJsonNode.path("systemProperties").path("scope").path("type").asText("");
		String scopeKey = embeddedJsonNode.path("scopeKey").asText("");

		if (scopeKey.isBlank()) {
			log.debug("Could not extract scopeKey from embedded JSON");

			return null;
		}

		// For Asset Library content, the scopeKey is the Asset Library name (e.g. "Corporate"),
		// which is not in the site cache. The caller-supplied siteKey is the reliable rendering
		// site (passed from the fragment's Liferay.ThemeDisplay context). Fall back to the
		// heuristic only when no siteKey is provided.

		String siteScopeKey;

		if ("AssetLibrary".equals(scopeType)) {
			if (siteKey != null && !siteKey.isBlank()) {
				siteScopeKey = siteKey;
			}
			else {
				siteScopeKey = _resolveRenderingSiteKey(authToken);
			}
		}
		else {
			siteScopeKey = scopeKey;
		}

		if (siteScopeKey == null) {
			log.debug("Could not determine rendering site for Asset Library scopeKey '{}'", scopeKey);

			return null;
		}

		String separator = _resolveCustomObjectSeparator(objectRestPath, authToken);

		if (separator == null) {
			return null;
		}

		return _buildUrl(embeddedJsonNode, siteScopeKey, separator, authToken);
	}

	private String _resolveCustomObjectSeparator(String objectRestPath, String authToken) {
		String cached = _separatorCache.get(objectRestPath);

		if (cached != null) {
			return cached;
		}

		// Fallback: startup load may have failed, retry once with the request's JWT

		if (!_objectDefinitionsLoaded.get() && !_objectDefinitionsJwtAttempted.get()) {
			_loadAllObjectDefinitions(authToken);
		}

		return _separatorCache.get(objectRestPath);
	}

	/**
	 * Resolves the Asset Library group id (the numeric id used in the
	 * {@code asset-library-{id}} URL segment) from the cache, using the
	 * Asset Library name as the key.  Retries once if the cache is not yet
	 * populated.
	 */
	private String _resolveAssetLibraryGroupId(String scopeKey, String authToken) {
		String key = (scopeKey != null) ? scopeKey.toLowerCase() : "";
		String cached = _assetLibraryCache.get(key);

		if (cached != null) {
			return cached;
		}

		if (!_assetLibrariesLoaded.get() && !_assetLibrariesJwtAttempted.get()) {
			_loadAllAssetLibraries(authToken);
		}

		return _assetLibraryCache.get(key);
	}

	/**
	 * Resolves the scope key of the first real (non-Global) site to use as the
	 * base URL for Asset Library content.  Asset Library content is rendered
	 * in the context of a site that has connected the library, so any connected
	 * site is a reasonable default.
	 */
	private String _resolveRenderingSiteKey(String authToken) {
		if (!_sitesLoaded.get()) {
			_reloadSites(authToken);
		}

		// Return the first site that isn't Global or Guest (those are platform-level)
		for (Map.Entry<String, String> entry : _siteCache.entrySet()) {
			String name = entry.getKey();

			if (!name.equals("global") && !name.equals("guest") && !name.matches("\\d+")) {
				return name;
			}
		}

		return null;
	}

	// --- Standard Content Type URL Resolution ---

	private String _resolveStandardTypeUrl(
		JsonNode embeddedJsonNode, String contentType, String authToken) {

		String separator = _STANDARD_SEPARATORS.get(contentType);

		if (separator == null) {
			log.debug("No known separator for standard content type '{}'", contentType);

			return null;
		}

		// Standard types carry siteId directly in the embedded JSON

		String siteId = embeddedJsonNode.path("siteId").asText("");

		if (siteId.isBlank()) {
			log.debug("No siteId found in embedded JSON for standard type '{}'", contentType);

			return null;
		}

		return _buildUrl(embeddedJsonNode, siteId, separator, authToken);
	}

	// --- Site Resolution ---

	/**
	 * Resolves a site's {@code friendlyUrlPath} from a scope key (site name)
	 * or a numeric site ID.  On cache miss, the full sites registry is
	 * reloaded from the paginated Liferay API to pick up newly added sites.
	 */
	private String _resolveSiteFriendlyUrl(String scopeKey, String authToken) {
		String key = (scopeKey != null) ? scopeKey.toLowerCase() : "";
		String cached = _siteCache.get(key);

		if (cached != null) {
			return cached;
		}

		// Cache miss — reload sites (a new site may have been added)

		_reloadSites(authToken);

		return _siteCache.get(key);
	}

	private synchronized void _reloadSites(String authToken) {
		if (authToken != null) {

			// Request-time reload with JWT — always allowed (new sites)

			_loadAllSites(authToken);
		}
		else if (!_sitesLoaded.get() && !_sitesJwtAttempted.get()) {

			// Startup retry only if never succeeded and JWT not yet tried

			_loadAllSites(null);
		}
	}

	private synchronized void _loadAllSites(String authToken) {
		try {
			int page = 1;
			int loadedCount = 0;

			while (true) {
				String url = UriComponentsBuilder
					.fromUriString("/headless-admin-site/v1.0/sites")
					.queryParam("fields", "name,friendlyUrlPath,id")
					.queryParam("page", page)
					.queryParam("pageSize", 100)
					.toUriString();

				String response = (authToken != null)
					? _liferayClient.get(url, authToken)
					: _liferayClient.getWithBasicAuth(url);

				JsonNode root = _objectMapper.readTree(response);
				JsonNode items = root.path("items");

				if (!items.isArray() || (items.size() == 0)) {
					break;
				}

				for (JsonNode item : items) {
					String name = item.path("name").asText("");
					String friendlyUrlPath = item.path("friendlyUrlPath").asText("");
					String id = item.path("id").asText("");

					if (!friendlyUrlPath.isBlank()) {

						// Key by name (matches scopeKey from custom objects)
						// Lowercase to ensure case-insensitive matching

						if (!name.isBlank()) {
							_siteCache.put(name.toLowerCase(), friendlyUrlPath);
						}

						// Key by numeric id (matches siteId from standard content)

						if (!id.isBlank()) {
							_siteCache.put(id, friendlyUrlPath);
						}

						loadedCount++;

						log.debug("Cached site '{}' (id={}) → '{}'", name, id, friendlyUrlPath);
					}
				}

				int lastPage = root.path("lastPage").asInt(1);

				if (page >= lastPage) {
					break;
				}

				page++;
			}

			_sitesLoaded.set(true);

			if (authToken != null) {
				_sitesJwtAttempted.set(true);
			}

			log.info("Loaded {} sites into cache", loadedCount);
		}
		catch (IOException e) {
			if (authToken != null) {
				_sitesJwtAttempted.set(true);
			}

			log.warn("Failed to load sites: {}", e.getMessage());
		}
	}

	// --- Asset Library Loading ---

	private synchronized void _loadAllAssetLibraries(String authToken) {
		if (_assetLibrariesLoaded.get()) {
			return;
		}

		if ((authToken != null) && !_assetLibrariesJwtAttempted.compareAndSet(false, true)) {
			return;
		}

		try {
			int page = 1;
			int loadedCount = 0;

			while (true) {
				String url = UriComponentsBuilder
					.fromUriString("/headless-asset-library/v1.0/asset-libraries")
					.queryParam("fields", "id,name,assetLibraryKey")
					.queryParam("page", page)
					.queryParam("pageSize", 100)
					.toUriString();

				String response = (authToken != null)
					? _liferayClient.get(url, authToken)
					: _liferayClient.getWithBasicAuth(url);

				JsonNode root = _objectMapper.readTree(response);
				JsonNode items = root.path("items");

				if (!items.isArray() || (items.size() == 0)) {
					break;
				}

				for (JsonNode item : items) {
					String id = item.path("id").asText("");
					String name = item.path("name").asText("");
					String assetLibraryKey = item.path("assetLibraryKey").asText("");

					if (!id.isBlank()) {

						// Key by lowercase name (matches scopeKey from CMS Object search results)

						if (!name.isBlank()) {
							_assetLibraryCache.put(name.toLowerCase(), id);
						}

						// Key by assetLibraryKey (may differ from name in some configurations)

						if (!assetLibraryKey.isBlank() && !assetLibraryKey.equalsIgnoreCase(name)) {
							_assetLibraryCache.put(assetLibraryKey.toLowerCase(), id);
						}

						// Key by numeric id for direct lookups

						_assetLibraryCache.put(id, id);

						loadedCount++;

						log.debug("Cached asset library '{}' (key='{}') → id={}", name, assetLibraryKey, id);
					}
				}

				int lastPage = root.path("lastPage").asInt(1);

				if (page >= lastPage) {
					break;
				}

				page++;
			}

			_assetLibrariesLoaded.set(true);

			log.info("Loaded {} asset libraries into cache", loadedCount);
		}
		catch (IOException e) {
			log.warn("Failed to load asset libraries: {}", e.getMessage());
		}
	}

	// --- Object Definitions Loading ---

	private synchronized void _loadAllObjectDefinitions(String authToken) {
		if (_objectDefinitionsLoaded.get()) {
			return;
		}

		// If called with JWT, only allow one attempt

		if ((authToken != null) && !_objectDefinitionsJwtAttempted.compareAndSet(false, true)) {
			return;
		}

		try {
			int page = 1;

			while (true) {
				String url = UriComponentsBuilder
					.fromUriString("/object-admin/v1.0/object-definitions")
					.queryParam("fields", "restContextPath,friendlyURLSeparator")
					.queryParam("page", page)
					.queryParam("pageSize", 100)
					.toUriString();

				String response = (authToken != null)
					? _liferayClient.get(url, authToken)
					: _liferayClient.getWithBasicAuth(url);

				JsonNode root = _objectMapper.readTree(response);
				JsonNode items = root.path("items");

				if (!items.isArray() || (items.size() == 0)) {
					break;
				}

				for (JsonNode item : items) {
					String restContextPath = item.path("restContextPath").asText("");
					String separator = item.path("friendlyURLSeparator").asText("");

					if (separator.isBlank()) {
						continue;
					}

					String key = null;

					if (restContextPath.startsWith("/o/c/")) {
						key = restContextPath.substring("/o/c/".length());
					}
					else if (restContextPath.startsWith("/o/cms/")) {
						key = restContextPath.substring("/o/cms/".length());
					}

					if (key != null) {
						_separatorCache.putIfAbsent(key, separator);

						log.debug("Cached separator for '{}': '{}'", key, separator);
					}
				}

				int lastPage = root.path("lastPage").asInt(1);

				if (page >= lastPage) {
					break;
				}

				page++;
			}

			_objectDefinitionsLoaded.set(true);

			log.info("Loaded {} custom object definition separators", _separatorCache.size());
		}
		catch (IOException e) {
			log.warn("Failed to load object definitions: {}", e.getMessage());
		}
	}

}

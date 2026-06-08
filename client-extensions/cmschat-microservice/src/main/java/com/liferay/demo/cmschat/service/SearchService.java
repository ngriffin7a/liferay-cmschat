// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.demo.cmschat.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.exception.ZeroByteFileException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.liferay.demo.cmschat.config.SearchMappingProperties;
import com.liferay.demo.cmschat.dto.SearchResult;
import com.liferay.demo.cmschat.http.LiferayClient;

import org.xml.sax.SAXException;

/**
 * @author Neil Griffin
 */
@Service
public class SearchService {

	private static final Logger log = LoggerFactory.getLogger(SearchService.class);

	private static final Tika _tika = new Tika();

	private static final int MAX_CONTEXT_CHARS = 80_000;
	private static final int MAX_RESULTS = 3;

	private static final Set<String> _SUMMARY_STOP_WORDS = Set.of(
		"a", "an", "the", "and", "or", "of", "in", "on", "at", "to", "for",
		"is", "are", "was", "were", "be", "been", "by", "as", "from", "with",
		"about", "summarize", "summarizing", "titled", "called", "named");

	private static final ObjectMapper _objectMapper = new ObjectMapper();

	@Autowired
	private DisplayPageUrlService _displayPageUrlService;

	@Autowired
	private LiferayClient _liferayClient;

	@Value("${liferay.base.url}")
	private String _liferayBaseURL;

	@Value("${liferay.headless.api.base.url}")
	private String _liferayApiBaseURL;

	private List<SearchMappingProperties.Mapping> _mappings;

	private final Map<String, StrategyHandler> _strategyHandlers = new HashMap<>();

	@Autowired
	public void setSearchMappingProperties(SearchMappingProperties searchMappingProperties) {
		_mappings = searchMappingProperties.getMappings();
		_mappings.sort(Comparator.comparingInt(SearchMappingProperties.Mapping::getMatchFieldDisambiguationOrder));
	}

	@PostConstruct
	public void init() {
		_strategyHandlers.put("CONTENT_FIELDS", this::_handleContentFields);
		_strategyHandlers.put("HTML", this::_handleHtml);
		_strategyHandlers.put("DOCUMENT", this::_handleDocument);
		_strategyHandlers.put("CMS2_DOCUMENT", this::_handleCms2Document);
	}

	public List<SearchResult> getSearchResults(
		Jwt jwt, String keywords, String blueprintExternalReferenceCode, String siteKey)
		throws IOException {

		log.debug("keywords={}", keywords);

		boolean limitToFirstResult = keywords.toLowerCase().startsWith("summarize the");

		String searchKeywords = keywords;

		if (limitToFirstResult) {
			int titledIdx = keywords.toLowerCase().indexOf(" titled ");

			if (titledIdx != -1) {
				String extractedTitle = keywords.substring(titledIdx + " titled ".length()).trim();
				searchKeywords = "\"" + extractedTitle + "\"";
				log.debug("Phrase search for 'titled' clause: {}", searchKeywords);
			}
		}

		String url = UriComponentsBuilder
			.fromUriString("/search/v1.0/search")
			.queryParam("blueprintExternalReferenceCode", blueprintExternalReferenceCode)
			.queryParam("nestedFields", "embedded")
			.queryParam("search", searchKeywords)
			.toUriString();

		log.debug("url={}", url);

		return _parseSearchResults(
			jwt, _liferayClient.get(url, jwt.getTokenValue()), limitToFirstResult, siteKey, keywords);
	}

	private List<SearchResult> _parseSearchResults(
		Jwt jwt, String json, boolean limitToFirstResult, String siteKey, String keywords)
		throws IOException {

		List<SearchResult> searchResults = new ArrayList<>();

		try {
			JsonNode rootNode = _objectMapper.readTree(json);
			JsonNode itemsJsonNode = rootNode.path("items");

			if (!itemsJsonNode.isArray()) {
				log.debug("No search results found");
				return searchResults;
			}

			int remainingChars = MAX_CONTEXT_CHARS;

			for (JsonNode itemJsonNode : itemsJsonNode) {
				int totalResults = searchResults.size();

				if ((totalResults == MAX_RESULTS) || (remainingChars <= 1024)) {
					break;
				}

				String title = itemJsonNode.path("title").asText();
				String description = itemJsonNode.path("description").asText();
				String itemURL = itemJsonNode.path("itemURL").asText();

				if (itemURL.isBlank()) {
					itemURL = _liferayBaseURL;
				}

				JsonNode embeddedJsonNode = itemJsonNode.path("embedded");
				String contentText = null;
				String contentURL = null;
				boolean addSearchResult = false;

				SearchMappingProperties.Mapping mapping = _findMapping(itemJsonNode, embeddedJsonNode);

				if ((mapping != null) && (mapping.getStrategy() != null)) {
					StrategyHandler handler = _strategyHandlers.get(mapping.getStrategy());

					if (handler != null) {
						StrategyResult strategyResult = handler.handle(
							jwt, mapping, itemJsonNode, embeddedJsonNode, itemURL, title, description, remainingChars, siteKey);

						if (strategyResult != null) {
							contentText = strategyResult.contentText();
							contentURL = strategyResult.contentURL();
							addSearchResult = true;
						}
					}
				}

				if (addSearchResult) {
					log.debug("Search result: type=\"{}\" title=\"{}\"", mapping.getType(), title);
					searchResults.add(new SearchResult(title, description, itemURL, contentText, contentURL, mapping.getType(), mapping.getTitle()));
					remainingChars -= (contentText != null ? contentText.length() : 0);
				}
			}
		}
		catch (JsonProcessingException e) {
			throw new IOException(e);
		}

		if (limitToFirstResult && (searchResults.size() > 1)) {
			SearchResult best = _findBestTitleMatch(searchResults, keywords);
			log.debug("Summarize mode: selected \"{}\" from {} candidates", best.title(), searchResults.size());
			return List.of(best);
		}

		log.debug("Returning {} search results", searchResults.size());

		return searchResults;
	}

	private DownloadedDoc _downloadDocument(Jwt jwt, String contentURL, int maxChars) throws IOException {
		if (contentURL == null) {
			return new DownloadedDoc(null, null);
		}

		if (contentURL.contains("/o/headless-delivery/v1.0/documents")) {
			String fileEntryId = contentURL.substring(contentURL.lastIndexOf('/') + 1);
			contentURL = contentURL.replaceAll(
				"/o/headless-delivery/v1.0/documents/\\d+",
				"/c/document_library/get_file?fileEntryId=" + fileEntryId);
		}

		log.debug("Downloading document: {}", contentURL);

		LiferayClient.DownloadResult result = _liferayClient.download(contentURL, jwt.getTokenValue());

		return new DownloadedDoc(_extractText(result, maxChars), contentURL);
	}

	private String _extractText(LiferayClient.DownloadResult result, int maxChars) throws IOException {
		if (result.isPlainText()) {
			return result.asString();
		}

		try (InputStream stream = new ByteArrayInputStream(result.body())) {
			AutoDetectParser parser = new AutoDetectParser();
			BodyContentHandler handler = maxChars > 0 ? new BodyContentHandler(maxChars) : new BodyContentHandler(-1);

			try {
				parser.parse(stream, handler, new Metadata(), new ParseContext());
			}
			catch (WriteLimitReachedException e) {
				log.debug("Document truncated at {} chars", maxChars);
			}

			String text = handler.toString().trim();

			if (text.isEmpty()) {
				log.warn("No text extracted from document; it may be a non-textual file (image, etc.)");

				return "";
			}

			return text;
		}
		catch (ZeroByteFileException e) {
			log.debug("Empty document, skipping");
			return "";
		}
		catch (SAXException | TikaException e) {
			throw new IOException("Failed to parse document content", e);
		}
	}

	private SearchMappingProperties.Mapping _findMapping(JsonNode itemJsonNode, JsonNode embeddedJsonNode) {
		if (_mappings == null) {
			return null;
		}

		SearchMappingProperties.Mapping bestMatch = null;
		int bestScore = -1;

		for (SearchMappingProperties.Mapping mapping : _mappings) {
			int score = _scoreMapping(mapping, itemJsonNode, embeddedJsonNode);

			if ((score > bestScore) ||
				((score == bestScore) && (bestMatch != null) &&
					(mapping.getMatchFieldDisambiguationOrder() < bestMatch.getMatchFieldDisambiguationOrder()))) {

				bestScore = score;
				bestMatch = mapping;
			}
		}

		if (bestMatch == null) {
			log.debug("Unknown embedded type: {}", embeddedJsonNode.toPrettyString());
		}

		return bestMatch;
	}

	private int _scoreMapping(
		SearchMappingProperties.Mapping mapping, JsonNode itemJsonNode, JsonNode embeddedJsonNode) {

		if (embeddedJsonNode.size() > 0) {
			if ((mapping.getMatchField() == null) || !embeddedJsonNode.has(mapping.getMatchField())) {
				return -1;
			}

			int score = 1; // matched on field presence

			if (mapping.getMatchActionHrefContains() != null) {
				String href = embeddedJsonNode.path("actions").path("get-by-scope").path("href").asText();

				if (href.contains(mapping.getMatchActionHrefContains())) {
					score++; // bonus for href match
				}
				else {
					return -1; // required constraint failed
				}
			}

			return score;
		}
		else {
			if (mapping.getMatchEntryClassName() != null) {
				String entryClassName = itemJsonNode.path("entryClassName").asText();

				if (entryClassName.equals(mapping.getMatchEntryClassName())) {
					return 1;
				}
			}
		}

		return -1;
	}

	private String _html2Text(String html, String title) throws IOException {
		try {
			Metadata metadata = new Metadata();
			metadata.set(HttpHeaders.CONTENT_TYPE, "text/html");

			return _tika.parseToString(new ByteArrayInputStream(html.getBytes()), metadata);
		}
		catch (ZeroByteFileException e) {
			log.debug("Empty HTML content for '{}'", title);
			return "";
		}
		catch (TikaException e) {
			throw new IOException(e);
		}
	}

	private String _resolveContentURL(
		Jwt jwt, SearchMappingProperties.Mapping mapping,
		JsonNode embeddedJsonNode, String itemURL, String siteKey) {

		if (mapping.getUrlPathPrefix() != null) {
			return _relative2AbsoluteURL(
				itemURL, mapping.getUrlPathPrefix() + embeddedJsonNode.path(mapping.getIdField()).asText());
		}

		// Dynamic resolution via Liferay APIs

		String resolvedPath = _displayPageUrlService.resolveUrl(embeddedJsonNode, jwt.getTokenValue(), siteKey);

		if (resolvedPath != null) {
			return _relative2AbsoluteURL(itemURL, resolvedPath);
		}

		return null;
	}

	private String _relative2AbsoluteURL(String referenceURL, String relativeContentURL) {
		if ((referenceURL == null) || referenceURL.isBlank() ||
			(relativeContentURL == null) || relativeContentURL.isBlank()) {

			return null;
		}

		try {
			return new URI(referenceURL).resolve(relativeContentURL).toString();
		}
		catch (URISyntaxException e) {
			log.warn("Failed to resolve URL: reference='{}', relative='{}'", referenceURL, relativeContentURL);

			return null;
		}
	}

	private StrategyResult _handleCms2Document(
		Jwt jwt, SearchMappingProperties.Mapping mapping, JsonNode itemJsonNode,
		JsonNode embeddedJsonNode, String itemURL, String title, String description,
		int remainingChars, String siteKey) throws IOException {

		JsonNode hrefJsonNode = embeddedJsonNode.path("file").path("link").path("href");

		if (!hrefJsonNode.isMissingNode()) {
			String href = hrefJsonNode.asText();

			DownloadedDoc doc = _downloadDocument(
				jwt, _relative2AbsoluteURL(itemURL, href), remainingChars);

			return new StrategyResult(doc.contentText(), doc.url());
		}

		return null;
	}

	private StrategyResult _handleContentFields(
		Jwt jwt, SearchMappingProperties.Mapping mapping, JsonNode itemJsonNode,
		JsonNode embeddedJsonNode, String itemURL, String title, String description,
		int remainingChars, String siteKey) {

		JsonNode contentFields = embeddedJsonNode.path("contentFields");

		if (contentFields.isArray()) {
			StringBuilder sb = new StringBuilder();

			for (JsonNode contentField : contentFields) {
				if ("string".equals(contentField.path("dataType").asText())) {
					sb.append(contentField.path("contentFieldValue").path("data").asText()).append("\n");
				}
			}

			String text = sb.toString();
			String contentText = text.length() > remainingChars ? text.substring(0, remainingChars) : text;
			String contentURL = _resolveContentURL(
				jwt, mapping, embeddedJsonNode, itemURL, siteKey);

			return new StrategyResult(contentText, contentURL);
		}

		return null;
	}

	private StrategyResult _handleDocument(
		Jwt jwt, SearchMappingProperties.Mapping mapping, JsonNode itemJsonNode,
		JsonNode embeddedJsonNode, String itemURL, String title, String description,
		int remainingChars, String siteKey) throws IOException {

		String encodingFormat = embeddedJsonNode.path("encodingFormat").asText();
		String contentText;
		String contentURL;

		if ("application/vnd+liferay.video.external.shortcut+html".equals(encodingFormat)) {
			contentText = description;
			contentURL = _resolveContentURL(
				jwt, mapping, embeddedJsonNode, itemURL, siteKey);
		}
		else {
			String relativeContentURL = embeddedJsonNode.path("contentUrl").asText();
			String absoluteURL = relativeContentURL.isBlank()
				? itemURL
				: _relative2AbsoluteURL(itemURL, relativeContentURL);
			DownloadedDoc doc = _downloadDocument(jwt, absoluteURL, remainingChars);
			contentText = doc.contentText();
			contentURL = doc.url();
		}

		return new StrategyResult(contentText, contentURL);
	}

	private StrategyResult _handleHtml(
		Jwt jwt, SearchMappingProperties.Mapping mapping, JsonNode itemJsonNode,
		JsonNode embeddedJsonNode, String itemURL, String title, String description,
		int remainingChars, String siteKey) throws IOException {

		String text = _html2Text(embeddedJsonNode.path(mapping.getTextField()).asText(), title);
		String contentText = text.length() > remainingChars ? text.substring(0, remainingChars) : text;
		String contentURL = _resolveContentURL(
			jwt, mapping, embeddedJsonNode, itemURL, siteKey);

		return new StrategyResult(contentText, contentURL);
	}

	private SearchResult _findBestTitleMatch(List<SearchResult> candidates, String keywords) {
		String normalizedQuery = keywords.toLowerCase().replaceAll("[^a-z0-9 ]", " ");
		Set<String> queryWords = Arrays.stream(normalizedQuery.split("\\s+"))
			.filter(w -> !w.isEmpty() && !_SUMMARY_STOP_WORDS.contains(w))
			.collect(Collectors.toSet());

		SearchResult best = candidates.get(0);
		int bestScore = -1;

		for (SearchResult candidate : candidates) {
			String normalizedTitle = candidate.title().toLowerCase().replaceAll("[^a-z0-9 ]", " ");
			int score = (int) Arrays.stream(normalizedTitle.split("\\s+"))
				.filter(w -> !w.isEmpty() && queryWords.contains(w))
				.count();

			if (score > bestScore) {
				bestScore = score;
				best = candidate;
			}
		}

		return best;
	}

	private record DownloadedDoc(String contentText, String url) {}

	private record StrategyResult(String contentText, String contentURL) {}

	@FunctionalInterface
	private interface StrategyHandler {
		StrategyResult handle(
			Jwt jwt, SearchMappingProperties.Mapping mapping, JsonNode itemJsonNode,
			JsonNode embeddedJsonNode, String itemURL, String title, String description,
			int remainingChars, String siteKey) throws IOException;
	}
}

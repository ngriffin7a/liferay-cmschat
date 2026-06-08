// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.demo.cmschat.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.liferay.demo.cmschat.dto.ChatRequest;
import com.liferay.demo.cmschat.dto.SearchResult;
import com.liferay.demo.cmschat.service.PromptService;
import com.liferay.demo.cmschat.service.SearchService;

import com.openai.client.OpenAIClient;
import com.openai.models.ChatCompletion;
import com.openai.models.ChatCompletionCreateParams;

/**
 * @author Neil Griffin
 */
@RestController
@CrossOrigin
@RequestMapping("/cmschat")
public class ChatController {

	private static final Logger log = LoggerFactory.getLogger(ChatController.class);

	private static final ObjectMapper _objectMapper = new ObjectMapper();

	private static final Parser _markdownParser = Parser.builder().build();

	private static final HtmlRenderer _htmlRenderer = HtmlRenderer.builder()
		.attributeProviderFactory(ctx -> (node, tagName, attributes) -> {
			if (node instanceof Link) {
				attributes.put("target", "_blank");
			}
		})
		.build();

	@Autowired
	private OpenAIClient _openAIClient;

	@Autowired
	private PromptService _promptService;

	@Autowired
	private SearchService _searchService;

	@PostMapping("/completions")
	String postCompletions(@AuthenticationPrincipal Jwt jwt, @RequestBody ChatRequest chatRequest) {
		log.debug("jwt={}", jwt);

		List<String> messages = chatRequest.getMessages();
		List<SearchResult> searchResults = List.of();

		if (!messages.isEmpty()) {
			try {
				searchResults = _searchService.getSearchResults(
					jwt,
					messages.get(messages.size() - 1),
					chatRequest.getBlueprintExternalReferenceCode(),
					chatRequest.getSiteKey());
			}
			catch (IOException e) {
				log.error("Failed to retrieve search results", e);

				throw new RuntimeException(e);
			}
		}

		ChatCompletionCreateParams params = _promptService.buildParams(chatRequest, searchResults);
		ChatCompletion completion = _openAIClient.chat().completions().create(params);

		return completion.choices().stream()
			.findFirst()
			.flatMap(choice -> choice.message().content())
			.map(markdown -> {
				try {
					return _objectMapper.writeValueAsString(Map.of("assistant", _convertToHtml(markdown)));
				}
				catch (JsonProcessingException e) {
					throw new RuntimeException(e);
				}
			})
			.orElse("");
	}

	private static String _convertToHtml(String markdown) {
		Node document = _markdownParser.parse(markdown);

		return _htmlRenderer.render(document);
	}
}

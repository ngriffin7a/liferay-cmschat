// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.demo.cmschat.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.liferay.demo.cmschat.dto.ChatRequest;
import com.liferay.demo.cmschat.dto.SearchResult;

import com.openai.models.ChatCompletionAssistantMessageParam;
import com.openai.models.ChatCompletionCreateParams;
import com.openai.models.ChatCompletionMessageParam;
import com.openai.models.ChatCompletionSystemMessageParam;
import com.openai.models.ChatCompletionUserMessageParam;

/**
 * @author Neil Griffin
 */
@Service
public class PromptService {

	@Value("${cmschat.openai.model}")
	private String _model;

	@Value("${cmschat.openai.system-prompt}")
	private String _systemPrompt;

	@Value("${cmschat.instruction.keyword-highlight}")
	private String _keywordHighlight;

	@Value("${cmschat.instruction.title}")
	private String _titleInstruction;

	@Value("${cmschat.instruction.url}")
	private String _urlInstruction;

	@Value("${cmschat.instruction.focus}")
	private String _focusInstruction;

	@Value("${cmschat.instruction.content}")
	private String _contentInstruction;

	public ChatCompletionCreateParams buildParams(ChatRequest chatRequest, List<SearchResult> searchResults) {
		ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder();

		builder.addMessage(ChatCompletionMessageParam.ofChatCompletionSystemMessageParam(
			ChatCompletionSystemMessageParam.builder()
				.role(ChatCompletionSystemMessageParam.Role.SYSTEM)
				.content(ChatCompletionSystemMessageParam.Content.ofTextContent(_systemPrompt))
				.build()));

		List<String> messages = chatRequest.getMessages();
		List<String> roles = chatRequest.getRoles();

		for (int i = 0; i < messages.size(); i++) {
			String message = messages.get(i);

			if ("assistant".equals(roles.get(i))) {
				builder.addMessage(ChatCompletionMessageParam.ofChatCompletionAssistantMessageParam(
					ChatCompletionAssistantMessageParam.builder()
						.role(ChatCompletionAssistantMessageParam.Role.ASSISTANT)
						.content(ChatCompletionAssistantMessageParam.Content.ofTextContent(message))
						.build()));
			}
			else {
				builder.addMessage(_userMessage(_keywordHighlight + "\n" + message + "\n---\n"));

				for (SearchResult result : searchResults) {
					String msg = _buildResultMessage(result, message);

					if (!msg.isBlank()) {
						builder.addMessage(_userMessage(msg));
					}
				}
			}
		}

		return builder.model(_model).build();
	}

	private String _buildResultMessage(SearchResult result, String query) {
		String typeTitle = result.typeTitle();
		StringBuilder msg = new StringBuilder();

		String title = result.title();

		if ((title != null) && !title.isBlank()) {
			msg.append(String.format(_titleInstruction, typeTitle)).append("\n").append(title).append("\n---\n");
		}

		String contentURL = result.contentURL();

		if ((contentURL != null) && !contentURL.isBlank()) {
			msg.append(String.format(_urlInstruction, typeTitle)).append("\n").append(contentURL).append("\n---\n");
		}

		String textContent = result.contentText();

		if ((textContent != null) && !textContent.isBlank()) {
			msg.append(_focusInstruction).append("\n").append(query).append("\n---\n")
				.append(String.format(_contentInstruction, typeTitle)).append(textContent);
		}

		return msg.toString();
	}

	private ChatCompletionMessageParam _userMessage(String text) {
		return ChatCompletionMessageParam.ofChatCompletionUserMessageParam(
			ChatCompletionUserMessageParam.builder()
				.role(ChatCompletionUserMessageParam.Role.USER)
				.content(ChatCompletionUserMessageParam.Content.ofTextContent(text))
				.build());
	}
}

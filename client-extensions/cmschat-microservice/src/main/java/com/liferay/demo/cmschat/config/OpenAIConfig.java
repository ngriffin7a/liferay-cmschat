// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.demo.cmschat.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Neil Griffin
 */
@Configuration
public class OpenAIConfig {

	@Value("${openai.key}")
	private String _apiKey;

	@Bean
	public OpenAIClient openAIClient() {
		return OpenAIOkHttpClient.builder().apiKey(_apiKey).build();
	}
}

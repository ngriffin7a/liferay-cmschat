// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.demo.cmschat.http;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * @author Neil Griffin
 */
@Service
public class LiferayClient {

	@Value("${liferay.headless.api.base.url}")
	private String _baseUrl;

	@Value("${liferay.admin.email}")
	private String _adminEmail;

	@Value("${liferay.admin.password}")
	private String _adminPassword;

	private final HttpClient _httpClient = HttpClient.newBuilder()
		.proxy(ProxySelector.getDefault())
		.version(HttpClient.Version.HTTP_1_1)
		.build();

	public String get(String relativePath, String token) throws IOException {
		HttpRequest request = _buildRequest(_baseUrl + relativePath, "Bearer " + token);
		HttpResponse<String> response = _send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() >= 400) {
			throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
		}

		return response.body();
	}

	public String getWithBasicAuth(String relativePath) throws IOException {
		String credentials = Base64.getEncoder().encodeToString(
			(_adminEmail + ":" + _adminPassword).getBytes(StandardCharsets.UTF_8));

		HttpRequest request = _buildRequest(_baseUrl + relativePath, "Basic " + credentials);
		HttpResponse<String> response = _send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() >= 400) {
			throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
		}

		return response.body();
	}

	public DownloadResult download(String absoluteUrl, String token) throws IOException {
		HttpRequest request = _buildRequest(absoluteUrl, token);
		HttpResponse<byte[]> response = _send(request, HttpResponse.BodyHandlers.ofByteArray());
		String contentType = response.headers().firstValue("Content-Type").orElse("");

		return new DownloadResult(response.body(), contentType);
	}

	private HttpRequest _buildRequest(String url, String authHeaderValue) throws IOException {
		try {
			return HttpRequest.newBuilder()
				.uri(new URI(url))
				.header("Authorization", authHeaderValue)
				.header("Content-Type", "application/json")
				.GET()
				.build();
		}
		catch (URISyntaxException e) {
			throw new IOException(e);
		}
	}

	private <T> HttpResponse<T> _send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			return _httpClient.send(request, handler);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		}
	}

	public record DownloadResult(byte[] body, String contentType) {

		public boolean isPlainText() {
			return contentType.contains("text/plain");
		}

		public String asString() {
			return new String(body, StandardCharsets.UTF_8);
		}
	}
}

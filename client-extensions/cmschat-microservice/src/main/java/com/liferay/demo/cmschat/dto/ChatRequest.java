// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.demo.cmschat.dto;

import java.util.List;

/**
 * @author Neil Griffin
 */
public class ChatRequest {

	private String blueprintExternalReferenceCode;
	private List<String> messages;
	private List<String> roles;
	private String scope;
	private String siteKey;

	public String getBlueprintExternalReferenceCode() {
		return blueprintExternalReferenceCode;
	}

	public List<String> getMessages() {
		return messages;
	}

	public List<String> getRoles() {
		return roles;
	}

	public String getScope() {
		return scope;
	}

	public void setBlueprintExternalReferenceCode(String blueprintExternalReferenceCode) {
		this.blueprintExternalReferenceCode = blueprintExternalReferenceCode;
	}

	public void setMessages(List<String> messages) {
		this.messages = messages;
	}

	public void setRoles(List<String> roles) {
		this.roles = roles;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public String getSiteKey() {
		return siteKey;
	}

	public void setSiteKey(String siteKey) {
		this.siteKey = siteKey;
	}
}

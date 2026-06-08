// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.demo.cmschat.dto;

/**
 * @author Neil Griffin
 */
public record SearchResult(
	String title, String description, String itemURL,
	String contentText, String contentURL,
	String type, String typeTitle) {
}

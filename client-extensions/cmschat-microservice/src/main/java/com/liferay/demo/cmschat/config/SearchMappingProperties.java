// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.demo.cmschat.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @author Neil Griffin
 */
@Component
@ConfigurationProperties(prefix = "cmschat.search")
public class SearchMappingProperties {

	private List<Mapping> mappings;

	public List<Mapping> getMappings() {
		return mappings;
	}

	public void setMappings(List<Mapping> mappings) {
		this.mappings = mappings;
	}

	public static class Mapping {

		private String idField;
		private String matchActionHrefContains;
		private String matchEntryClassName;
		private String matchField;
		private int matchFieldDisambiguationOrder = Integer.MAX_VALUE;
		private String strategy;
		private String textField;
		private String title;
		private String type;
		private String urlPathPrefix;

		public String getIdField() {
			return idField;
		}

		public String getMatchActionHrefContains() {
			return matchActionHrefContains;
		}

		public String getMatchEntryClassName() {
			return matchEntryClassName;
		}

		public String getMatchField() {
			return matchField;
		}

		public int getMatchFieldDisambiguationOrder() {
			return matchFieldDisambiguationOrder;
		}

		public String getStrategy() {
			return strategy;
		}

		public String getTextField() {
			return textField;
		}

		public String getTitle() {
			return title;
		}

		public String getType() {
			return type;
		}

		public String getUrlPathPrefix() {
			return urlPathPrefix;
		}

		public void setIdField(String idField) {
			this.idField = idField;
		}

		public void setMatchActionHrefContains(String matchActionHrefContains) {
			this.matchActionHrefContains = matchActionHrefContains;
		}

		public void setMatchEntryClassName(String matchEntryClassName) {
			this.matchEntryClassName = matchEntryClassName;
		}

		public void setMatchField(String matchField) {
			this.matchField = matchField;
		}

		public void setMatchFieldDisambiguationOrder(int matchFieldDisambiguationOrder) {
			this.matchFieldDisambiguationOrder = matchFieldDisambiguationOrder;
		}

		public void setStrategy(String strategy) {
			this.strategy = strategy;
		}

		public void setTextField(String textField) {
			this.textField = textField;
		}

		public void setTitle(String title) {
			this.title = title;
		}

		public void setType(String type) {
			this.type = type;
		}

		public void setUrlPathPrefix(String urlPathPrefix) {
			this.urlPathPrefix = urlPathPrefix;
		}
	}

}

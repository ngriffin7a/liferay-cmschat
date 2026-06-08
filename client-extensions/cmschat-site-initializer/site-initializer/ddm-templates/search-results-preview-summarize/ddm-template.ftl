<style>
        #preview-popup {
        position: absolute;
        width: 459px;
        height: 594px;
        background-color: #fff;
        border: 1px solid #000;
        border-radius: 10px;
        display: none;
        padding: 20px;
        box-shadow: 0 0 10px rgba(0, 0, 0, 0.1);
        z-index: 9999; /* Add this line */
    }

    #preview-content {
        width: 100%;
        height: 95%;
        overflow: auto;
    }

    #close-preview {
        position: absolute;
        top: 10px;
        right: 10px;
    }

    .search-document-tags {
        margin-top: 16px !important;
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
        column-gap: 16px;
        row-gap: 12px;
    }

    .search-document-tags .taglib-asset-categories-summary {
        margin: 0 !important;
        display: flex;
        flex-direction: column;
        background-color: #f8f9fa;
        padding: 6px 12px;
        border-radius: 6px;
        border: 1px solid #e9ecef;
        font-size: 0.75rem;
        color: #6c757d;
        text-transform: uppercase;
        letter-spacing: 0.5px;
    }

    .search-document-tags .taglib-asset-categories-summary a {
        margin: 4px 0 0 0 !important;
        padding: 0 !important;
        font-size: 0.95rem;
        font-weight: 600;
        text-transform: none;
        letter-spacing: normal;
        word-break: break-word;
    }

    .search-document-tags .taglib-asset-tags-summary {
        grid-column: 1 / -1;
        display: flex;
        flex-direction: row;
        flex-wrap: wrap;
        gap: 8px;
        margin: 8px 0 0 0 !important;
    }

    .search-document-tags .taglib-asset-tags-summary a {
        margin: 0 !important;
    }
</style>

<div id="preview-popup" style="display: none;">
	<div id="preview-content"></div>
	<button id="close-preview" class="btn btn-monospaced btn-primary btn-xs" type="button" aria-label="Close" title="Close">
		<svg class="lexicon-icon lexicon-icon-times" role="presentation" viewBox="0 0 512 512">
			<path class="lexicon-icon-outline" d="M300.4,256L467,89.4c29.6-29.6-14.8-74.1-44.4-44.4L256,211.6L89.4,45C59.8,15.3,15.3,59.8,45,89.4L211.6,256L45,422.6 c-29.7,29.7,14.7,74.1,44.4,44.4L256,300.4L422.6,467c29.7,29.7,74.1-14.7,44.4-44.4L300.4,256z"></path>
		</svg>
	</button>
</div>



<div class="c-mb-4 c-mt-4 search-total-label">
    <#if searchContainer.getTotal() == 1>
        ${languageUtil.format(locale, "x-result-for-x", [searchContainer.getTotal(), "<strong>" + htmlUtil.escape(searchResultsPortletDisplayContext.getKeywords()) + "</strong>"], false)}
    <#else>
        ${languageUtil.format(locale, "x-results-for-x", [searchContainer.getTotal(), "<strong>" + htmlUtil.escape(searchResultsPortletDisplayContext.getKeywords()) + "</strong>"], false)}
    </#if>
</div>

<div class="display-list">
	<ul class="list-group" id="search-results-display-list">
        <#if entries?has_content>
            <#list entries as entry>
                <#assign viewURL = entry.getViewURL() />
                <#assign _entryContent = entry.getContent()!"" />
                <#assign externalURL = "" />
                <#assign subtitle = "" />
                <#if _entryContent?contains("externalURL:")>
                    <#assign _urlPart = _entryContent?keep_after("externalURL:")?trim />
                    <#assign _urlLine = _urlPart?split("\n")[0]?trim />
                    <#if _urlLine?starts_with("http")>
                        <#assign externalURL = _urlLine />
                    </#if>
                </#if>
                <#if _entryContent?contains("subtitle:")>
                    <#assign _subtitlePart = _entryContent?keep_after("subtitle:")?trim />
                    <#assign subtitle = _subtitlePart?split("\n")[0]?trim />
                </#if>
            
				<li class="list-group-item list-group-item-flex">
					<div class="autofit-col">
                        <#if entry.isThumbnailVisible()>
							<span class="sticker">
								<span class="sticker-overlay">
									<img
											alt="${languageUtil.get(locale, "thumbnail")}"
											class="sticker-img"
											src="${entry.getThumbnailURLString()}"
									/>
								</span>
							</span>
                        <#elseif entry.isUserPortraitVisible() && stringUtil.equals(entry.getClassName(), userClassName)>
                            <@liferay_ui["user-portrait"] userId=entry.getAssetEntryUserId() />
                        <#elseif entry.getModelResource()?contains("Link")>
							<span class="sticker sticker-rounded sticker-secondary sticker-static">
								<@clay.icon symbol="link" />
							</span>
                        <#elseif entry.getModelResource()?contains("Forum")>
							<span class="sticker sticker-rounded sticker-secondary sticker-static">
								<@clay.icon symbol="message-boards" />
							</span>
                        <#elseif entry.isIconVisible()>
							<span class="sticker sticker-rounded sticker-secondary sticker-static">
								<@clay.icon symbol="${entry.getIconId()}" />
							</span>
                        </#if>
					</div>

					<div class="autofit-col autofit-col-expand">
						<section class="autofit-section">
							<div class="c-mt-0 list-group-title">
                                <#if externalURL?has_content>
                                    <a href="${externalURL}" title="${subtitle}" target="_blank">
                                        ${entry.getHighlightedTitle()}
                                    </a>
                                    <span>&nbsp;</span>
                                    <a href="#" class="preview-link" data-fullview="${externalURL}">[<@liferay.language key="preview" />]</a>
                                <#else>
                                    <a href="${viewURL}">
                                        ${entry.getHighlightedTitle()}
                                    </a>
                                    <span>&nbsp;</span>
                                    <#assign assetType = entry.getModelResource() />
                                    <a href="#" class="preview-link" data-fullview="${viewURL}">[<@liferay.language key="preview" />]</a>
                                    <a href="#" class="summarize-link" data-asset-type="${assetType}" data-asset-title="${htmlUtil.escape(entry.getTitle())}">[<@liferay.language key="summary" />]</a>
                                </#if>
							</div>

							<div class="search-results-metadata">
								<p class="list-group-subtext">
                                    <#if entry.isModelResourceVisible()>
										<span class="subtext-item">
											<strong>${entry.getModelResource()}</strong>
										</span>
                                    </#if>

                                    <#if entry.isLocaleReminderVisible()>
										<span class="lfr-portal-tooltip" title="${entry.getLocaleReminder()}">
											<@clay["icon"] symbol="${entry.getLocaleLanguageId()?lower_case?replace('_', '-')}" />
										</span>
                                    </#if>

                                    <#if entry.isCreatorVisible()>
										<span class="subtext-item">
											&#183;

											<@liferay.language key="written-by" />

											<strong>${htmlUtil.escape(entry.getCreatorUserName())}</strong>
										</span>
                                    </#if>

                                    <#if entry.isCreationDateVisible()>
										<span class="subtext-item">
											<@liferay.language key="on-date" />

                                            ${entry.getCreationDateString()}
										</span>
                                    </#if>
								</p>

                                <#if entry.isContentVisible()>
                                    <#assign _rawContent = entry.getContent()!"" />
                                    <#assign _displayContent = "" />
                                    <#if _rawContent?contains("externalURL:")>
                                        <#-- News Link: suppress content, URL already used for link -->
                                    <#elseif _rawContent?starts_with("file:")>
                                        <#-- Basic Document: suppress content, title already in link -->
                                    <#elseif _rawContent?contains("body:")>
                                        <#assign _displayContent = _rawContent?keep_after("body:")?trim />
                                        <#if _displayContent?contains(", r_")>
                                            <#assign _displayContent = _displayContent?keep_before(", r_") />
                                        </#if>
                                    <#elseif _rawContent?starts_with("content:")>
                                        <#assign _displayContent = _rawContent?keep_after("content:")?trim />
                                    <#else>
                                        <#assign _displayContent = _rawContent />
                                    </#if>
                                    <#if _displayContent?has_content>
									<p class="list-group-subtext">
										<span class="subtext-item">
											${_displayContent}
										</span>
									</p>
                                    </#if>
                                </#if>

                                <#if entry.isFieldsVisible()>
									<p class="list-group-subtext">
                                        <#assign separate = false />

                                        <#list entry.getFieldDisplayContexts() as fieldDisplayContext>
                                            <#if separate>
												&#183;
                                            </#if>

											<span class="badge">${fieldDisplayContext.getName()}</span>

											<span>${fieldDisplayContext.getValuesToString()}</span>

                                            <#assign separate = true />
                                        </#list>
									</p>
                                </#if>

                                <#if entry.isAssetCategoriesOrTagsVisible()>
									<div class="c-mt-2 h6 search-document-tags text-default">
                                        <@liferay_asset["asset-tags-summary"]
                                        className=entry.getClassName()
                                        classPK=entry.getClassPK()
                                        paramName=entry.getFieldAssetTagNames()
                                        portletURL=entry.getPortletURL()
                                        />

                                        <@liferay_asset["asset-categories-summary"]
                                        className=entry.getClassName()
                                        classPK=entry.getClassPK()
                                        paramName=entry.getFieldAssetCategoryIds()
                                        portletURL=entry.getPortletURL()
                                        />
									</div>
                                </#if>

                                <#if entry.isDocumentFormVisible()>
									<div class="expand-details text-default">
										<span class="list-group-text text-2">
											<a class="shadow-none" href="javascript:void(0);">
												<@liferay.language key="details" />...
											</a>
										</span>
									</div>

									<div class="hide search-results-list table-details table-responsive">
										<table class="table table-head-bordered table-hover table-sm table-striped">
											<thead>
											<tr>
												<th class="table-cell-expand-smaller table-cell-text-end">
                                                    <@liferay.language key="key" />
												</th>
												<th class="table-cell-expand">
                                                    <@liferay.language key="value" />
												</th>
											</tr>
											</thead>

											<tbody>
                                            <#list entry.getDocumentFormFieldDisplayContexts() as fieldDisplayContext>
												<tr>
													<td class="table-cell-expand-smaller table-cell-text-end table-details-content">
														<strong>${htmlUtil.escape(fieldDisplayContext.getName())}</strong>
													</td>
													<td class="table-cell-expand table-details-content">
														<code>
                                                            ${fieldDisplayContext.getValuesToString()}
														</code>
													</td>
												</tr>
                                            </#list>
											</tbody>
										</table>
									</div>
                                </#if>
							</div>
						</section>
					</div>

                    <#if entry.isAssetRendererURLDownloadVisible()>
						<div class="autofit-col">
							<span
									class="c-mt-2 lfr-portal-tooltip"
									title="${languageUtil.get(locale, "download")}"
							>
								<@clay.link
                                aria\-label="${languageUtil.format(locale, 'download-x', [entry.getTitle()])}"
                                cssClass="link-monospaced link-outline link-outline-borderless link-outline-secondary"
                                displayType="secondary"
                                href="${entry.getAssetRendererURLDownload()}"
                                >
                                    <@clay.icon symbol="download" />
                                </@clay.link>
							</span>
						</div>
                    </#if>
				</li>
            </#list>
        </#if>
	</ul>
</div>

<@liferay_aui.script use="aui-base">
	A.one('#search-results-display-list').delegate(
	'click',
	function(event) {
	var currentTarget = event.currentTarget;

	currentTarget.siblings('.search-results-list').toggleClass('hide');
	},
	'.expand-details'
	);
</@liferay_aui.script>


<script>
	document.getElementById('close-preview').addEventListener('click', function() {
		document.getElementById('preview-popup').style.display = 'none';
	});

	document.querySelectorAll('.preview-link').forEach(function(link) {
		link.addEventListener('click', function(e) {
			e.preventDefault();
			var previewPopup = document.getElementById('preview-popup');
			var previewContent = document.getElementById('preview-content');
			previewContent.innerHTML = '<iframe src="' + this.dataset.fullview + '" width="100%" height="100%" style="border: none; opacity: 0; transition: opacity 0.2s ease-in;"></iframe>';

			var iframe = previewContent.querySelector('iframe');
			iframe.onload = function() {
				try {
					var style = document.createElement('style');
					style.innerHTML = '.control-menu-container, .product-menu, header, footer, nav { display: none !important; } #content { padding-top: 0 !important; }';
					iframe.contentWindow.document.head.appendChild(style);
				} catch(err) {
					console.warn('Unable to inject styles into iframe.', err);
				}
				iframe.style.opacity = '1';
			};
			var parentDiv = this.closest('div.autofit-col');
			parentDiv.appendChild(previewPopup);
			previewPopup.style.position = 'absolute';
			previewPopup.style.right = '0';
			previewPopup.style.top = '0';
			previewPopup.style.display = 'block';
		});
	});
</script>

<script>
	document.querySelectorAll('.summarize-link').forEach(function(link) {
		link.addEventListener('click', function(e) {
			e.preventDefault();
			document.dispatchEvent(new CustomEvent('cms-summarize', {
				detail: {
					assetType: this.dataset.assetType || 'content',
					assetTitle: this.dataset.assetTitle || 'Unknown'
				}
			}));
		});
	});
</script>
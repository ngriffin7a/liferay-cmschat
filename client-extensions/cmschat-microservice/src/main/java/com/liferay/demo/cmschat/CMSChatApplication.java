// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.demo.cmschat;

import com.liferay.client.extension.util.spring.boot3.ClientExtensionUtilSpringBootComponentScan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;


/**
 * @author  Neil Griffin
 */
@Import(ClientExtensionUtilSpringBootComponentScan.class)
@SpringBootApplication()
// @SpringBootApplication(scanBasePackages = "com.liferay.demo")
public class CMSChatApplication {

	public static void main(String[] args) throws Exception {
		SpringApplication.run(CMSChatApplication.class, args);
	}
}

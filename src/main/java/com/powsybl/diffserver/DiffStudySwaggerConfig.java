/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */
@Configuration
@EnableSwagger2
public class DiffStudySwaggerConfig {
    @Bean
    public Docket produceApi() {
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .select()
                .apis(RequestHandlerSelectors.basePackage(DiffStudyController.class.getPackage().getName()))
                .paths(paths())
                .build();
    }

    // Describe your apis
    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("DiffStudy API")
                .description("This is the documentation of the Diff Study REST API")
                .build();
    }

    // Only select apis that matches the given Predicates.
    private Predicate<String> paths() {
        // Match all paths except /error
        return Predicates.and(PathSelectors.regex("/" + ".*"),
                Predicates.not(PathSelectors.regex("/error.*")));
    }
}

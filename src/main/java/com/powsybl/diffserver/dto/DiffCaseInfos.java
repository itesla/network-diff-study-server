/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver.dto;

import io.swagger.annotations.ApiModel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */
@AllArgsConstructor
@Getter
@NoArgsConstructor
@ApiModel("Case infos")
public class DiffCaseInfos {

    private String name1;

    private UUID caseUuid1;

    private int forecastDistance1;

    private ZonedDateTime date1;

    private UUID storeUuid1;

    private String name2;

    private UUID caseUuid2;

    private int forecastDistance2;

    private ZonedDateTime date2;

    private UUID storeUuid2;

    private List<String> zone;

    private String substation;
}

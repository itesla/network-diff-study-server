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
import java.util.UUID;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */
@AllArgsConstructor
@Getter
@NoArgsConstructor
@ApiModel("Case infos")
public class CaseInfos {

    private String name;

    private String format;

    private UUID uuid;

    private ZonedDateTime date;

    private int forecastDistance;
}

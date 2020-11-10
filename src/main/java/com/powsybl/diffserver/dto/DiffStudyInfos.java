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

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */

@AllArgsConstructor
@NoArgsConstructor
@Getter
@ApiModel("Diff Study attributes")
public class DiffStudyInfos {

    String studyName;

    String description;

    String network1Id;

    String case1Format;

    String network2Id;

    String case2Format;

    String[] zone;
}

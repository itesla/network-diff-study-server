/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */

@AllArgsConstructor
@NoArgsConstructor
@Getter
public class NetworkInfos {

    private UUID networkUuid;

    private String networkId;

}

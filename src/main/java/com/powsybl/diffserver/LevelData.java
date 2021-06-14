/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LevelData {
    int id;
    double i;
    double v;
    String c;

    @Override
    public String toString() {
        return "LevelData{" +
                "id=" + id +
                ", i=" + i +
                ", v=" + v +
                ", c='" + c + '\'' +
                '}';
    }
}

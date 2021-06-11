/**
 * Copyright (c) 2020-2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.diffserver;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.eu>
 */
class VlDiffData {
    final String vlId;
    final String minVDelta;
    final String maxVDelta;
    final String minVDeltaPerc;
    final String maxVDeltaPerc;

    public VlDiffData(String vlId, String minVDelta, String maxVDelta, String minVDeltaPerc, String maxVDeltaPerc) {
        this.vlId = vlId;
        this.minVDelta = minVDelta;
        this.maxVDelta = maxVDelta;
        this.minVDeltaPerc = minVDeltaPerc;
        this.maxVDeltaPerc = maxVDeltaPerc;
    }

    public String getVlId() {
        return vlId;
    }

    public String getMinVDelta() {
        return minVDelta;
    }

    public String getMaxVDelta() {
        return maxVDelta;
    }

    @Override
    public String toString() {
        return "VlDiffData{" +
                "vlId='" + vlId + '\'' +
                ", minVDelta='" + minVDelta + '\'' +
                ", maxVDelta='" + maxVDelta + '\'' +
                ", minVDeltaPerc='" + minVDeltaPerc + '\'' +
                ", maxVDeltaPerc='" + maxVDeltaPerc + '\'' +
                '}';
    }

    public String getMinVDeltaPerc() {
        return minVDeltaPerc;
    }

    public String getMaxVDeltaPerc() {
        return maxVDeltaPerc;
    }

}
